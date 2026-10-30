// Package desync is the bridge side of the `type: desync` outbound (core patch
// 0021, github.com/Nemu-x/desync): what the library can do on this device, its
// strategy catalog in the outbound's YAML form, the runtime state of the
// desync proxies of the loaded profile, and a generator for a profile that
// routes chosen services through desync and everything else DIRECT.
//
// It stays free of cfa/native/app, so `go test` runs on any workstation.
package desync

import (
	"net"
	"runtime/debug"
	"sync"
	"time"

	lib "github.com/Nemu-x/desync"
)

// LibraryModule is the module path of the desync library; its version is the
// one go.mod of cfa and foss pins (see docs/core-patches.md).
const LibraryModule = "github.com/Nemu-x/desync"

// capabilitiesNote says how to read Capabilities.
const capabilitiesNote = "Capabilities are static in this library version: they describe what the " +
	"library implements on this OS, not whether a method works on the current network or socket. " +
	"Methods in conditional are decided per socket; probed is a loopback socket of this process, " +
	"which answers for every connection of it (the restriction is the app's SELinux domain and the kernel)."

// DisorderCapability is desync.DisorderCapability in JSON.
type DisorderCapability struct {
	Supported bool   `json:"supported"`
	Mechanism string `json:"mechanism"`
	Observed  bool   `json:"observed"`
	Reliable  bool   `json:"reliable"`
}

// AckCapability is desync.AckCapability in JSON.
type AckCapability struct {
	Mechanism string `json:"mechanism"`
	Sent      bool   `json:"sent"`
	Acked     bool   `json:"acked"`
	Reliable  bool   `json:"reliable"`
}

// Probe is desync.ProbeCapabilities of a loopback connection: the socket
// dependent methods as a socket of this process can honor them. Error is set,
// and the rest is zero, when the probe could not run.
type Probe struct {
	FakeTCP     bool               `json:"fakeTcp"`
	Disorder    DisorderCapability `json:"disorder"`
	Ack         AckCapability      `json:"ack"`
	Unsupported map[string]string  `json:"unsupported"`
	Error       string             `json:"error"`
}

// Capabilities is desync.PlatformCapabilities in a stable JSON shape, with a
// loopback probe of this process (desync.ProbeCapabilities).
type Capabilities struct {
	OS         string             `json:"os"`
	FakeTCP    bool               `json:"fakeTcp"`
	FakeUDP    bool               `json:"fakeUdp"`
	TTLControl bool               `json:"ttlControl"`
	Disorder   DisorderCapability `json:"disorder"`
	OOB        bool               `json:"oob"`
	Ack        AckCapability      `json:"ack"`
	AutoTTL    struct {
		Mechanism string `json:"mechanism"`
		IPv4      bool   `json:"ipv4"`
		IPv6      bool   `json:"ipv6"`
	} `json:"autoTtl"`
	// Unsupported maps a method ("fake-tcp", "fake-udp", "disorder", "oob",
	// "auto-ttl-ipv4", "auto-ttl-ipv6") to the library's reason.
	Unsupported map[string]string `json:"unsupported"`
	// Conditional maps the methods this build implements but decides per
	// socket ("split-wait-sent", "fake-tcp", "disorder"; Android only) to the
	// reason. They are also in Unsupported and reported false above; a
	// desync outbound accepts them, and a socket that cannot honor them fails
	// its connection before anything is sent.
	Conditional map[string]string `json:"conditional"`
	// Probed resolves Conditional on a loopback connection of this process.
	Probed *Probe `json:"probed"`
	// LibraryVersion is the linked library version, empty when the binary
	// carries no build info.
	LibraryVersion string `json:"libraryVersion"`
	Static         bool   `json:"static"`
	Note           string `json:"note"`
}

func capabilitiesOf(c lib.Capabilities) Capabilities {
	var out Capabilities
	out.OS, out.FakeTCP, out.FakeUDP, out.TTLControl, out.OOB = c.OS, c.FakeTCP, c.FakeUDP, c.TTLControl, c.OOB
	out.Disorder, out.Ack = disorderOf(c.Disorder), ackOf(c.Ack)
	out.AutoTTL.Mechanism, out.AutoTTL.IPv4, out.AutoTTL.IPv6 = c.AutoTTL.Mechanism, c.AutoTTL.IPv4, c.AutoTTL.IPv6
	out.Unsupported = copyReasons(c.Unsupported)
	out.Conditional = copyReasons(c.Conditional)
	out.LibraryVersion = LibraryVersion()
	out.Static = true
	out.Note = capabilitiesNote
	return out
}

func disorderOf(d lib.DisorderCapability) DisorderCapability {
	return DisorderCapability{Supported: d.Supported, Mechanism: d.Mechanism, Observed: d.Observed, Reliable: d.Reliable}
}

func ackOf(a lib.AckCapability) AckCapability {
	return AckCapability{Mechanism: a.Mechanism, Sent: a.Sent, Acked: a.Acked, Reliable: a.Reliable}
}

func copyReasons(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

func probeOf(c lib.Capabilities, err error) *Probe {
	if err != nil {
		return &Probe{Unsupported: map[string]string{}, Error: err.Error()}
	}
	return &Probe{FakeTCP: c.FakeTCP, Disorder: disorderOf(c.Disorder), Ack: ackOf(c.Ack), Unsupported: copyReasons(c.Unsupported)}
}

// PlatformCapabilities reports what the linked library implements here, and
// what a loopback socket of this process can do.
func PlatformCapabilities() Capabilities {
	out := capabilitiesOf(lib.PlatformCapabilities())
	out.Probed = probeOf(probedCapabilities())
	return out
}

// probedCapabilities probes a loopback connection once per process: what a
// socket can observe depends on the process's SELinux domain and the kernel,
// not on the destination.
var probedCapabilities = sync.OnceValues(func() (lib.Capabilities, error) {
	return probeLoopback(5 * time.Second)
})

func probeLoopback(timeout time.Duration) (lib.Capabilities, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return lib.PlatformCapabilities(), err
	}
	defer ln.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, err := ln.Accept()
		if err != nil {
			accepted <- nil
			return
		}
		accepted <- c
	}()
	c, err := net.DialTimeout("tcp", ln.Addr().String(), timeout)
	if err != nil {
		return lib.PlatformCapabilities(), err
	}
	defer c.Close()
	if peer := <-accepted; peer != nil {
		defer peer.Close()
	}
	return lib.ProbeCapabilities(c)
}

// effectiveCapabilities is what a desync connection of this process can use:
// the probe where it ran, otherwise the platform report.
func effectiveCapabilities() lib.Capabilities {
	if c, err := probedCapabilities(); err == nil {
		return c
	}
	return lib.PlatformCapabilities()
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
