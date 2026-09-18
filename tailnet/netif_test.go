package mobile

import (
	"net"
	"testing"
)

// These run on an ordinary machine (the android-tagged wiring cannot), because
// the conversion is where the subtle mistakes live. The one that matters most is
// the AltAddrs invariant: netmon's Interface.Addrs() silently reaches for netlink
// when AltAddrs is nil, which is the failure this whole change exists to avoid.

func TestInterfacesFromRecordsKeepsAltAddrsNonNil(t *testing.T) {
	got := interfacesFromRecords([]ifaceRecord{{
		Name:  "wlan0",
		Index: 3,
		MTU:   1500,
		MAC:   "aa:bb:cc:dd:ee:ff",
		Flags: 1 | 16, // up, multicast
	}})
	if len(got) != 1 {
		t.Fatalf("want 1 interface, got %d", len(got))
	}
	if got[0].Interface == nil {
		t.Fatal("the embedded *net.Interface must not be nil")
	}
	if got[0].Interface.Name != "wlan0" || got[0].Interface.Index != 3 || got[0].Interface.MTU != 1500 {
		t.Fatalf("record fields lost: %+v", got[0].Interface)
	}
	if got[0].Interface.Flags&1 == 0 {
		t.Fatal("the up flag was lost in translation")
	}
	if len(got[0].Interface.HardwareAddr) == 0 {
		t.Fatal("hardware address was not parsed")
	}
	if got[0].AltAddrs == nil {
		t.Fatal("AltAddrs must be non-nil even with no addresses, or Addrs() goes to netlink")
	}
	addrs, err := got[0].Addrs()
	if err != nil {
		t.Fatalf("Addrs() returned an error: %v", err)
	}
	if len(addrs) != 0 {
		t.Fatalf("want no addresses, got %v", addrs)
	}
}

func TestInterfacesFromRecordsParsesCIDRAndDropsZones(t *testing.T) {
	got := interfacesFromRecords([]ifaceRecord{{
		Name:  "wlan0",
		Addrs: []string{"192.0.2.10/24", "fe80::1%wlan0/64", "not-an-address", ""},
	}})
	addrs, err := got[0].Addrs()
	if err != nil {
		t.Fatalf("Addrs() returned an error: %v", err)
	}
	if len(addrs) != 2 {
		t.Fatalf("want the two parseable addresses and nothing else, got %d: %v", len(addrs), addrs)
	}

	// The address has to survive as the *host* address. net.ParseCIDR masks it to
	// the network address (192.0.2.0), and netmon is written against
	// net.Interface.Addrs(), which reports the interface's own address. Getting
	// this wrong advertises a network address as this machine's IP, which no peer
	// can dial, so every connection degrades to a relay without an error anywhere.
	v4, ok := addrs[0].(*net.IPNet)
	if !ok {
		t.Fatalf("want a *net.IPNet, got %T", addrs[0])
	}
	if v4.IP.String() != "192.0.2.10" {
		t.Fatalf("host address lost: got %v, want 192.0.2.10", v4.IP)
	}
	if ones, bits := v4.Mask.Size(); ones != 24 || bits != 32 {
		t.Fatalf("mask lost: /%d of %d, want /24 of 32", ones, bits)
	}

	// The zone has to be gone: netip prefixes cannot carry one, and
	// net.ParseCIDR refuses the string outright.
	v6, ok := addrs[1].(*net.IPNet)
	if !ok {
		t.Fatalf("want a *net.IPNet, got %T", addrs[1])
	}
	if v6.IP.String() != "fe80::1" {
		t.Fatalf("link-local address should have lost its zone, got %v", v6.IP)
	}
	if ones, bits := v6.Mask.Size(); ones != 64 || bits != 128 {
		t.Fatalf("v6 mask lost: /%d of %d, want /64 of 128", ones, bits)
	}
}

func TestParseInterfaceSnapshotRejectsJunkButAcceptsEmpty(t *testing.T) {
	if _, err := parseInterfaceSnapshot("{not json"); err == nil {
		t.Fatal("a malformed snapshot must be reported, not silently ignored")
	}
	recs, err := parseInterfaceSnapshot("[]")
	if err != nil || len(recs) != 0 {
		t.Fatalf("an empty array is valid and means zero records; got %v, %v", recs, err)
	}
	recs, err = parseInterfaceSnapshot(`[{"name":"rmnet0","index":2,"mtu":1500,"flags":1,"addrs":["10.0.0.5/30"]}]`)
	if err != nil {
		t.Fatalf("a realistic snapshot must parse: %v", err)
	}
	if len(recs) != 1 || recs[0].Name != "rmnet0" || len(recs[0].Addrs) != 1 {
		t.Fatalf("snapshot fields lost: %+v", recs)
	}
}

func TestInterfacesFromRecordsSkipsRecordsWithoutAName(t *testing.T) {
	if got := interfacesFromRecords([]ifaceRecord{{Index: 1}, {Name: "   "}}); len(got) != 0 {
		t.Fatalf("records without a usable name must be skipped, got %d", len(got))
	}
}
