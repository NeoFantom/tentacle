//go:build android

package tsbridge

import "tailscale.com/net/netmon"

// Android has no readable routing table for apps; netmon learns the default
// interface and gateway from ConnectivityManager via the app, as in
// Tailscale's Android client.
func setDefaultRoute(ifName, gateway string) {
	netmon.UpdateLastKnownDefaultRouteInterface(ifName)
	netmon.UpdateLastKnownDefaultGateway(gateway)
}
