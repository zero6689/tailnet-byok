//go:build android

package mobile

import (
	"net"
	"sync"

	"tailscale.com/net/netmon"
)

// The Android half of the interface fix. Read netif.go first: it explains why a
// node cannot start when the interface list is unavailable, and why the list has
// to arrive as data from the platform API rather than from Go's own
// net.Interfaces().

var (
	ifaceMu       sync.RWMutex
	ifaceSnapshot []ifaceRecord
)

func init() {
	// package init, so the getter is in place before any node can start:
	// netmon reads the list during tsnet's Up, and an error there fails the Up.
	netmon.RegisterInterfaceGetter(interfacesForNetmon)
}

// SetInterfaceSnapshot hands the platform's interface list to the embedded node.
//
// The caller is the Android app, which enumerates java.net.NetworkInterface (the
// supported way on Android, and the only one that works from SDK 30 on) and sends
// the result as JSON. Call it before Start, and again after a network change:
// netmon reads the list when the node starts and on its own refresh cycle, so a
// snapshot taken now is also the answer it gets later.
//
// An empty or malformed document is not fatal. A malformed one returns an error
// the app can log; what matters is that the node keeps whatever list it had.
func SetInterfaceSnapshot(jsonDoc string) error {
	recs, err := parseInterfaceSnapshot(jsonDoc)
	if err != nil {
		return err
	}
	ifaceMu.Lock()
	ifaceSnapshot = recs
	ifaceMu.Unlock()
	return nil
}

func interfacesForNetmon() ([]netmon.Interface, error) {
	ifaceMu.RLock()
	recs := ifaceSnapshot
	ifaceMu.RUnlock()

	if len(recs) > 0 {
		return interfacesFromRecords(recs), nil
	}

	// Nothing has been handed down (an embedder that does not push a snapshot).
	// Below Android 11 the standard library still works, so use it when it does.
	if ifs, err := net.Interfaces(); err == nil {
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			addrs, _ := ifs[i].Addrs()
			if addrs == nil {
				addrs = []net.Addr{}
			}
			out[i] = netmon.Interface{Interface: &ifs[i], AltAddrs: addrs}
		}
		return out, nil
	}

	// Android 11+ denies the netlink socket, so net.Interfaces() fails with
	// "netlinkrib: permission denied" and netmon would treat that as fatal.
	// Reporting no interfaces instead keeps the node startable: it comes up, finds
	// no local endpoint candidates of its own, and falls back to a relay path. A
	// relayed node is slower than a direct one; a node that never starts is not a
	// node at all.
	return nil, nil
}
