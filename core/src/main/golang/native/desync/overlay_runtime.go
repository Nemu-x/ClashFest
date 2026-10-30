package desync

// Run-time side of the desync overlay (overlay.go): which member each overlay
// group uses.
//
// When a config loads, every overlay group first takes the outbound the
// subscription's own rules pick for the service (the engine's rules, matched
// for the service's host with the overlay's rules skipped), so traffic flows
// exactly as without the overlay. Then, in the background, the candidates are
// fetched with the transfer test in order; the first that carries the service
// becomes the group's choice. A check every transferRecheck repeats this, so
// a strategy that stops working falls back to the subscription and a working
// one is taken again. Nothing waits on the network: loading never blocks.

import (
	"context"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

// overlayRoute is one overlay group of the running config.
type overlayRoute struct {
	group      C.Proxy
	name       string
	candidates []C.Proxy
	// fallback is what the subscription routes the service to.
	fallback string
	target   *url.URL
	minBytes int64
}

// overlayVerdict is the last decision for a route.
type overlayVerdict struct {
	// desync: a candidate carries the service and the group uses it.
	desync bool
	at     time.Time
}

var (
	overlayMu       sync.Mutex
	overlayCancel   context.CancelFunc
	overlayVerdicts sync.Map // route name -> overlayVerdict

	// overlayRecheck is replaced in tests.
	overlayRecheck = transferRecheck
)

// StartOverlayRouting sets up the overlay groups of the config the engine
// just loaded and starts their checks; it replaces the checks of the previous
// config. A config without overlay groups only stops them.
func StartOverlayRouting() {
	startOverlayRouting(tunnel.Proxies(), tunnel.Rules())
}

func startOverlayRouting(proxies map[string]C.Proxy, rules []C.Rule) {
	StopOverlayRouting()
	routes := overlayRoutes(proxies, rules)
	if len(routes) == 0 {
		return
	}
	ctx, cancel := context.WithCancel(context.Background())
	overlayMu.Lock()
	overlayCancel = cancel
	overlayMu.Unlock()
	for _, r := range routes {
		selectMember(r, r.fallback)
		go r.run(ctx)
	}
}

// StopOverlayRouting stops the checks; called before another config loads
// and when the core stops.
func StopOverlayRouting() {
	overlayMu.Lock()
	if overlayCancel != nil {
		overlayCancel()
		overlayCancel = nil
	}
	overlayMu.Unlock()
	overlayVerdicts.Range(func(k, _ any) bool { overlayVerdicts.Delete(k); return true })
}

// overlayRoutes finds the overlay groups: hidden select groups named desync-*
// whose candidate members are desync proxies named after the group.
func overlayRoutes(proxies map[string]C.Proxy, rules []C.Rule) []*overlayRoute {
	groups := map[string]bool{}
	var routes []*overlayRoute
	for name, p := range proxies {
		if !strings.HasPrefix(name, "desync-") || p.Type() != C.Selector {
			continue
		}
		g, ok := p.Adapter().(*outboundgroup.Selector)
		if !ok {
			continue
		}
		r := &overlayRoute{group: p, name: name}
		for _, m := range g.Proxies() {
			if m.Type() == C.Desync && strings.HasPrefix(m.Name(), name+"~") {
				r.candidates = append(r.candidates, m)
			}
		}
		if len(r.candidates) == 0 {
			continue
		}
		groups[name] = true
		routes = append(routes, r)
	}
	for _, r := range routes {
		host := routeHost(r.name, rules)
		testURL := "https://" + host + "/"
		if s, ok := serviceOfRoute(r.name); ok {
			testURL = s.TestURL
		}
		r.target, r.minBytes, _ = transferTarget(r.name, testURL)
		r.fallback = subscriptionTarget(host, groups, rules, proxies)
	}
	return routes
}

func serviceOfRoute(routeName string) (Service, bool) {
	for _, s := range services {
		if ProxyName(s.ID) == routeName {
			return s, true
		}
	}
	return Service{}, false
}

// routeHost is the host the route is checked for: the service's test URL,
// else the first domain the route's rules name.
func routeHost(routeName string, rules []C.Rule) string {
	if s, ok := serviceOfRoute(routeName); ok {
		if u, err := url.Parse(s.TestURL); err == nil && u.Hostname() != "" {
			return u.Hostname()
		}
	}
	for _, r := range rules {
		if r.Adapter() == routeName && r.RuleType() == C.DomainSuffix {
			return r.Payload()
		}
	}
	return ""
}

// subscriptionTarget is the outbound the config's rules pick for a TLS
// connection to host, the overlay's own rules skipped. Rules that need the
// destination IP or the process do not match a bare host; PASS goes on to the
// next rule as in the engine. Without a match the engine uses DIRECT.
func subscriptionTarget(host string, overlay map[string]bool, rules []C.Rule, proxies map[string]C.Proxy) string {
	metadata := &C.Metadata{NetWork: C.TCP, Host: host, DstPort: 443}
	helper := C.RuleMatchHelper{ResolveIP: func() {}, FindProcess: func() {}}
	for _, rule := range rules {
		if overlay[rule.Adapter()] {
			continue
		}
		if w, ok := rule.(C.RuleWrapper); ok && w.IsDisabled() {
			continue
		}
		matched, name := rule.Match(metadata, helper)
		if !matched {
			continue
		}
		p := proxies[name]
		if p == nil || p.Type() == C.Pass {
			continue
		}
		return name
	}
	return "DIRECT"
}

func selectMember(r *overlayRoute, name string) {
	s, ok := r.group.Adapter().(*outboundgroup.Selector)
	if !ok {
		return
	}
	if err := s.Set(name); err != nil {
		log.Warnln("[desync] %s: select %s: %v", r.name, name, err)
	}
}

func (r *overlayRoute) run(ctx context.Context) {
	for {
		r.check(ctx)
		select {
		case <-ctx.Done():
			return
		case <-time.After(overlayRecheck):
		}
	}
}

// check picks the first candidate that carries the service, else the
// subscription's outbound.
func (r *overlayRoute) check(ctx context.Context) {
	if r.target == nil {
		return
	}
	choice, desync := r.fallback, false
	for _, p := range r.candidates {
		tctx, cancel := context.WithTimeout(ctx, transferTimeout)
		n, err := transferTest(tctx, p, r.target, r.minBytes, transferMaxBytes, transferStall)
		cancel()
		if ctx.Err() != nil {
			return
		}
		if err == nil {
			log.Infoln("[desync] %s: %s carries %s (%d bytes)", r.name, p.Name(), r.target, n)
			choice, desync = p.Name(), true
			break
		}
		log.Infoln("[desync] %s: %s does not carry %s: %v", r.name, p.Name(), r.target, err)
	}
	if !desync {
		log.Infoln("[desync] %s: no strategy carries %s, staying on %s", r.name, r.target, r.fallback)
	}
	selectMember(r, choice)
	overlayVerdicts.Store(r.name, overlayVerdict{desync: desync, at: transferNow()})
}
