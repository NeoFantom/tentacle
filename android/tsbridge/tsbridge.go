// Package tsbridge runs an embedded, userspace Tailscale node (tsnet) inside
// the Tentacle Android app and exposes the app's local HTTP server on the
// tailnet.
//
// There is no VpnService and no TUN device: the node lives entirely inside
// the app process (gVisor netstack), so it works while another VPN or proxy
// app is active. Only one TCP port is reachable over the tailnet; each
// connection is proxied to 127.0.0.1:<port>, where Tentacle's own HTTP
// server enforces its token.
//
// The exported API is bound with gomobile and kept small: strings and ints
// only. Every exported function recovers panics and reports them as errors
// instead of crashing the app.
package tsbridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	_ "golang.org/x/mobile/bind" // gomobile bind support package.
	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/logtail"
	"tailscale.com/tsnet"
)

func init() {
	// Privacy: never upload logs to Tailscale's log service. SetNoLogsNoSupport
	// also tells the control server that this node opted out.
	envknob.SetNoLogsNoSupport()
	logtail.Disable()
	registerInterfaceGetter()
}

// Node state reported by StatusJSON.
const (
	StateStopped    = "stopped"
	StateStarting   = "starting"
	StateNeedsLogin = "needs_login"
	StateRunning    = "running"
	StateError      = "error"
)

type node struct {
	srv        *tsnet.Server
	controlURL string // as configured; empty means Tailscale's default
	hostname   string
	listener   net.Listener
	localPort  int
	ctx        context.Context
	cancel     context.CancelFunc
	started    chan struct{} // closed when srv.Start returned
	startErr   error
}

var (
	mu      sync.Mutex
	current *node
	lastErr string
)

// guard converts a panic into an error for the gomobile caller.
func guard(name string, err *error) {
	if p := recover(); p != nil {
		log.Printf("tsbridge: panic in %s: %v\n%s", name, p, debug.Stack())
		if err != nil {
			*err = fmt.Errorf("tsbridge: internal error in %s: %v", name, p)
		}
	}
}

// goSafe runs fn in a goroutine that cannot crash the process with a panic.
func goSafe(name string, fn func()) {
	go func() {
		defer guard(name, nil)
		fn()
	}()
}

// Start starts the node, replacing a running one. stateDir must be
// app-private storage; it keeps the node identity across restarts. An empty
// controlURL selects Tailscale's default control server; authKey is optional
// and used only for the first login. localPort is both the tailnet port and
// the loopback port of the app's HTTP server.
func Start(stateDir, hostname, controlURL, authKey string, localPort int) (err error) {
	defer guard("Start", &err)
	if stateDir == "" {
		return errors.New("state directory is required")
	}
	if localPort < 1 || localPort > 65535 {
		return fmt.Errorf("invalid port %d", localPort)
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return fmt.Errorf("state directory: %w", err)
	}
	// An Android app process has no usable HOME/TMPDIR; give Go and Tailscale
	// app-private defaults.
	for key, value := range map[string]string{
		"HOME": stateDir, "TMPDIR": stateDir, "XDG_CACHE_HOME": stateDir,
	} {
		if os.Getenv(key) == "" || os.Getenv(key) == "/" {
			_ = os.Setenv(key, value)
		}
	}
	stopLocked := func() *node {
		mu.Lock()
		defer mu.Unlock()
		old := current
		current = nil
		return old
	}
	if old := stopLocked(); old != nil {
		old.close()
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := &node{
		srv: &tsnet.Server{
			Dir:        stateDir,
			Hostname:   hostname,
			ControlURL: strings.TrimSpace(controlURL),
			AuthKey:    strings.TrimSpace(authKey),
			Ephemeral:  false,
			Logf:       backendLogf,
			UserLogf:   userLogf,
		},
		controlURL: strings.TrimSpace(controlURL),
		hostname:   hostname,
		localPort:  localPort,
		ctx:        ctx,
		cancel:     cancel,
		started:    make(chan struct{}),
	}
	mu.Lock()
	current = n
	lastErr = ""
	mu.Unlock()
	goSafe("start", func() { n.run() })
	return nil
}

func (n *node) run() {
	err := n.srv.Start()
	if err == nil {
		var ln net.Listener
		ln, err = n.srv.Listen("tcp", fmt.Sprintf(":%d", n.localPort))
		if err == nil {
			mu.Lock()
			n.listener = ln
			mu.Unlock()
			goSafe("accept", func() { n.accept(ln) })
		}
	}
	mu.Lock()
	n.startErr = err
	if err != nil && current == n {
		lastErr = err.Error()
	}
	mu.Unlock()
	close(n.started)
	if err != nil {
		log.Printf("tsbridge: start failed: %v", err)
	}
}

func (n *node) accept(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			if n.ctx.Err() == nil {
				log.Printf("tsbridge: accept stopped: %v", err)
			}
			return
		}
		goSafe("proxy", func() { proxy(n.ctx, conn, n.localPort) })
	}
}

type closeWriter interface{ CloseWrite() error }

// proxy streams one tailnet connection to the local HTTP server in both
// directions, propagating half-closes, without buffering whole bodies.
func proxy(ctx context.Context, tailnet net.Conn, port int) {
	defer tailnet.Close()
	var dialer net.Dialer
	dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	local, err := dialer.DialContext(dialCtx, "tcp", fmt.Sprintf("127.0.0.1:%d", port))
	cancel()
	if err != nil {
		log.Printf("tsbridge: local server unreachable: %v", err)
		return
	}
	defer local.Close()
	done := make(chan struct{}, 2)
	pipe := func(dst, src net.Conn) {
		defer func() { done <- struct{}{} }()
		defer guard("pipe", nil)
		buf := make([]byte, 64*1024)
		_, _ = io.CopyBuffer(dst, src, buf)
		if cw, ok := dst.(closeWriter); ok {
			_ = cw.CloseWrite()
		} else {
			_ = dst.Close()
		}
	}
	go pipe(local, tailnet)
	go pipe(tailnet, local)
	select {
	case <-done:
		// One direction finished; give the other one time to drain.
		select {
		case <-done:
		case <-ctx.Done():
		}
	case <-ctx.Done():
	}
}

func (n *node) close() {
	n.cancel()
	mu.Lock()
	ln := n.listener
	mu.Unlock()
	if ln != nil {
		_ = ln.Close()
	}
	select {
	case <-n.started:
	case <-time.After(15 * time.Second):
		log.Printf("tsbridge: start still running at stop; closing anyway")
	}
	if err := n.srv.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
		log.Printf("tsbridge: close: %v", err)
	}
}

// Stop stops the node. The login state stays in the state directory.
func Stop() (err error) {
	defer guard("Stop", &err)
	mu.Lock()
	n := current
	current = nil
	lastErr = ""
	mu.Unlock()
	if n != nil {
		n.close()
	}
	return nil
}

// running returns the current node once srv.Start has returned successfully.
func running() (*node, error) {
	mu.Lock()
	n := current
	mu.Unlock()
	if n == nil {
		return nil, errors.New("not started")
	}
	select {
	case <-n.started:
	default:
		return nil, errors.New("still starting")
	}
	if n.startErr != nil {
		return nil, n.startErr
	}
	return n, nil
}

// nodeStatus is the JSON shape returned by StatusJSON.
type nodeStatus struct {
	State    string `json:"state"` // stopped | starting | needs_login | running | error
	IP       string `json:"ip,omitempty"`
	DNSName  string `json:"dns_name,omitempty"`
	LoginURL string `json:"login_url,omitempty"`
	Error    string `json:"error,omitempty"`
	Peers    int    `json:"peers"`
	Direct   int    `json:"direct_peers"` // peers currently on a direct (non-relayed) path
}

// StatusJSON returns the node status as JSON (see nodeStatus). It never fails;
// errors are reported in the "error" field.
func StatusJSON() (out string) {
	st := status()
	defer func() {
		if p := recover(); p != nil {
			out = fmt.Sprintf(`{"state":"error","error":%q,"peers":0}`, fmt.Sprint(p))
		}
	}()
	b, _ := json.Marshal(st)
	return string(b)
}

func status() (st nodeStatus) {
	defer func() {
		if p := recover(); p != nil {
			st = nodeStatus{State: StateError, Error: fmt.Sprintf("internal error: %v", p)}
		}
	}()
	mu.Lock()
	n := current
	errText := lastErr
	mu.Unlock()
	if n == nil {
		return nodeStatus{State: StateStopped}
	}
	select {
	case <-n.started:
	default:
		return nodeStatus{State: StateStarting}
	}
	if n.startErr != nil {
		return nodeStatus{State: StateError, Error: n.startErr.Error()}
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		return nodeStatus{State: StateError, Error: err.Error()}
	}
	ctx, cancel := context.WithTimeout(n.ctx, 3*time.Second)
	defer cancel()
	s, err := lc.Status(ctx)
	if err != nil {
		return nodeStatus{State: StateError, Error: err.Error()}
	}
	st = nodeStatus{LoginURL: s.AuthURL, Error: errText}
	switch s.BackendState {
	case ipn.Running.String():
		st.State = StateRunning
	case ipn.NeedsLogin.String():
		st.State = StateNeedsLogin
	case ipn.NeedsMachineAuth.String():
		st.State = StateNeedsLogin
		st.Error = "waiting for the tailnet admin to approve this device"
	case ipn.Stopped.String():
		st.State = StateStopped
	default:
		st.State = StateStarting
	}
	for _, ip := range s.TailscaleIPs {
		if ip.Is4() {
			st.IP = ip.String()
			break
		}
	}
	if s.Self != nil {
		st.DNSName = strings.TrimSuffix(s.Self.DNSName, ".")
	}
	for _, p := range s.Peer {
		st.Peers++
		if p.CurAddr != "" {
			st.Direct++
		}
	}
	if st.State != StateNeedsLogin {
		st.LoginURL = ""
	}
	return st
}

// Login starts an interactive login; the login URL then appears in
// StatusJSON's "login_url".
func Login() (err error) {
	defer guard("Login", &err)
	n, err := running()
	if err != nil {
		return err
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(n.ctx, 10*time.Second)
	defer cancel()
	// A logout resets the profile's prefs, and the control client keeps its
	// URL until the backend restarts. Restart it with the configured control
	// server and name (like `tailscale login --login-server`), then log in.
	prefs := ipn.NewPrefs()
	prefs.ControlURL = n.controlURL
	if prefs.ControlURL == "" {
		prefs.ControlURL = ipn.DefaultControlURL
	}
	prefs.Hostname = n.hostname
	prefs.WantRunning = true
	if err := lc.Start(ctx, ipn.Options{UpdatePrefs: prefs}); err != nil {
		return err
	}
	return lc.StartLoginInteractive(ctx)
}

// Logout logs the node out of its tailnet and forgets its identity.
func Logout() (err error) {
	defer guard("Logout", &err)
	n, err := running()
	if err != nil {
		return err
	}
	lc, err := n.srv.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(n.ctx, 15*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// NetworkChanged tells the node that the device's network changed so that
// magicsock rebinds and re-runs endpoint discovery immediately.
func NetworkChanged() (err error) {
	defer guard("NetworkChanged", &err)
	n, err := running()
	if err != nil {
		return nil // Nothing to tell yet; the next start reads fresh interfaces.
	}
	if nm, ok := n.srv.Sys().NetMon.GetOK(); ok && nm != nil {
		nm.InjectEvent()
	}
	return nil
}

func backendLogf(format string, args ...any) {
	log.Printf("tsnet: "+format, args...)
}

func userLogf(format string, args ...any) {
	log.Printf("tsbridge: "+format, args...)
}
