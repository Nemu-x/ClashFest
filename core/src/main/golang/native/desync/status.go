package desync

import (
	"encoding/json"
	"sort"
	"strings"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
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

// RouteStatus is a fallback group whose members are all desync proxies, as
// automatic strategy selection generates one per route.
type RouteStatus struct {
	Name string `json:"name"`
	// Now is the candidate the group uses.
	Now string `json:"now"`
	// Checked: every candidate has been checked with the group's URL.
	Checked bool `json:"checked"`
	// Working: some candidate passed its last check, and the transfer check
	// (transfer.go) found none freezing. Checked && !Working means no
	// strategy gets through: the service needs a VPN here.
	Working bool `json:"working"`
	// Overlay: a route of the desync overlay on a subscription, where a
	// route that is not Working goes the subscription's way (through the
	// VPN) rather than nowhere.
	Overlay bool `json:"overlay,omitempty"`

	testURL string   // the group's delay-test URL
	alive   []string // candidates that passed it, in group order
}

// overlayRouteStatus reports an overlay group (overlay.go): Checked once its
// first check ran, Working while it uses a desync candidate (otherwise the
// service goes the subscription's way).
func overlayRouteStatus(p C.Proxy) (RouteStatus, bool) {
	if p == nil || p.Type() != C.Selector || !strings.HasPrefix(p.Name(), "desync-") {
		return RouteStatus{}, false
	}
	g, ok := p.Adapter().(*outboundgroup.Selector)
	if !ok {
		return RouteStatus{}, false
	}
	desync := false
	for _, m := range g.Proxies() {
		if m.Type() == C.Desync && strings.HasPrefix(m.Name(), p.Name()+"~") {
			desync = true
			break
		}
	}
	if !desync {
		return RouteStatus{}, false
	}
	r := RouteStatus{Name: p.Name(), Now: g.Now(), Overlay: true}
	if v, ok := overlayVerdicts.Load(p.Name()); ok {
		r.Checked, r.Working = true, v.(overlayVerdict).desync
	}
	return r, true
}

// Status lists the desync proxies of the running configuration.
type Status struct {
	Proxies []ProxyStatus `json:"proxies"`
	Routes  []RouteStatus `json:"routes"`
}

// QueryStatus reads the loaded configuration: top-level proxies and those of
// proxy providers.
func QueryStatus() Status {
	return collectStatus(tunnel.Proxies(), tunnel.Providers())
}

func collectStatus(proxies map[string]C.Proxy, providers map[string]P.ProxyProvider) Status {
	out := Status{Proxies: []ProxyStatus{}, Routes: []RouteStatus{}}
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
	for _, p := range proxies {
		if r, ok := routeOf(p, proxies); ok {
			applyTransfer(p, &r, proxies)
			out.Routes = append(out.Routes, r)
		} else if r, ok := overlayRouteStatus(p); ok {
			out.Routes = append(out.Routes, r)
		}
	}
	sort.Slice(out.Routes, func(i, j int) bool { return out.Routes[i].Name < out.Routes[j].Name })
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

func routeOf(p C.Proxy, proxies map[string]C.Proxy) (RouteStatus, bool) {
	if p == nil || p.Type() != C.Fallback {
		return RouteStatus{}, false
	}
	raw, err := p.MarshalJSON()
	if err != nil {
		return RouteStatus{}, false
	}
	var group struct {
		Now     string   `json:"now"`
		All     []string `json:"all"`
		TestURL string   `json:"testUrl"`
	}
	if json.Unmarshal(raw, &group) != nil || len(group.All) == 0 {
		return RouteStatus{}, false
	}
	r := RouteStatus{Name: p.Name(), Now: group.Now, Checked: true, testURL: group.TestURL}
	for _, name := range group.All {
		m := proxies[name]
		if m == nil || m.Type() != C.Desync {
			return RouteStatus{}, false
		}
		if len(m.ExtraDelayHistories()[group.TestURL].History) == 0 {
			r.Checked = false
		} else if m.AliveForTestUrl(group.TestURL) {
			r.Working = true
			r.alive = append(r.alive, name)
		}
	}
	return r, true
}
