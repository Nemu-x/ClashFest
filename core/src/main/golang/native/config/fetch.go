package config

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	U "net/url"
	"os"
	P "path"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"cfa/native/app"

	"github.com/metacubex/mihomo/component/dialer"
	"cfa/native/config/fetchheaders"

	clashHttp "github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

type Status struct {
	Action      string   `json:"action"`
	Args        []string `json:"args"`
	Progress    int      `json:"progress"`
	MaxProgress int      `json:"max"`
}

type providerFetchTask struct {
	name string
	url  *U.URL
	path string
}

// route describes how a download should reach the server.
//
// viaProxy sends the request through the tunnel with rule matching: HttpRequest dials via
// inner.HandleTcp unless an explicit dialer is passed (see component/http/http.go:77-84), so
// "through the tunnel" is the library default and a plain dialer is the only way out. That plain
// dialer still runs through dialer.DefaultSocketHook, so the socket is protected — protecting a
// socket bypasses the tun, it does not bypass rule matching.
//
// Note this used to be implicitly state-dependent: with no config running, rule matching lands on
// DIRECT anyway, so a manual import while disconnected went out directly while the scheduled
// auto-update — same code path, tunnel up — went through the selected node. Issue #178 is that
// second case, on a panel that only answers off-tunnel.
type route struct {
	// viaProxy is the *preferred* route, not the only one — see fallback.
	viaProxy bool
	// fallback retries once on the other route when the failure looks like the route was the
	// problem (see shouldTryOtherRoute). Both directions are useful: a panel that whitelists the
	// home IP answers only off-tunnel, while a panel whose domain is DPI-blocked answers only
	// through it. Set for the subscription only — providers are best-effort and fetched six at a
	// time, so doubling their requests buys nothing.
	fallback bool
}

func (r route) other() route {
	return route{viaProxy: !r.viaProxy, fallback: false}
}

func (r route) name() string {
	if r.viaProxy {
		return "proxy"
	}
	return "direct"
}

// httpStatusError is a non-2xx response. It has to be an error: HttpRequest only fails on transport
// problems, so without this check a 403 error page is written to disk as config.yaml and only
// surfaces much later as a confusing YAML parse failure.
type httpStatusError struct {
	code   int
	status string
	// header of the refusal. A panel that rejects an expired plan or a device over its HWID limit
	// says so in the headers of the very response it refuses with, so these are what turn a bare
	// "HTTP 403" into an actionable reason — see the failure path in FetchAndValid.
	header map[string][]string
}

func (e httpStatusError) Error() string {
	return fmt.Sprintf("server returned HTTP %s", e.status)
}

func openUrl(ctx context.Context, url string, includeSubscriptionHeaders bool, viaProxy bool) (io.ReadCloser, map[string][]string, error) {
	base := http.Header{"User-Agent": {"ClashMetaForAndroid/" + app.VersionName()}}
	hdr := base
	if includeSubscriptionHeaders {
		hdr = app.MergeSubscriptionFetchHeaders(base)
	}
	var options []clashHttp.Option
	if !viaProxy {
		options = append(options, clashHttp.WithDialer(dialer.NewDialer()))
	}
	response, err := clashHttp.HttpRequest(ctx, url, http.MethodGet, hdr, nil, options...)

	if err != nil {
		return nil, nil, err
	}

	if response.StatusCode < 200 || response.StatusCode >= 300 {
		_ = response.Body.Close()
		return nil, nil, httpStatusError{
			code:   response.StatusCode,
			status: response.Status,
			header: response.Header,
		}
	}

	// Plain map type: mihomo's forked metacubex/http.Header and net/http.Header
	// are distinct named types over the same underlying map.
	return response.Body, response.Header, nil
}

// routeFirstAttemptTimeout caps the preferred route when a fallback is available, so a route that
// hangs until the caller's overall budget expires can't starve the second attempt. Without it a
// dead node burns all 60s of subscriptionFetchTimeout and the user watches "Updating…" twice as
// long for a result the fallback could have produced in seconds.
const routeFirstAttemptTimeout = 25 * time.Second

// shouldTryOtherRoute reports whether err suggests the *route* was the problem, rather than the
// subscription itself. Being wrong here is not free: an unnecessary direct retry leaks the panel's
// hostname (DNS query + TLS SNI) to the local network, which is exactly what routing through the
// tunnel avoids. So the HTTP side is an allowlist, not a denylist.
func shouldTryOtherRoute(err error) bool {
	var status httpStatusError
	if errors.As(err, &status) {
		// We reached the panel and it refused *this request*: 403 is what an IP-whitelisting or
		// geo-blocking panel answers to an unexpected exit IP, 451 its policy-blocked cousin.
		// Everything else is about the subscription, not the path to it — 401 is a bad token,
		// 404 a dead link, 5xx a broken panel — and retrying elsewhere only leaks the hostname.
		return status.code == http.StatusForbidden || status.code == http.StatusUnavailableForLegalReasons
	}
	// Transport-level: refused, reset, DNS failure, TLS error, deadline exceeded. All route-shaped.
	return true
}

// openRoutedUrl opens url on r's preferred route, falling back once to the other one.
//
// The fallback covers the two dead ends of a single fixed route: with the tunnel preferred, a
// subscription that can only be fetched off-tunnel is unreachable — and worse, a profile whose
// nodes are all dead can't be refreshed, because the refresh goes through the dead nodes. With
// direct preferred, a DPI-blocked panel is unreachable. Only the *open* is retried; a body that
// dies mid-transfer is left to the caller's normal error path.
func openRoutedUrl(ctx context.Context, url string, includeSubscriptionHeaders bool, r route) (io.ReadCloser, map[string][]string, error) {
	if !r.fallback {
		return openUrl(ctx, url, includeSubscriptionHeaders, r.viaProxy)
	}

	first, cancelFirst := context.WithTimeout(ctx, routeFirstAttemptTimeout)
	reader, header, err := openUrl(first, url, includeSubscriptionHeaders, r.viaProxy)
	if err == nil {
		// The body is read later, by fetch — cancelling now would truncate it. Hand the cancel
		// to Close instead, which fetch defers.
		return cancelingReadCloser{ReadCloser: reader, cancel: cancelFirst}, header, nil
	}
	cancelFirst()

	if !shouldTryOtherRoute(err) {
		return nil, nil, err
	}

	alt := r.other()
	// Logged rather than silent: falling back to direct means the hostname just went out over the
	// local network, and a user who cares needs to be able to see that it happened.
	log.Warnln("Subscription fetch via %s failed (%s), retrying %s", r.name(), err.Error(), alt.name())

	reader, header, altErr := openUrl(ctx, url, includeSubscriptionHeaders, alt.viaProxy)
	if altErr != nil {
		return nil, nil, fmt.Errorf("%s: %w (%s: %v)", r.name(), err, alt.name(), altErr)
	}

	log.Infoln("Subscription fetch succeeded via %s", alt.name())

	return reader, header, nil
}

// cancelingReadCloser releases a request-scoped context once the body is closed.
type cancelingReadCloser struct {
	io.ReadCloser
	cancel context.CancelFunc
}

func (c cancelingReadCloser) Close() error {
	defer c.cancel()
	return c.ReadCloser.Close()
}

func openContent(url string) (io.ReadCloser, error) {
	return app.OpenContent(url)
}

const (
	// The main subscription is the single critical download — give it generous
	// room (matches upstream ClashMetaForAndroid) so a slow CDN / DPI-laggy link
	// doesn't fail the whole update.
	subscriptionFetchTimeout = 60 * time.Second
	// Each rule-/proxy-provider runs on a short budget so one stuck provider can't
	// hold up the parallel import (see [fetchProviders] / [maxProviderConcurrency]).
	providerFetchTimeout = 20 * time.Second
)

// Downloads a single URL to a file with a caller-supplied timeout. The timeout
// is decoupled per use case (see [subscriptionFetchTimeout] / [providerFetchTimeout]):
// the main subscription is one critical fetch and gets generous room for slow
// CDNs / DPI, while each rule-/proxy-provider runs on a short budget so a stuck
// provider can't hold up the (parallel, see [fetchProviders]) import.
//
// For http(s) URLs the response headers are returned too (nil for content://
// and on error) — the subscription fetch persists them via [writeFetchHeaders]
// so the Kotlin side can read subscription-userinfo / X-Brand-* / naming
// headers without issuing a second GET of its own.
func fetch(url *U.URL, file string, timeout time.Duration, includeSubscriptionHeaders bool, r route) (map[string][]string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var reader io.ReadCloser
	var header map[string][]string
	var err error

	switch url.Scheme {
	case "http", "https":
		reader, header, err = openRoutedUrl(ctx, url.String(), includeSubscriptionHeaders, r)
	case "content":
		reader, err = openContent(url.String())
	default:
		err = fmt.Errorf("unsupported scheme %s of %s", url.Scheme, url)
	}

	if err != nil {
		return nil, err
	}

	defer reader.Close()

	_ = os.MkdirAll(P.Dir(file), 0700)

	f, err := os.OpenFile(file, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return nil, err
	}

	defer f.Close()

	_, err = io.Copy(f, reader)
	if err != nil {
		_ = os.Remove(file)
		return nil, err
	}

	return header, nil
}

// FetchHeadersFileName is the per-profile snapshot of the subscription
// response headers, written next to config.yaml on every successful
// subscription download. One flat JSON object, keys lowercased, multi-value
// headers joined the HTTP way (", "). The Kotlin side feeds it to the same
// header parsers (usage / brand / display-name) that used to run against a
// SECOND OkHttp GET of the subscription — that extra request doubled traffic
// (panels count fetches against quota), raced the primary download, and kept
// a whole parallel header path alive (the mojibake class of bugs).
const FetchHeadersFileName = "fetch-headers.json"

func writeFetchHeaders(profilePath string, header map[string][]string) {
	file := P.Join(profilePath, FetchHeadersFileName)

	if header == nil {
		// content:// / inline imports have no HTTP response; drop any stale
		// snapshot from a previous source so consumers don't read old quota.
		_ = os.Remove(file)
		return
	}

	flat := fetchheaders.Filter(header)

	bytes, err := json.Marshal(flat)
	if err != nil {
		return
	}

	// Best-effort: header persistence must never fail the import itself.
	_ = os.WriteFile(file, bytes, 0600)
}

func FetchAndValid(
	path string,
	url string,
	force bool,
	// viaProxy is the user's preferred route (Profile -> Update via proxy, on by default): through
	// the tunnel with rule matching, or a direct dial for subscriptions that are only reachable
	// off-tunnel (issue #178). Either way the other route is tried once if the first fails in a
	// route-shaped way — see [route].
	viaProxy bool,
	reportStatus func(string),
) error {
	configPath := P.Join(path, "config.yaml")

	trimmed := strings.TrimSpace(url)
	if isInlineMierusImport(trimmed) {
		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{"mierus"},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		if err := writeConfigFromMierusShare(configPath, trimmed); err != nil {
			return err
		}

		writeFetchHeaders(path, nil)
	} else if _, err := os.Stat(configPath); os.IsNotExist(err) || force {
		parsed, err := U.Parse(url)
		if err != nil {
			return err
		}

		bytes, _ := json.Marshal(&Status{
			Action:      "FetchConfiguration",
			Args:        []string{parsed.Host},
			Progress:    -1,
			MaxProgress: -1,
		})

		reportStatus(string(bytes))

		header, err := fetch(parsed, configPath, subscriptionFetchTimeout, true, route{viaProxy: viaProxy, fallback: true})
		if err != nil {
			// Keep the refusal's headers: a panel answers "expired plan" / "device limit reached"
			// in the headers of the response it refuses with, and without them the Kotlin side can
			// only report the status code. Safe to write here — [path] is the staging directory,
			// discarded on failure, so this never clobbers the live profile's snapshot.
			//
			// Unconditional, because the staging directory is seeded with a COPY of the existing
			// profile: a stale snapshot from the last successful download is already sitting there,
			// and leaving it would let the classifier explain today's failure with last week's
			// headers. Writing nil removes it, so a snapshot present after a failed fetch always
			// belongs to that fetch.
			var status httpStatusError
			if errors.As(err, &status) {
				writeFetchHeaders(path, status.header)
			} else {
				writeFetchHeaders(path, nil)
			}
			return err
		}

		writeFetchHeaders(path, header)
	}

	// Terminate age encryption at the door: unlike upstream CMFA (where
	// config.yaml is only ever read by the engine, which decrypts lazily),
	// ClashFest runs a whole TEXT pipeline over the profile on the Kotlin
	// side — overlay composition, YAML hardening, rule mapping, the error
	// classifier. Those layers must see plain YAML, so decrypt the on-disk
	// body right away using the engine keys (SetGlobalSecretKeys, installed
	// by the caller before this call). Covers every entry: URL fetch,
	// content:// file import, and an existing config revalidated later.
	// Plain (non-age) bodies pass through untouched.
	if err := decryptConfigInPlace(configPath); err != nil {
		return err
	}

	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	// Provider files re-fetch is driven separately via FetchProvidersAndValid;
	// here we only pick up providers that have no cached file on disk yet,
	// regardless of the config-level force flag.
	if err := fetchProviders(rawCfg, false, reportStatus); err != nil {
		return err
	}

	bytes, _ := json.Marshal(&Status{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})

	reportStatus(string(bytes))

	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}

	destroyProviders(cfg)

	return nil
}

func FetchProvidersAndValid(
	path string,
	force bool,
	reportStatus func(string),
) error {
	defer runtime.GC()

	rawCfg, err := UnmarshalAndPatch(path)
	if err != nil {
		return err
	}

	if err := fetchProviders(rawCfg, force, reportStatus); err != nil {
		return err
	}

	bytes, _ := json.Marshal(&Status{
		Action:      "Verifying",
		Args:        []string{},
		Progress:    0xffff,
		MaxProgress: 0xffff,
	})

	reportStatus(string(bytes))

	cfg, err := Parse(rawCfg)
	if err != nil {
		return err
	}

	destroyProviders(cfg)

	return nil
}

// maxProviderConcurrency caps how many rule/proxy-providers can be downloaded
// at once. Higher values reduce wall-clock time for big subscriptions on fast
// links, but on mobile / EDGE / Cloudflare-fronted CDNs each parallel stream
// adds its own DNS lookup + TLS handshake; opening 20 of them simultaneously
// can starve the link and trigger DNS timeouts (UnknownHostException-style
// failures). 6 is a good compromise — fast enough for a 10-provider import
// to finish in ~3 seconds on Wi-Fi, low enough not to overwhelm mobile DNS.
const maxProviderConcurrency = 6

func fetchProviders(rawCfg *config.RawConfig, force bool, reportStatus func(string)) error {
	providerFetchTasks := make([]providerFetchTask, 0)
	forEachProviders(rawCfg, func(index int, total int, name string, provider map[string]any, prefix string) {
		u, uok := provider["url"]
		p, pok := provider["path"]

		if !uok || !pok {
			return
		}

		us, uok := u.(string)
		ps, pok := p.(string)

		if !uok || !pok {
			return
		}

		// A provider that declares `proxy:` is meant to be downloaded THROUGH that
		// proxy — mihomo honors it via WithSpecialProxy when it loads the provider.
		// Our import-time fetch here is a plain DIRECT GET with no proxy, so for such
		// a provider it cannot succeed on a host the proxy exists to reach (e.g. a
		// geo/DPI-blocked raw.githubusercontent.com): it just burns the full
		// providerFetchTimeout and the swallowed error leaves no file on disk anyway.
		// Skip it — the engine fetches it correctly through the proxy on activation
		// (Fetcher.Initial → Update with the proxy) and keeps it fresh on its
		// background pull loop thereafter.
		if px, ok := provider["proxy"].(string); ok && strings.TrimSpace(px) != "" {
			return
		}

		if !force {
			if _, err := os.Stat(ps); err == nil {
				return
			}
		}

		url, err := U.Parse(us)
		if err != nil {
			return
		}

		providerFetchTasks = append(providerFetchTasks, providerFetchTask{
			name: name,
			url:  url,
			path: ps,
		})
	})

	total := len(providerFetchTasks)
	if total == 0 {
		return nil
	}

	// Parallel fetch with a bounded semaphore. Previous implementation was
	// sequential — one slow / stuck provider could stall the entire import
	// for tens of seconds even though all other providers were ready in <1s.
	// We still report Action=FetchProviders status messages, but [Progress]
	// now reflects completed count (an atomic counter incremented after each
	// fetch resolves), not the launch index, so the UI doesn't run ahead of
	// the actual work.
	sem := make(chan struct{}, maxProviderConcurrency)
	var wg sync.WaitGroup
	var done int32

	for _, task := range providerFetchTasks {
		wg.Add(1)
		sem <- struct{}{}
		go func(t providerFetchTask) {
			defer wg.Done()
			defer func() { <-sem }()

			// Per-task errors are intentionally swallowed (mirrors the prior
			// behaviour): a missing rule-provider should not abort the whole
			// import — the user can still activate the profile and the
			// provider re-fetches on the next FetchProvidersAndValid pass.
			// Provider response headers are irrelevant (only the subscription
			// fetch persists them, see writeFetchHeaders).
			// Device-identifying subscription headers are scoped to the subscription
			// origin. Providers may be controlled by unrelated third parties.
			_, _ = fetch(t.url, t.path, providerFetchTimeout, false, route{})

			current := atomic.AddInt32(&done, 1)
			bytes, _ := json.Marshal(&Status{
				Action:      "FetchProviders",
				Args:        []string{t.name},
				Progress:    int(current),
				MaxProgress: total,
			})
			reportStatus(string(bytes))
		}(task)
	}
	wg.Wait()

	return nil
}
