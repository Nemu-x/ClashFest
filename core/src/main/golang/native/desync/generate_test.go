package desync

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/Nemu-x/desync/presets"
	"gopkg.in/yaml.v3"

	"cfa/native/snapshot"
)

func TestGenerateConfig(t *testing.T) {
	c := Presets()
	y, err := generateConfig(Request{
		Services:         []string{"youtube", "discord", "youtube"},
		CustomDomains:    []string{"Example.org", "*.example.org", " "},
		StrategyPresetID: c.DefaultTCP,
	}, c)
	if err != nil {
		t.Fatal(err)
	}
	if msg := snapshot.ValidateBytes([]byte(y)); msg != "" {
		t.Fatalf("engine rejects:\n%s\n%s", y, msg)
	}
	var doc struct {
		Mode    string           `yaml:"mode"`
		Proxies []map[string]any `yaml:"proxies"`
		Rules   []string         `yaml:"rules"`
	}
	if err := yaml.Unmarshal([]byte(y), &doc); err != nil {
		t.Fatal(err)
	}
	if doc.Mode != "rule" || len(doc.Proxies) != 3 {
		t.Fatalf("profile:\n%s", y)
	}
	for i, name := range []string{"desync-youtube", "desync-discord", CustomProxyName} {
		if doc.Proxies[i]["name"] != name || doc.Proxies[i]["type"] != "desync" || doc.Proxies[i]["reject-quic"] != true {
			t.Errorf("proxy %d = %v", i, doc.Proxies[i])
		}
	}
	if doc.Rules[len(doc.Rules)-1] != "MATCH,DIRECT" {
		t.Errorf("last rule = %q", doc.Rules[len(doc.Rules)-1])
	}
	want := map[string]bool{
		"DOMAIN-SUFFIX,googlevideo.com,desync-youtube": true,
		"DOMAIN-SUFFIX,discord.gg,desync-discord":      true,
		"DOMAIN-SUFFIX,example.org,desync-custom":      true,
	}
	n := 0
	for _, r := range doc.Rules {
		if want[r] {
			n++
		}
		if strings.Contains(r, "example.org") && r != "DOMAIN-SUFFIX,example.org,desync-custom" {
			t.Errorf("unexpected custom rule %q", r)
		}
	}
	if n != len(want) {
		t.Errorf("rules missing:\n%s", strings.Join(doc.Rules, "\n"))
	}
	if !strings.HasPrefix(y, "# ") {
		t.Error("no header")
	}
}

func TestGenerateConfigAuto(t *testing.T) {
	c := Presets()
	auto := autoCandidates(c)
	if len(auto) < 2 {
		t.Skipf("only %d automatic candidates on this host", len(auto))
	}
	y, err := generateConfig(Request{Services: []string{"youtube"}, CustomDomains: []string{"example.org"}}, c)
	if err != nil {
		t.Fatal(err)
	}
	if msg := snapshot.ValidateBytes([]byte(y)); msg != "" {
		t.Fatalf("engine rejects:\n%s\n%s", y, msg)
	}
	var doc struct {
		Proxies     []map[string]any `yaml:"proxies"`
		ProxyGroups []struct {
			Name     string   `yaml:"name"`
			Type     string   `yaml:"type"`
			Proxies  []string `yaml:"proxies"`
			URL      string   `yaml:"url"`
			Interval int      `yaml:"interval"`
			Lazy     bool     `yaml:"lazy"`
		} `yaml:"proxy-groups"`
		Rules []string `yaml:"rules"`
	}
	if err := yaml.Unmarshal([]byte(y), &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.ProxyGroups) != 2 || len(doc.Proxies) != 2*len(auto) {
		t.Fatalf("profile:\n%s", y)
	}
	for i, want := range []struct{ name, url string }{
		{"desync-youtube", "https://www.youtube.com/generate_204"},
		{CustomProxyName, "https://example.org/"},
	} {
		g := doc.ProxyGroups[i]
		if g.Name != want.name || g.Type != "fallback" || g.URL != want.url || !g.Lazy || g.Interval != autoInterval {
			t.Errorf("group %d = %+v", i, g)
		}
		if len(g.Proxies) != len(auto) || g.Proxies[0] != candidateName(want.name, AutoCandidates[0]) {
			t.Errorf("group %d candidates = %v", i, g.Proxies)
		}
	}
	for _, p := range doc.Proxies {
		if p["type"] != "desync" || p["reject-quic"] != true {
			t.Errorf("candidate = %v", p)
		}
	}
	if !strings.Contains(strings.Join(doc.Rules, "\n"), "DOMAIN-SUFFIX,googlevideo.com,desync-youtube") {
		t.Errorf("rules:\n%s", strings.Join(doc.Rules, "\n"))
	}

	// One candidate is not a choice: a single proxy, as with a fixed preset.
	single := Catalog{TCP: auto[:1], DefaultTCP: auto[0].ID}
	y, err = generateConfig(Request{Services: []string{"youtube"}}, single)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(y, "proxy-groups") || !strings.Contains(y, "name: desync-youtube\n") {
		t.Fatalf("single candidate:\n%s", y)
	}
}

func TestGenerateConfigEveryPreset(t *testing.T) {
	c := Presets()
	for _, p := range c.TCP {
		if _, err := generateConfig(Request{Services: []string{"discord"}, StrategyPresetID: p.ID}, c); err != nil {
			t.Errorf("%s: %v", p.Name, err)
		}
	}
	for _, p := range c.UDP {
		y, err := generateConfig(Request{Services: []string{"discord"}, UDPPresetID: p.ID}, c)
		if err != nil {
			t.Errorf("%s: %v", p.Name, err)
			continue
		}
		if strings.Contains(y, "reject-quic") || !strings.Contains(y, "udp:") {
			t.Errorf("%s: UDP preset without udp or with reject-quic:\n%s", p.Name, y)
		}
	}
}

func TestGenerateConfigErrors(t *testing.T) {
	c := Presets()
	var fakeID string
	for _, p := range presets.TCP() {
		if p.Name == "fake-ttl5" {
			fakeID = p.ID()
		}
	}
	if fakeID == "" {
		t.Fatal("fake-ttl5 not in the library catalog")
	}
	off := false
	for name, tc := range map[string]struct {
		req     Request
		catalog Catalog
		want    string
	}{
		"empty":           {Request{}, c, "at least one"},
		"unknown service": {Request{Services: []string{"myspace"}}, c, `unknown service "myspace"`},
		"bad domain":      {Request{CustomDomains: []string{"a b"}}, c, "not a host name"},
		"unknown preset":  {Request{Services: []string{"discord"}, StrategyPresetID: "s0000000000"}, c, "unknown tcp preset"},
		"udp as tcp":      {Request{Services: []string{"discord"}, StrategyPresetID: presets.UDP()[0].ID()}, c, "unknown tcp preset"},
		"unavailable":     {Request{Services: []string{"discord"}, StrategyPresetID: fakeID}, catalogFor(noCaps()), "not available here"},
		"no udp here":     {Request{Services: []string{"discord"}, UDPPresetID: presets.UDP()[0].ID(), RejectQUIC: &off}, catalogFor(noCaps()), "not available here"},
	} {
		if _, err := generateConfig(tc.req, tc.catalog); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: err = %v, want %q", name, err, tc.want)
		}
	}
}

func TestGenerateConfigJSON(t *testing.T) {
	var ok Result
	if err := json.Unmarshal([]byte(GenerateConfigJSON(`{"services":["youtube"],"customDomains":[]}`)), &ok); err != nil {
		t.Fatal(err)
	}
	if ok.Error != "" || !strings.Contains(ok.YAML, "desync-youtube") {
		t.Fatalf("result = %+v", ok)
	}
	var bad Result
	if err := json.Unmarshal([]byte(GenerateConfigJSON(`{"services":`)), &bad); err != nil {
		t.Fatal(err)
	}
	if bad.YAML != "" || !strings.HasPrefix(bad.Error, "malformed request") {
		t.Fatalf("result = %+v", bad)
	}
}
