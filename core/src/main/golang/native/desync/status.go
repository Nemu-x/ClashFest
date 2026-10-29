package desync

import (
	"encoding/json"
	"sort"

	"github.com/metacubex/mihomo/adapter/provider"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/tunnel"
)

// ProxyStatus is the runtime state of one desync proxy, taken from the
// engine's own MarshalJSON (the /proxies view, which Android does not serve).
type ProxyStatus struct {
	Name     string `json:"name"`
	Provider string `json:"provider,omitempty"`
	Alive    bool   `json:"alive"`
	// Desync is the outbound's "desync" object: strategy, tcp and udp
	// options as applied, udp.counters and tcp.fake.last-ttl.
	Desync json.RawMessage `json:"desync"`
	// DNSFallbacks counts profile-resolver retries (dns-fallback only).
	DNSFallbacks *uint64 `json:"dnsFallbacks,omitempty"`
	// UDPDiagnostics is the outbound's desyncUDP object (udp only).
	UDPDiagnostics json.RawMessage `json:"udpDiagnostics,omitempty"`
}

// Status lists the desync proxies of the running configuration.
type Status struct {
	Proxies []ProxyStatus `json:"proxies"`
}

// QueryStatus reads the loaded configuration: top-level proxies and those of
// proxy providers.
func QueryStatus() Status {
	return collectStatus(tunnel.Proxies(), tunnel.Providers())
}

func collectStatus(proxies map[string]C.Proxy, providers map[string]P.ProxyProvider) Status {
	out := Status{Proxies: []ProxyStatus{}}
	seen := map[[2]string]bool{}
	add := func(p C.Proxy, providerName string) {
		if p == nil || p.Type() != C.Desync {
			return
		}
		key := [2]string{providerName, p.Name()}
		if seen[key] {
			return
		}
		seen[key] = true
		raw, err := p.MarshalJSON()
		if err != nil {
			return
		}
		var fields struct {
			Alive          bool            `json:"alive"`
			Desync         json.RawMessage `json:"desync"`
			DNSFallbacks   *uint64         `json:"desyncDNSFallbacks"`
			UDPDiagnostics json.RawMessage `json:"desyncUDP"`
		}
		if json.Unmarshal(raw, &fields) != nil || len(fields.Desync) == 0 {
			return
		}
		out.Proxies = append(out.Proxies, ProxyStatus{
			Name:           p.Name(),
			Provider:       providerName,
			Alive:          fields.Alive,
			Desync:         fields.Desync,
			DNSFallbacks:   fields.DNSFallbacks,
			UDPDiagnostics: fields.UDPDiagnostics,
		})
	}
	for _, p := range proxies {
		add(p, "")
	}
	for name, pd := range providers {
		if name == provider.ReservedName || pd == nil {
			continue
		}
		for _, p := range pd.Proxies() {
			add(p, name)
		}
	}
	sort.Slice(out.Proxies, func(i, j int) bool {
		a, b := out.Proxies[i], out.Proxies[j]
		if a.Provider != b.Provider {
			return a.Provider < b.Provider
		}
		return a.Name < b.Name
	})
	return out
}
