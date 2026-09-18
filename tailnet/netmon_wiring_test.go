package mobile

import (
	"errors"
	"net/netip"
	"testing"

	"tailscale.com/net/netmon"
	"tailscale.com/types/logger"
	"tailscale.com/util/eventbus"
)

// These two tests check the mechanism, not the conversion. netif_test.go proves
// that a snapshot is turned into the right values; the tests here prove the two
// premises the Android build depends on:
//
//  1. registering a getter is actually what netmon consults, all the way through
//     its consumer path, and
//  2. a getter that fails does stop a monitor from being created.
//
// Neither is observable on a host any other way -- the android-tagged file cannot
// even be compiled here -- and if either premise were wrong, the fix would look
// correct in review and still leave the node unable to start on the phone.

// RegisterInterfaceGetter(nil) puts netmon back on the standard library path.
// Every test here has to leave that behind, or it leaks into the next one.
func TestNetmonConsumesWhatWeRegister(t *testing.T) {
	defer netmon.RegisterInterfaceGetter(nil)

	recs := []ifaceRecord{{
		Name:  "wlan0",
		Index: 3,
		MTU:   1500,
		MAC:   "aa:bb:cc:dd:ee:ff",
		Flags: 1 | 16,
		Addrs: []string{"192.0.2.10/24", "fe80::1%wlan0/64"},
	}}
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return interfacesFromRecords(recs), nil
	})

	seen := map[string][]netip.Prefix{}
	if err := netmon.ForeachInterface(func(ni netmon.Interface, pfxs []netip.Prefix) {
		seen[ni.Name] = pfxs
	}); err != nil {
		t.Fatalf("ForeachInterface returned an error: %v", err)
	}

	pfxs, ok := seen["wlan0"]
	if !ok {
		t.Fatalf("netmon did not see the interface we registered; it saw %v", seen)
	}
	if len(pfxs) != 2 {
		t.Fatalf("want both prefixes, got %v", pfxs)
	}
	// The host address has to survive. netmon turns these prefixes into the
	// node's own endpoint candidates, so 192.0.2.0 here would be an address no
	// peer can dial -- a node that connects only through a relay, with nothing
	// reporting an error anywhere.
	if got := pfxs[0].Addr().String(); got != "192.0.2.10" {
		t.Fatalf("netmon received %v, want the host address 192.0.2.10", got)
	}
	if pfxs[0].Bits() != 24 {
		t.Fatalf("prefix length lost: %v", pfxs[0])
	}
	if got := pfxs[1].Addr().String(); got != "fe80::1" {
		t.Fatalf("link-local address should have lost its zone, got %v", got)
	}
}

func TestAFailingInterfaceSourceStopsAMonitorFromStarting(t *testing.T) {
	defer netmon.RegisterInterfaceGetter(nil)

	// This is the Android 11+ failure, verbatim: the netlink route socket is
	// refused, so the interface query fails.
	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return nil, errors.New("netlinkrib: permission denied")
	})

	bus := eventbus.New()
	defer bus.Close()

	if _, err := netmon.New(bus, logger.Discard); err == nil {
		t.Fatal("netmon.New succeeded with a failing interface source; the reason the Android build " +
			"registers a getter at all is that it does not, and tsnet turns that error into a failed start")
	}
}

func TestAMonitorStartsWithOurSnapshot(t *testing.T) {
	defer netmon.RegisterInterfaceGetter(nil)

	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		return interfacesFromRecords([]ifaceRecord{{
			Name:  "wlan0",
			Index: 3,
			MTU:   1500,
			Flags: 1 | 16,
			Addrs: []string{"192.0.2.10/24"},
		}}), nil
	})

	bus := eventbus.New()
	defer bus.Close()

	m, err := netmon.New(bus, logger.Discard)
	if err != nil {
		t.Fatalf("netmon.New failed with a snapshot-backed getter: %v", err)
	}
	if m == nil {
		t.Fatal("netmon.New returned no monitor and no error")
	}
	// The monitor's first state is built from the getter's answer, which is what
	// a node reads while coming up.
	st := m.InterfaceState()
	if st == nil {
		t.Fatal("no interface state")
	}
	if _, ok := st.Interface["wlan0"]; !ok {
		t.Fatalf("the monitor did not pick up our interface: %v", st.Interface)
	}
}
