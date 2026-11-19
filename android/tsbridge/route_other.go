//go:build !android

package tsbridge

// Desktop builds (tests) read routes from the OS.
func setDefaultRoute(ifName, gateway string) {}
