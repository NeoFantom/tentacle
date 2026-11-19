package tsbridge

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"strings"
	"testing"
)

func TestParseInterfaces(t *testing.T) {
	list, def, gw, err := parseInterfaces(`{"defaultInterface":"wlan0","gateway":"192.0.2.1","interfaces":[
		{"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":[{"ip":"127.0.0.1","prefixLen":8}]},
		{"name":"wlan0","index":3,"mtu":1500,"up":true,"broadcast":true,"multicast":true,
		 "addrs":[{"ip":"192.0.2.10","prefixLen":24},{"ip":"fe80::1%wlan0","prefixLen":64},{"ip":"bogus","prefixLen":1}]},
		{"name":"","index":9}]}`)
	if err != nil {
		t.Fatal(err)
	}
	if def != "wlan0" || gw != "192.0.2.1" || len(list) != 2 {
		t.Fatalf("got %q %q %d", def, gw, len(list))
	}
	wlan := list[1]
	if !wlan.IsUp() || wlan.IsLoopback() || wlan.MTU != 1500 || len(wlan.AltAddrs) != 2 {
		t.Fatalf("bad wlan0: %+v", wlan)
	}
	if got := wlan.AltAddrs[0].String(); got != "192.0.2.10/24" {
		t.Fatalf("addr %s", got)
	}
	if _, _, _, err := parseInterfaces("not json"); err == nil {
		t.Fatal("expected error")
	}
}

func TestStatusWhenStoppedAndCallsWithoutNode(t *testing.T) {
	var st map[string]any
	if err := json.Unmarshal([]byte(StatusJSON()), &st); err != nil || st["state"] != StateStopped {
		t.Fatalf("status %v %v", st, err)
	}
	if err := Login(); err == nil {
		t.Fatal("login without node must fail")
	}
	if err := NetworkChanged(); err != nil {
		t.Fatal(err)
	}
	if err := Stop(); err != nil {
		t.Fatal(err)
	}
	if err := Start("", "h", "", "", 8765); err == nil {
		t.Fatal("empty state dir must fail")
	}
	if err := Start(t.TempDir(), "h", "", "", 0); err == nil {
		t.Fatal("bad port must fail")
	}
}

func TestProxyStreamsBothWaysWithHalfClose(t *testing.T) {
	backend, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer backend.Close()
	go func() {
		c, err := backend.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		req, _ := io.ReadAll(c) // until the client half-closes
		c.Write([]byte("echo:" + string(req)))
	}()
	port := backend.Addr().(*net.TCPAddr).Port
	front, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer front.Close()
	go func() {
		c, err := front.Accept()
		if err == nil {
			proxy(context.Background(), c, port)
		}
	}()
	client, err := net.Dial("tcp", front.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	payload := strings.Repeat("x", 1<<20)
	go func() {
		client.Write([]byte(payload))
		client.(*net.TCPConn).CloseWrite()
	}()
	got, err := io.ReadAll(client)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "echo:"+payload {
		t.Fatalf("got %d bytes", len(got))
	}
}
