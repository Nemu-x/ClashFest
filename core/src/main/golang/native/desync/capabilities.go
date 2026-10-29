// Package desync is the bridge side of the `type: desync` outbound (core patch
// 0021, github.com/Nemu-x/desync): what the library can do on this device, its
// strategy catalog in the outbound's YAML form, the runtime state of the
// desync proxies of the loaded profile, and a generator for a profile that
// routes chosen services through desync and everything else DIRECT.
//
// It stays free of cfa/native/app, so `go test` runs on any workstation.
package desync

import (
	"runtime/debug"

	lib "github.com/Nemu-x/desync"
)

// LibraryModule is the module path of the desync library; its version is the
// one go.mod of cfa and foss pins (see docs/core-patches.md).
const LibraryModule = "github.com/Nemu-x/desync"

// capabilitiesNote says how to read Capabilities.
const capabilitiesNote = "Capabilities are static in this library version: they describe what the " +
	"library implements on this OS, not whether a method works on the current network or socket."

// Capabilities is desync.PlatformCapabilities in a stable JSON shape.
type Capabilities struct {
	OS         string `json:"os"`
	FakeTCP    bool   `json:"fakeTcp"`
	FakeUDP    bool   `json:"fakeUdp"`
	TTLControl bool   `json:"ttlControl"`
	Disorder   struct {
		Supported bool   `json:"supported"`
		Mechanism string `json:"mechanism"`
		Observed  bool   `json:"observed"`
		Reliable  bool   `json:"reliable"`
	} `json:"disorder"`
	OOB bool `json:"oob"`
	Ack struct {
		Mechanism string `json:"mechanism"`
		Sent      bool   `json:"sent"`
		Acked     bool   `json:"acked"`
		Reliable  bool   `json:"reliable"`
	} `json:"ack"`
	AutoTTL struct {
		Mechanism string `json:"mechanism"`
		IPv4      bool   `json:"ipv4"`
		IPv6      bool   `json:"ipv6"`
	} `json:"autoTtl"`
	// Unsupported maps a method ("fake-tcp", "fake-udp", "disorder", "oob",
	// "auto-ttl-ipv4", "auto-ttl-ipv6") to the library's reason.
	Unsupported map[string]string `json:"unsupported"`
	// LibraryVersion is the linked library version, empty when the binary
	// carries no build info.
	LibraryVersion string `json:"libraryVersion"`
	Static         bool   `json:"static"`
	Note           string `json:"note"`
}

func capabilitiesOf(c lib.Capabilities) Capabilities {
	var out Capabilities
	out.OS, out.FakeTCP, out.FakeUDP, out.TTLControl, out.OOB = c.OS, c.FakeTCP, c.FakeUDP, c.TTLControl, c.OOB
	out.Disorder.Supported, out.Disorder.Mechanism = c.Disorder.Supported, c.Disorder.Mechanism
	out.Disorder.Observed, out.Disorder.Reliable = c.Disorder.Observed, c.Disorder.Reliable
	out.Ack.Mechanism, out.Ack.Sent, out.Ack.Acked, out.Ack.Reliable = c.Ack.Mechanism, c.Ack.Sent, c.Ack.Acked, c.Ack.Reliable
	out.AutoTTL.Mechanism, out.AutoTTL.IPv4, out.AutoTTL.IPv6 = c.AutoTTL.Mechanism, c.AutoTTL.IPv4, c.AutoTTL.IPv6
	out.Unsupported = map[string]string{}
	for k, v := range c.Unsupported {
		out.Unsupported[k] = v
	}
	out.LibraryVersion = LibraryVersion()
	out.Static = true
	out.Note = capabilitiesNote
	return out
}

// PlatformCapabilities reports what the linked library implements here.
func PlatformCapabilities() Capabilities {
	return capabilitiesOf(lib.PlatformCapabilities())
}

// LibraryVersion is the version of LibraryModule in the build info.
func LibraryVersion() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return ""
	}
	for _, dep := range info.Deps {
		if dep.Path == LibraryModule {
			if dep.Replace != nil {
				return dep.Replace.Version
			}
			return dep.Version
		}
	}
	return ""
}
