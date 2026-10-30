package desync

import (
	"context"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"gopkg.in/yaml.v3"
)

// overlayBase is a subscription as panels ship them: an include-all url-test
// group, service rules of its own and a final rule.
const overlayBase = `mixed-port: 0
dns:
  enable: true
  enhanced-mode: fake-ip
  nameserver: [https://dns.example/dns-query]
proxies:
  - {name: node-a, type: socks5, server: 127.0.0.1, port: 1080}
  - {name: node-b, type: socks5, server: 127.0.0.1, port: 1081}
proxy-groups:
  - {name: Proxy, type: select, proxies: [Auto, node-a, node-b]}
  - {name: Auto, type: url-test, include-all: true, url: "http://127.0.0.1:9/", interval: 600}
  - {name: Video, type: select, proxies: [DIRECT, Proxy]}
rules:
  - DOMAIN-SUFFIX,youtube.com,Video
  - DOMAIN-SUFFIX,discord.com,Proxy
  - GEOIP,RU,DIRECT
  - MATCH,Proxy
`

func overlayRequest() Request {
	return Request{Services: []string{"youtube", "discord"}}
}

func composeTestOverlay(t *testing.T) (string, *config.Config) {
	t.Helper()
	out, err := composeOverlay([]byte(overlayBase), overlayRequest(), testCatalog(t))
	if err != nil {
		t.Fatal(err)
	}
	raw, err := config.UnmarshalRawConfig([]byte(out))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	return out, cfg
}

// testCatalog is the catalog of a device that has every automatic candidate.
func testCatalog(t *testing.T) Catalog {
	t.Helper()
	catalog := catalogFor(effectiveCapabilities())
	if len(autoCandidates(catalog)) < 2 {
		t.Skip("this platform has fewer than two automatic candidates")
	}
	return catalog
}

func TestComposeOverlayKeepsTheSubscription(t *testing.T) {
	out, cfg := composeTestOverlay(t)

	// The overlay's rules first, then the subscription's, unchanged and in order.
	var rules []string
	for _, r := range cfg.Rules {
		rules = append(rules, r.RuleType().String()+","+r.Payload()+","+r.Adapter())
	}
	tail := []string{"DomainSuffix,youtube.com,Video", "DomainSuffix,discord.com,Proxy", "GeoIP,ru,DIRECT", "Match,,Proxy"}
	if got := rules[len(rules)-len(tail):]; strings.Join(got, "|") != strings.Join(tail, "|") {
		t.Errorf("subscription rules = %q, want %q", got, tail)
	}
	if !strings.HasSuffix(rules[0], ",desync-youtube") {
		t.Errorf("first rule = %q, want an overlay rule", rules[0])
	}

	// Candidates fall back to the subscription's resolver when its direct
	// nameservers answer nothing.
	if n := strings.Count(out, "dns-fallback: true"); n != 8 {
		t.Errorf("dns-fallback on %d candidates, want 8", n)
	}
	// IPv4 only, so a block-page AAAA alone counts as empty and falls back.
	if n := strings.Count(out, "ip-version: ipv4"); n != 8 {
		t.Errorf("ip-version ipv4 on %d candidates, want 8", n)
	}

	// DNS as fetched.
	var doc struct {
		DNS map[string]any `yaml:"dns"`
	}
	if err := yaml.Unmarshal([]byte(out), &doc); err != nil {
		t.Fatal(err)
	}
	if doc.DNS["nameserver"].([]any)[0] != "https://dns.example/dns-query" || doc.DNS["enhanced-mode"] != "fake-ip" {
		t.Errorf("dns = %v", doc.DNS)
	}

	// The include-all group keeps exactly the subscription's proxies: a
	// desync candidate there would carry everything around the VPN.
	auto := cfg.Proxies["Auto"].Adapter().(*outboundgroup.URLTest)
	var members []string
	for _, p := range auto.Proxies() {
		members = append(members, p.Name())
	}
	if strings.Join(members, ",") != "node-a,node-b" {
		t.Errorf("Auto members = %v, want node-a,node-b", members)
	}

	// Each route: a hidden select group over its candidates and every
	// outbound the subscription can route to.
	for _, name := range []string{"desync-youtube", "desync-discord"} {
		g, ok := cfg.Proxies[name].Adapter().(*outboundgroup.Selector)
		if !ok {
			t.Fatalf("%s is %v, want a select group", name, cfg.Proxies[name].Type())
		}
		set := map[string]bool{}
		for _, p := range g.Proxies() {
			set[p.Name()] = true
		}
		for _, want := range []string{name + "~tlsrec-1s", "Proxy", "Video", "Auto", "node-a", "DIRECT"} {
			if !set[want] {
				t.Errorf("%s has no member %s", name, want)
			}
		}
		if !g.Hidden() {
			t.Errorf("%s is not hidden", name)
		}
	}
}

func TestComposeOverlayAppendsToAnExcludeFilter(t *testing.T) {
	base := strings.Replace(overlayBase, "include-all: true,", "include-all: true, exclude-filter: node-b,", 1)
	out, err := composeOverlay([]byte(base), overlayRequest(), testCatalog(t))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out, "node-b`"+overlayCandidateFilter) {
		t.Errorf("exclude-filter not extended:\n%s", out)
	}
}

func TestComposeOverlayRefusesANameClash(t *testing.T) {
	base := strings.Replace(overlayBase, "name: node-b,", "name: desync-discord,", 1)
	if _, err := composeOverlay([]byte(base), overlayRequest(), testCatalog(t)); err == nil {
		t.Fatal("no error for a subscription outbound named desync-discord")
	}
}

func TestOverlayRoutingStartsOnTheSubscriptionAndSwitches(t *testing.T) {
	_, cfg := composeTestOverlay(t)

	var mu sync.Mutex
	pass := map[string]bool{"desync-discord~tlsrec-1s-split-1": true}
	f := useFakeTransfer(t)
	transferTest = func(ctx context.Context, p C.Proxy, target *url.URL, minBytes, maxBytes int64, stall time.Duration) (int64, error) {
		mu.Lock()
		ok := pass[p.Name()]
		mu.Unlock()
		if ok {
			return maxBytes, nil
		}
		return f.test(ctx, p, target, minBytes, maxBytes, stall)
	}
	oldRecheck := overlayRecheck
	overlayRecheck = 50 * time.Millisecond
	t.Cleanup(func() { overlayRecheck = oldRecheck; StopOverlayRouting() })

	now := func(name string) string {
		return cfg.Proxies[name].Adapter().(*outboundgroup.Selector).Now()
	}
	// Block the checks until the safe start is observed.
	mu.Lock()
	startOverlayRouting(cfg.Proxies, cfg.Rules)
	if got := now("desync-discord"); got != "Proxy" {
		t.Errorf("discord starts on %s, want the subscription's Proxy", got)
	}
	if got := now("desync-youtube"); got != "Video" {
		t.Errorf("youtube starts on %s, want the subscription's Video", got)
	}
	mu.Unlock()

	waitFor(t, "discord on its candidate", func() bool { return now("desync-discord") == "desync-discord~tlsrec-1s-split-1" })
	waitFor(t, "youtube checked", func() bool { _, ok := overlayVerdicts.Load("desync-youtube"); return ok })
	if got := now("desync-youtube"); got != "Video" {
		t.Errorf("youtube with no working candidate uses %s, want Video", got)
	}
	st := collectStatus(cfg.Proxies, nil)
	for _, r := range st.Routes {
		if r.Name == "desync-discord" && (!r.Overlay || !r.Checked || !r.Working) {
			t.Errorf("discord status = %+v", r)
		}
		if r.Name == "desync-youtube" && (!r.Overlay || !r.Checked || r.Working) {
			t.Errorf("youtube status = %+v", r)
		}
	}

	// The strategy stops working: back to the subscription.
	mu.Lock()
	pass = map[string]bool{}
	mu.Unlock()
	waitFor(t, "discord back on Proxy", func() bool { return now("desync-discord") == "Proxy" })
}

func TestSubscriptionTargetSkipsTheOverlayAndPass(t *testing.T) {
	y := `
proxies:
  - {name: node, type: socks5, server: 127.0.0.1, port: 1080}
proxy-groups:
  - {name: desync-discord, type: select, proxies: [DIRECT, node]}
rules:
  - DOMAIN-SUFFIX,discord.com,desync-discord
  - DOMAIN-SUFFIX,discord.com,PASS
  - DOMAIN-KEYWORD,discord,node
  - MATCH,DIRECT
`
	raw, err := config.UnmarshalRawConfig([]byte(y))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	overlay := map[string]bool{"desync-discord": true}
	if got := subscriptionTarget("discord.com", overlay, cfg.Rules, cfg.Proxies); got != "node" {
		t.Errorf("discord.com -> %s, want node", got)
	}
	if got := subscriptionTarget("example.org", overlay, cfg.Rules, cfg.Proxies); got != "DIRECT" {
		t.Errorf("example.org -> %s, want DIRECT", got)
	}
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(10 * time.Millisecond)
	}
}
