package tunnel

import (
	"context"
	"io"
	"reflect"
	"strings"
	"sync"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/common/utils"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
	"golang.org/x/sync/errgroup"
)

// defaultHealthCheckURL is mihomo's stock generate_204 endpoint. Used as a
// fallback when a provider has no configured health-check URL (typical for
// compatible providers built from inline `proxies:` lists).
const defaultHealthCheckURL = "http://www.gstatic.com/generate_204"

// perProxyHealthCheckTimeout caps each individual proxy URLTest when the
// provider's own health-check timeout cannot be extracted. Mirrors the
// mihomo default so behaviour matches a vanilla provider.HealthCheck()
// for subscriptions that don't override `health-check.timeout`.
const perProxyHealthCheckTimeout = 5 * time.Second

// perGroupConcurrencyLimit mirrors the cap mihomo's provider.HealthCheck
// imposes via errgroup.SetLimit(10) in adapter/provider/healthcheck.go.
// Without it, a group with 100+ proxies fires 100+ simultaneous URLTest
// goroutines — TLS handshake storming + local socket pressure inflates
// every reported delay 2-3x compared to the vanilla path. Matching the
// engine's own cap keeps per-proxy push results comparable.
const perGroupConcurrencyLimit = 10

// extractHealthCheckSettings pulls the configured health-check timeout off
// a ProxyProvider so per-proxy URLTest can honour the subscription's
// `health-check.timeout` instead of hard-coding 5s. mihomo only exposes
// HealthCheckURL() through the ProxyProvider interface — timeout lives on
// the private baseProvider.healthCheck field. Reflection is the least
// invasive workaround until we fork mihomo and add a public getter
// (recorded in project memory).
//
// expectedStatus extraction is intentionally NOT attempted via reflection:
// reflect.Value.Interface() panics unconditionally on values obtained from
// unexported fields, regardless of IsNil/IsValid guards, so any subscription
// with a non-default `health-check.expected-status` would crash the Go
// runtime through SIGABRT — observed in the wild on user reports. Until the
// submodule getter lands we always pass nil to URLTest, which matches
// mihomo's own default (accept any 2xx) for subscriptions that do not set
// the field. Custom expected-status ranges silently degrade to that default
// instead of panicking — acceptable; the affected configurations are rare.
//
// If the field layout shifts in a future mihomo bump this falls back to
// timeout = perProxyHealthCheckTimeout (the mihomo default), keeping the
// call path safe.
func extractHealthCheckSettings(prov provider.ProxyProvider) (time.Duration, utils.IntRanges[uint16]) {
	timeout := perProxyHealthCheckTimeout
	var expectedStatus utils.IntRanges[uint16]

	pv := reflect.ValueOf(prov)
	if pv.Kind() == reflect.Ptr {
		pv = pv.Elem()
	}
	if !pv.IsValid() || pv.Kind() != reflect.Struct {
		return timeout, expectedStatus
	}

	hcField := pv.FieldByName("healthCheck")
	if !hcField.IsValid() || hcField.Kind() != reflect.Ptr || hcField.IsNil() {
		return timeout, expectedStatus
	}
	hc := hcField.Elem()
	if !hc.IsValid() || hc.Kind() != reflect.Struct {
		return timeout, expectedStatus
	}

	if t := hc.FieldByName("timeout"); t.IsValid() && t.Kind() == reflect.Uint {
		if tval := t.Uint(); tval > 0 {
			timeout = time.Duration(tval) * time.Millisecond
		}
	}
	return timeout, expectedStatus
}

func HealthCheck(name string) {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Request health check for `%s`: not found", name)

		return
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Request health check for `%s`: invalid type %s", name, p.Type().String())

		return
	}

	wg := &sync.WaitGroup{}

	for _, pr := range g.Providers() {
		wg.Add(1)

		go func(provider provider.ProxyProvider) {
			provider.HealthCheck()

			wg.Done()
		}(pr)
	}

	wg.Wait()
}

func HealthCheckAll() {
	for _, g := range QueryProxyGroupNames(false) {
		go func(group string) {
			HealthCheck(group)
		}(g)
	}
}

// selfRoutingGroup reports whether a group picks its own outbound and therefore
// gains something from a forced probe. `select` groups don't: the user pinned a
// node by hand and the group will keep using it no matter what the probe says,
// so testing them only burns radio for delay numbers nobody is looking at (the
// screen is usually off when this runs). Relay is a fixed chain, same story.
func selfRoutingGroup(t C.AdapterType) bool {
	switch t {
	case C.URLTest, C.Fallback, C.LoadBalance:
		return true
	default:
		return false
	}
}

// HealthCheckAutoGroups probes exactly what a default-network switch can actually
// re-route: the url-test / fallback / load-balance groups, each backing provider
// hit exactly once.
//
// This replaces "walk every group name and call HealthCheck on it", which was
// doubly wasteful. First, it included `select` and relay groups, which cannot
// re-route (see selfRoutingGroup). Second, and worse, HealthCheck(group) probes
// every provider *of that group*, so a provider shared by N groups — the normal
// shape of a subscription where several groups `use:` the same provider — was
// url-tested N times, meaning every node in it was dialed N times per switch.
// mihomo's own singledo only dedups concurrent checks *within* one provider, so
// it could not save us here.
//
// Deduplication is by provider name, which is safe: mihomo keeps providers in a
// map keyed by name (tunnel.Providers()), so names are unique by construction.
//
// Note what this does NOT dedup: two groups that each inline their own `proxies:`
// list get two distinct compatible providers, and a node listed in both is still
// probed twice. Deduplicating down to individual nodes would mean running URLTest
// ourselves instead of provider.HealthCheck(), which would silently drop the extra
// health-check URLs that groups register on a shared provider (HealthCheck.extra)
// — a correctness loss for a smaller win. Left as is deliberately.
//
// Returns (groups probed, providers probed) for logging.
func HealthCheckAutoGroups() (int, int) {
	seen := make(map[string]struct{})
	targets := make([]provider.ProxyProvider, 0, 4)
	groups := 0

	proxies := tunnel.Proxies()

	for _, name := range QueryAllProxyGroupNamesIncludingHidden() {
		p := proxies[name]
		if p == nil || !selfRoutingGroup(p.Type()) {
			continue
		}

		g, ok := p.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		groups++

		for _, prov := range g.Providers() {
			if _, dup := seen[prov.Name()]; dup {
				continue
			}
			seen[prov.Name()] = struct{}{}
			targets = append(targets, prov)
		}
	}

	wg := &sync.WaitGroup{}

	for _, prov := range targets {
		wg.Add(1)

		go func(pr provider.ProxyProvider) {
			defer wg.Done()

			pr.HealthCheck()
		}(prov)
	}

	wg.Wait()

	log.Infoln("[APP] Network switch health check: %d self-routing groups -> %d providers", groups, len(targets))

	return groups, len(targets)
}

// CancelHealthChecks cancels the health-check context of every live proxy provider (subscription
// providers and the synthetic per-group compatible providers alike). A plain config swap only
// replaces the provider maps (tunnel.UpdateProxies) and never closes the outgoing ones, so their
// url-test goroutines keep dialing until each hits its full per-proxy timeout. On VPN teardown that
// stalls shutdown: the tunnel is logically down in ~90ms, but an in-flight batch against dead nodes
// keeps the Android VpnService — and the system VPN key — alive for the 10-30s it takes the last
// dial to give up. Cancelling the providers' health-check context aborts those dials at once.
//
// Called from reset() before LoadDefault swaps the config out; the providers are being discarded
// anyway, and closing only fires ctxCancel (+ stops a subscription fetcher) — nothing blocking.
func CancelHealthChecks() {
	for _, p := range tunnel.Providers() {
		if closer, ok := p.(io.Closer); ok {
			_ = closer.Close()
		}
	}
}

// HealthCheckWithCallback runs URLTest against every proxy of every provider
// in the named group in parallel and pushes each result to onDelay the moment
// that proxy's test resolves. Mirrors what provider.HealthCheck does
// internally (URLTest with the provider's own health-check URL, no filter)
// but reports per proxy instead of a single batch return — UI can patch a
// row as soon as it has data instead of polling queryProxyGroup on a timer.
//
// Returns ("", nil) when every proxy reported; ("group not found" / "invalid
// type", nil) on the obvious early-outs. onDelay is called from a goroutine,
// so the caller is responsible for any cross-goroutine synchronisation.
//
// errMsg is empty on success, otherwise carries the proxy's URLTest error
// reason; delayMs is meaningful only when errMsg == "".
// testURL, when non-empty, replaces the provider's configured health-check URL for THIS run only.
// It is a one-shot measurement target the user typed, not a setting: nothing is persisted engine-side
// and the automatic url-test/fallback timers keep using the subscription's own URL, because those are
// driven by mihomo itself from the group config.
//
// Note what changes with a custom target. The default is a `generate_204` endpoint, which answers with
// an empty body, so the number is close to a round trip. An arbitrary site returns a real page and may
// negotiate TLS, so its figure is legitimately higher — it is still latency, never throughput.
func HealthCheckWithCallback(
	name string,
	testURL string,
	onDelay func(proxyName string, delayMs int, errMsg string),
) string {
	p := tunnel.Proxies()[name]
	if p == nil {
		return "group not found"
	}
	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		return "invalid group type"
	}

	// Bound concurrent URLTests across the whole group, not per provider —
	// a kaso-style config can stack several providers behind one group and
	// each one's leaf count adds to the outgoing socket pressure.
	override := strings.TrimSpace(testURL)

	eg := new(errgroup.Group)
	eg.SetLimit(perGroupConcurrencyLimit)
	for _, prov := range g.Providers() {
		url := override
		if url == "" {
			url = prov.HealthCheckURL()
		}
		if url == "" {
			url = defaultHealthCheckURL
		}
		// Honour provider-level health-check.timeout / expected-status from
		// the subscription. Without this a custom `health-check.timeout: 10`
		// would still cap at 5s here, producing false timeout entries that
		// disagree with what the engine's own provider.HealthCheck() would
		// report. See extractHealthCheckSettings for the reflection caveat.
		timeout, expectedStatus := extractHealthCheckSettings(prov)
		for _, target := range prov.Proxies() {
			target := target
			url := url
			timeout := timeout
			expectedStatus := expectedStatus
			eg.Go(func() error {
				ctx, cancel := context.WithTimeout(context.Background(), timeout)
				defer cancel()
				delay, err := target.URLTest(ctx, url, expectedStatus)
				if err != nil {
					onDelay(target.Name(), 0, err.Error())
					return nil
				}
				onDelay(target.Name(), int(delay), "")
				return nil
			})
		}
	}
	_ = eg.Wait()
	return ""
}
