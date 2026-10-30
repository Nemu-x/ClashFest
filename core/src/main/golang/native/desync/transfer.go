package desync

// Transfer check of automatic strategy selection (core patches 0024/0025).
//
// A route's fallback group picks its candidate with the engine's delay test, a
// HEAD over HTTP/1.1 that passes once the response headers arrive. Some
// networks let the TLS handshake through and then freeze the connection after
// the first tens of kilobytes (Discord on Beeline), so the delay test calls a
// strategy working that the service cannot use. When the status is read, the
// candidate a route uses is also fetched with the transfer test (GET, ALPN h2,
// body read); if it freezes, the other live candidates are tried in group order
// and the first that passes is pinned in the group. A route where none passes
// reports Working false: the service needs a VPN here. If the freeze is tied to
// the server's IP, no strategy gets around it, and the check says so instead
// of a false "working".
//
// Checks run in the background and are reused for transferRecheck, so reading
// the status never waits for the network.

import (
	"context"
	"net/url"
	"sync"
	"time"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub/route"
	"github.com/metacubex/mihomo/log"
)

const (
	transferRecheck  = autoInterval * time.Second
	transferTimeout  = 10 * time.Second
	transferMaxBytes = 64 << 10
	// transferMinBytes applies to a service's TransferURL, a page known to
	// be larger: a body that ends sooner did not show the transfer works.
	transferMinBytes = 32 << 10
	transferStall    = 2 * time.Second
)

// transferVerdict is the last check of a route. proxy is the candidate that
// passed, or the one the group used when none did.
type transferVerdict struct {
	proxy string
	ok    bool
	at    time.Time
}

var (
	transferVerdicts sync.Map // route name -> transferVerdict
	transferRunning  sync.Map // route name -> struct{}

	// transferTest and transferNow are replaced in tests.
	transferTest = route.ProxyTransfer
	transferNow  = time.Now
)

// transferTarget is the page the route's candidate must deliver: the
// service's TransferURL, else the group's test URL with no minimum size.
// Only https can be checked.
func transferTarget(routeName, testURL string) (*url.URL, int64, bool) {
	raw, minBytes := testURL, int64(0)
	for _, s := range services {
		if ProxyName(s.ID) == routeName && s.TransferURL != "" {
			raw, minBytes = s.TransferURL, transferMinBytes
		}
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" {
		return nil, 0, false
	}
	return u, minBytes, true
}

// applyTransfer folds the route's last transfer verdict into r and starts a
// new check when there is none for the group's current choice or it is old.
func applyTransfer(group C.Proxy, r *RouteStatus, proxies map[string]C.Proxy) {
	if !r.Working {
		return
	}
	target, minBytes, ok := transferTarget(r.Name, r.testURL)
	if !ok {
		return
	}
	v, have := transferVerdicts.Load(r.Name)
	verdict, _ := v.(transferVerdict)
	if have && !verdict.ok {
		r.Working = false
	}
	if have && transferNow().Sub(verdict.at) < transferRecheck && (!verdict.ok || verdict.proxy == r.Now) {
		return
	}
	candidates := make([]C.Proxy, 0, len(r.alive))
	for _, name := range append([]string{r.Now}, r.alive...) {
		if p := proxies[name]; p != nil && !containsProxy(candidates, p) {
			candidates = append(candidates, p)
		}
	}
	if _, running := transferRunning.LoadOrStore(r.Name, struct{}{}); running {
		return
	}
	go func() {
		defer transferRunning.Delete(r.Name)
		transferVerdicts.Store(r.Name, checkTransfer(group, r.Name, r.Now, candidates, target, minBytes))
	}()
}

func checkTransfer(group C.Proxy, routeName, now string, candidates []C.Proxy, target *url.URL, minBytes int64) transferVerdict {
	for _, p := range candidates {
		ctx, cancel := context.WithTimeout(context.Background(), transferTimeout)
		n, err := transferTest(ctx, p, target, minBytes, transferMaxBytes, transferStall)
		cancel()
		if err != nil {
			log.Infoln("[desync] %s: %s does not carry %s: %v", routeName, p.Name(), target, err)
			continue
		}
		if p.Name() != now {
			if s, ok := group.Adapter().(interface{ Set(string) error }); ok {
				if err := s.Set(p.Name()); err != nil {
					log.Warnln("[desync] %s: pin %s: %v", routeName, p.Name(), err)
				}
			}
		}
		log.Infoln("[desync] %s: %s carries %s (%d bytes)", routeName, p.Name(), target, n)
		return transferVerdict{proxy: p.Name(), ok: true, at: transferNow()}
	}
	log.Warnln("[desync] %s: no strategy carries %s, the service needs a VPN here", routeName, target)
	return transferVerdict{proxy: now, ok: false, at: transferNow()}
}

func containsProxy(list []C.Proxy, p C.Proxy) bool {
	for _, q := range list {
		if q.Name() == p.Name() {
			return true
		}
	}
	return false
}
