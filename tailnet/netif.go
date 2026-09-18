package mobile

import (
	"encoding/json"
	"fmt"
	"net"
	"strings"

	"tailscale.com/net/netmon"
)

// Why this file exists at all
//
// Android SDK 30 (Android 11) stopped letting apps use the netlink route socket.
// That is exactly the socket Go's own net.Interfaces() uses, and it has no
// fallback: net/interface_linux.go calls syscall.NetlinkRIB and reports
// "netlinkrib: permission denied" when the kernel refuses.
//
// netmon asks for the interface list while a node is starting:
//
//	tsnet.Up -> netmon.New -> interfaceStateUncached -> getState -> netInterfaces
//	         -> net.Interfaces                                         (netlink)
//
// and netmon.New returns that error straight up, which tsnet turns into a failed
// Up. A node that cannot read the interface list therefore cannot start at all.
// Tailscale knows this (issues 2293, 8126, 7757) and provides the way out:
// netmon.RegisterInterfaceGetter, which their own Android app uses to feed the
// list in from java.net.NetworkInterface. See netif_android.go for the wiring.
//
// The records below are that hand-off format. They are plain data so the
// conversion stays testable on a normal machine (netif_test.go).

// ifaceRecord is one interface as the Android side reports it.
type ifaceRecord struct {
	Name  string   `json:"name"`
	Index int      `json:"index"`
	MTU   int      `json:"mtu"`
	MAC   string   `json:"mac"`
	Flags uint32   `json:"flags"`
	Addrs []string `json:"addrs"` // CIDR strings: "192.0.2.10/24", "fe80::1/64"
}

// Flags carry Go's net.Flags bit values, so the Java side must translate from
// java.net.NetworkInterface's booleans into these numbers:
//
//	1 = FlagUp, 2 = FlagBroadcast, 4 = FlagLoopback,
//	8 = FlagPointToPoint, 16 = FlagMulticast, 32 = FlagRunning

func parseInterfaceSnapshot(jsonDoc string) ([]ifaceRecord, error) {
	var recs []ifaceRecord
	if err := json.Unmarshal([]byte(jsonDoc), &recs); err != nil {
		return nil, fmt.Errorf("interface snapshot is not the expected JSON array: %w", err)
	}
	return recs, nil
}

// interfacesFromRecords converts a snapshot into what netmon consumes.
//
// AltAddrs is always non-nil, even when the record has no addresses. That is not
// a detail: netmon's Interface.Addrs() falls back to net.Interface.Addrs() the
// moment AltAddrs is nil, and that fallback is the netlink call this whole file
// exists to avoid. An empty slice answers "no addresses" without asking the
// kernel anything.
func interfacesFromRecords(recs []ifaceRecord) []netmon.Interface {
	out := make([]netmon.Interface, 0, len(recs))
	for _, r := range recs {
		if strings.TrimSpace(r.Name) == "" {
			continue
		}
		ni := &net.Interface{
			Name:  r.Name,
			Index: r.Index,
			MTU:   r.MTU,
			Flags: net.Flags(r.Flags),
		}
		if hw, err := net.ParseMAC(r.MAC); err == nil {
			ni.HardwareAddr = hw
		}
		addrs := make([]net.Addr, 0, len(r.Addrs))
		for _, c := range r.Addrs {
			ip, ipnet, err := net.ParseCIDR(stripIPv6Zone(c))
			if err != nil {
				continue
			}
			// Put the host address back into the prefix.
			//
			// net.ParseCIDR hands back an *net.IPNet whose IP is already masked to
			// the network address ("192.0.2.10/24" comes back as 192.0.2.0/24),
			// while net.Interface.Addrs() -- what netmon is written against --
			// returns the interface's own address in .IP with the mask alongside.
			// Handing over the network address would advertise 192.0.2.0 as this
			// machine's address, so a peer dialing it could never connect directly
			// and every path would silently fall back to a relay.
			ipnet.IP = ip
			addrs = append(addrs, ipnet)
		}
		out = append(out, netmon.Interface{Interface: ni, AltAddrs: addrs})
	}
	return out
}

// stripIPv6Zone rewrites "fe80::1%wlan0/64" to "fe80::1/64".
//
// Java reports a link-local IPv6 address with its scope that way, and
// net.ParseCIDR cannot parse a zone at all. Dropping the zone is also correct for
// netmon: it works in netip.Prefix, which has no room for one.
func stripIPv6Zone(cidr string) string {
	i := strings.IndexByte(cidr, '%')
	if i < 0 {
		return cidr
	}
	if j := strings.IndexByte(cidr, '/'); j > i {
		return cidr[:i] + cidr[j:]
	}
	return cidr[:i]
}
