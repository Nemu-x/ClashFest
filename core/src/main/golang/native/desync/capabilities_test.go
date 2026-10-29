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
	for _, k := range []string{"os", "fakeTcp", "fakeUdp", "ttlControl", "disorder", "oob", "ack", "autoTtl", "unsupported", "libraryVersion", "static", "note"} {
		if _, ok := m[k]; !ok {
			t.Errorf("JSON key %q missing: %s", k, b)
		}
	}
}
