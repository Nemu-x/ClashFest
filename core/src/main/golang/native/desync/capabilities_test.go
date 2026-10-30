package desync

import (
	"encoding/json"
	"runtime"
	"testing"

	lib "github.com/Nemu-x/desync"
)

func TestPlatformCapabilities(t *testing.T) {
	want := lib.PlatformCapabilities()
	got := PlatformCapabilities()
	if got.OS != runtime.GOOS || got.FakeTCP != want.FakeTCP || got.FakeUDP != want.FakeUDP ||
		got.Disorder.Supported != want.Disorder.Supported || got.OOB != want.OOB ||
		got.Ack.Sent != want.Ack.Sent || got.Ack.Acked != want.Ack.Acked ||
		got.AutoTTL.IPv4 != want.AutoTTL.IPv4 || got.AutoTTL.IPv6 != want.AutoTTL.IPv6 {
		t.Fatalf("capabilities differ from the library: %+v vs %+v", got, want)
	}
	if len(got.Unsupported) != len(want.Unsupported) {
		t.Fatalf("unsupported = %v, want %v", got.Unsupported, want.Unsupported)
	}
	if !got.Static || got.Note == "" {
		t.Fatal("static note missing")
	}
	if got.LibraryVersion == "" {
		t.Log("no build info for", LibraryModule)
	}

	b, err := json.Marshal(got)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"os", "fakeTcp", "fakeUdp", "ttlControl", "disorder", "oob", "ack", "autoTtl", "unsupported", "conditional", "probed", "libraryVersion", "static", "note"} {
		if _, ok := m[k]; !ok {
			t.Errorf("JSON key %q missing: %s", k, b)
		}
	}
	if len(got.Conditional) != len(want.Conditional) {
		t.Fatalf("conditional = %v, want %v", got.Conditional, want.Conditional)
	}

	// The loopback probe runs on every OS this test runs on; it resolves
	// Conditional, so no method is left conditional and, off Android, it
	// agrees with the platform report.
	p := got.Probed
	if p == nil || p.Error != "" {
		t.Fatalf("probe failed: %+v", p)
	}
	if len(want.Conditional) == 0 && (p.FakeTCP != want.FakeTCP || p.Disorder.Supported != want.Disorder.Supported ||
		p.Ack.Sent != want.Ack.Sent || p.Ack.Acked != want.Ack.Acked) {
		t.Fatalf("probe %+v differs from the platform %+v", p, want)
	}
	pm, ok := m["probed"].(map[string]any)
	if !ok {
		t.Fatalf("probed is not an object: %s", b)
	}
	for _, k := range []string{"fakeTcp", "disorder", "ack", "unsupported", "error"} {
		if _, ok := pm[k]; !ok {
			t.Errorf("probed JSON key %q missing: %s", k, b)
		}
	}
}

// What the library reports on Android (v0.2.1): the methods that need a
// socket's unsent bytes are reported false, listed in Unsupported and in
// Conditional. The bridge passes Conditional through, and a probe that found
// TCP_INFO's unsent count reports them available.
func TestAndroidConditionalCapabilities(t *testing.T) {
	const reason = "Android does not let apps read SIOCOUTQNSD; decided per socket"
	android := lib.Capabilities{
		OS: "android", FakeUDP: true, TTLControl: true, OOB: true,
		Ack: lib.AckCapability{Mechanism: "SIOCOUTQ", Acked: true, Reliable: true},
		Unsupported: map[string]string{"auto-ttl-ipv4": "no TTL",
			"split-wait-sent": reason, "fake-tcp": reason, "disorder": reason},
		Conditional: map[string]string{"split-wait-sent": reason, "fake-tcp": reason, "disorder": reason},
	}
	got := capabilitiesOf(android)
	if got.FakeTCP || got.Disorder.Supported || got.Ack.Sent {
		t.Fatalf("conditional methods reported as guaranteed: %+v", got)
	}
	for _, k := range []string{"split-wait-sent", "fake-tcp", "disorder"} {
		if got.Conditional[k] != reason || got.Unsupported[k] != reason {
			t.Errorf("%s: conditional %q, unsupported %q", k, got.Conditional[k], got.Unsupported[k])
		}
	}
	android.Conditional["x"] = "copied, not shared"
	if _, shared := got.Conditional["x"]; shared {
		t.Fatal("Conditional shares the library's map")
	}

	probed := android
	probed.FakeTCP, probed.Ack.Sent, probed.Ack.Mechanism = true, true, "SIOCOUTQ/TCP_INFO"
	probed.Disorder = lib.DisorderCapability{Supported: true, Mechanism: "TCP_INFO", Observed: true, Reliable: true}
	probed.Unsupported, probed.Conditional = map[string]string{"auto-ttl-ipv4": "no TTL"}, nil
	p := probeOf(probed, nil)
	if !p.FakeTCP || !p.Disorder.Supported || p.Disorder.Mechanism != "TCP_INFO" || !p.Ack.Sent || p.Error != "" {
		t.Fatalf("probe = %+v", p)
	}
	if _, ok := p.Unsupported["fake-tcp"]; ok {
		t.Fatalf("probe still lists fake-tcp: %v", p.Unsupported)
	}
	if e := probeOf(android, lib.ErrSplitWaitRequiresTCP); e.Error == "" || e.FakeTCP || e.Unsupported == nil {
		t.Fatalf("failed probe = %+v", e)
	}

	// The catalog follows the probe: unresolved, the fake, disorder and
	// split-wait sent presets are skipped; resolved, they are offered.
	unresolved, resolved := catalogFor(android), catalogFor(probed)
	needsSent := func(o Options) bool { return o.Fake != nil || o.Disorder || o.SplitWait == "sent" }
	for _, p := range unresolved.TCP {
		if needsSent(p.Options) {
			t.Errorf("unresolved catalog offers %s", p.Name)
		}
	}
	offered := false
	for _, p := range resolved.TCP {
		offered = offered || needsSent(p.Options)
	}
	if !offered {
		t.Error("resolved catalog offers no fake, disorder or split-wait sent preset")
	}
}
