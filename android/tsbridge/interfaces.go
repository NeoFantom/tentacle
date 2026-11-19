package tsbridge

import (
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"sync"

	"tailscale.com/net/netmon"
)

// Android 11+ denies apps the netlink sockets behind Go's net.Interfaces
// ("permission denied"). As in Tailscale's own Android client
// (libtailscale/net.go and ifaceparse), the app enumerates interfaces with
// java.net.NetworkInterface and hands them over as JSON; netmon uses them via
// RegisterInterfaceGetter.

type addrJSON struct {
	IP        string `json:"ip"`
	PrefixLen int    `json:"prefixLen"`
}

type ifaceJSON struct {
	Name         string     `json:"name"`
	Index        int        `json:"index"`
	MTU          int        `json:"mtu"`
	Up           bool       `json:"up"`
	Broadcast    bool       `json:"broadcast"`
	Loopback     bool       `json:"loopback"`
	PointToPoint bool       `json:"pointToPoint"`
	Multicast    bool       `json:"multicast"`
	Addrs        []addrJSON `json:"addrs"`
}

// interfacesJSON is the SetInterfaces payload.
type interfacesJSON struct {
	Interfaces []ifaceJSON `json:"interfaces"`
	// DefaultInterface is the interface of Android's default network ("" when
	// offline); Gateway is its IPv4 default gateway, if any.
	DefaultInterface string `json:"defaultInterface"`
	Gateway          string `json:"gateway"`
}

var (
	ifaceMu  sync.Mutex
	ifaceSet bool
	ifaces   []netmon.Interface
)

func registerInterfaceGetter() {
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		ifaceMu.Lock()
		set, list := ifaceSet, ifaces
		ifaceMu.Unlock()
		if set {
			return list, nil
		}
		// Not supplied yet: works on Android 10 and older, and on desktop.
		system, err := net.Interfaces()
		if err != nil {
			return nil, err
		}
		out := make([]netmon.Interface, len(system))
		for i := range system {
			out[i].Interface = &system[i]
		}
		return out, nil
	})
}

// SetInterfaces replaces the interface list used by the node. Call it before
// Start and on every network change, followed by NetworkChanged.
func SetInterfaces(payload string) (err error) {
	defer guard("SetInterfaces", &err)
	list, defaultIf, gateway, err := parseInterfaces(payload)
	if err != nil {
		return err
	}
	ifaceMu.Lock()
	ifaces, ifaceSet = list, true
	ifaceMu.Unlock()
	setDefaultRoute(defaultIf, gateway)
	return nil
}

func parseInterfaces(payload string) ([]netmon.Interface, string, string, error) {
	var in interfacesJSON
	if err := json.Unmarshal([]byte(payload), &in); err != nil {
		return nil, "", "", fmt.Errorf("interfaces JSON: %w", err)
	}
	out := make([]netmon.Interface, 0, len(in.Interfaces))
	for _, it := range in.Interfaces {
		if it.Name == "" {
			continue
		}
		nif := netmon.Interface{
			Interface: &net.Interface{Name: it.Name, Index: it.Index, MTU: it.MTU},
			AltAddrs:  []net.Addr{},
		}
		for flag, on := range map[net.Flags]bool{
			net.FlagUp: it.Up, net.FlagBroadcast: it.Broadcast, net.FlagLoopback: it.Loopback,
			net.FlagPointToPoint: it.PointToPoint, net.FlagMulticast: it.Multicast,
		} {
			if on {
				nif.Flags |= flag
			}
		}
		for _, a := range it.Addrs {
			if addr, ok := netAddr(a); ok {
				nif.AltAddrs = append(nif.AltAddrs, addr)
			}
		}
		out = append(out, nif)
	}
	return out, in.DefaultInterface, in.Gateway, nil
}

func netAddr(a addrJSON) (net.Addr, bool) {
	ip, err := netip.ParseAddr(a.IP)
	if err != nil {
		return nil, false
	}
	raw := net.IP(ip.AsSlice())
	if ip.Zone() != "" {
		return &net.IPAddr{IP: raw, Zone: ip.Zone()}, true
	}
	bits := ip.BitLen()
	if a.PrefixLen < 0 || a.PrefixLen > bits {
		return &net.IPAddr{IP: raw}, true
	}
	return &net.IPNet{IP: raw, Mask: net.CIDRMask(a.PrefixLen, bits)}, true
}
