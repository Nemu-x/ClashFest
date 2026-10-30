package desync

import (
	"encoding/json"
	"strings"
	"testing"

	lib "github.com/Nemu-x/desync"
	"github.com/Nemu-x/desync/presets"
	"gopkg.in/yaml.v3"

	"github.com/metacubex/mihomo/adapter"
)

func allCaps() lib.Capabilities {
	c := lib.PlatformCapabilities()
	c.FakeTCP, c.FakeUDP, c.OOB, c.Disorder.Supported, c.Ack.Sent, c.Ack.Acked = true, true, true, true, true, true
	c.Unsupported = map[string]string{}
	return c
}

func noCaps() lib.Capabilities {
	return lib.Capabilities{OS: "plan9", Unsupported: map[string]string{"fake-tcp": "test"}}
}

func TestCatalogFiltersByCapabilities(t *testing.T) {
	none := catalogFor(noCaps())
	for _, p := range none.TCP {
		if p.Options.Fake != nil || p.Options.Disorder || p.Options.OOB || p.Options.SplitWait != "" {
			t.Errorf("preset %s needs a platform feature but passed an empty capability set", p.Name)
		}
	}
	if len(none.UDP) != 0 {
		t.Errorf("UDP presets without fake-udp: %+v", none.UDP)
	}
	var fakeSkipped bool
	for _, s := range none.Skipped {
		if s.Kind == "tcp" && strings.HasPrefix(s.Name, "fake-ttl") {
			fakeSkipped = strings.Contains(s.Reason, "fake-tcp: test")
		}
	}
	if !fakeSkipped {
		t.Errorf("fake presets not reported as skipped with the library's reason: %+v", none.Skipped)
	}
	if len(none.TCP) == 0 || none.DefaultTCP != none.TCP[0].ID {
		t.Fatalf("default = %q, presets = %+v", none.DefaultTCP, none.TCP)
	}
	for _, p := range none.TCP {
		if p.Name == presets.BaselineName {
			t.Error("the baseline is not a desync strategy")
		}
	}
	for i := 1; i < len(none.TCP); i++ {
		if none.TCP[i-1].Rank > none.TCP[i].Rank {
			t.Errorf("TCP presets not in rank order at %d", i)
		}
	}
	if none.CatalogVersion == "" || none.CatalogVersion == catalogFor(allCaps()).CatalogVersion {
		t.Errorf("catalog version must follow the IDs: %q", none.CatalogVersion)
	}
}

// The preset in the outbound's keys must be the preset: convert every catalog
// entry, let the engine parse it as a desync proxy and compare what the proxy
// reports with the library options.
func TestPresetOptionsRoundTripThroughOutbound(t *testing.T) {
	c := catalogFor(lib.PlatformCapabilities())
	if len(c.TCP) == 0 {
		t.Fatal("no TCP preset on this platform")
	}
	for _, p := range c.TCP {
		m, ok := presets.ByID(p.ID)
		if !ok || m.TCP == nil {
			t.Fatalf("%s: ID %s not in the library catalog", p.Name, p.ID)
		}
		proxy := parseAsProxy(t, p)
		var got struct {
			Desync struct {
				TCP struct {
					TLSRec   []string `json:"tlsrec"`
					Split    []string `json:"split"`
					Wait     string   `json:"split-wait"`
					Disorder bool     `json:"disorder"`
					OOB      any      `json:"oob"`
					Random   any      `json:"random"`
					Fake     *struct {
						TTL       int      `json:"ttl"`
						Prefix    int      `json:"prefix"`
						SNI       []string `json:"sni"`
						Randomize bool     `json:"randomize"`
					} `json:"fake"`
				} `json:"tcp"`
			} `json:"desync"`
		}
		if err := json.Unmarshal(proxy, &got); err != nil {
			t.Fatal(err)
		}
		o, tcp := m.TCP.Options, got.Desync.TCP
		if strings.Join(tcp.TLSRec, ",") != joinPositions(o.TLSRec) || strings.Join(tcp.Split, ",") != joinPositions(o.Split) ||
			tcp.Wait != o.SplitWait.String() || tcp.Disorder != o.Disorder || (tcp.OOB != nil) != (o.OOB != nil) ||
			(tcp.Random != nil) != (o.Random != nil) || (tcp.Fake != nil) != (o.Fake != nil) {
			t.Errorf("%s: outbound reports %s", p.Name, proxy)
			continue
		}
		if f := o.Fake; f != nil && (tcp.Fake.TTL != f.TTL || tcp.Fake.Prefix != f.Prefix ||
			strings.Join(tcp.Fake.SNI, ",") != strings.Join(f.SNI, ",") || tcp.Fake.Randomize != f.Randomize) {
			t.Errorf("%s: fake reported as %+v, preset %+v", p.Name, *tcp.Fake, *f)
		}
	}
	for _, p := range c.UDP {
		m, ok := presets.ByID(p.ID)
		if !ok || m.UDP == nil {
			t.Fatalf("%s: ID %s not in the library catalog", p.Name, p.ID)
		}
		proxy := parseAsProxy(t, Preset{Name: p.Name, Options: Options{TLSRec: "1+s", UDP: p.Options.UDP}})
		var got struct {
			Desync struct {
				UDP struct {
					TTL      int    `json:"ttl"`
					Repeats  int    `json:"repeats"`
					Template string `json:"template"`
					QUICOnly bool   `json:"quic-only"`
				} `json:"udp"`
			} `json:"desync"`
		}
		if err := json.Unmarshal(proxy, &got); err != nil {
			t.Fatal(err)
		}
		o, u := m.UDP.Options, got.Desync.UDP
		wantRepeats := o.Repeats
		if wantRepeats == 0 {
			wantRepeats = 6
		}
		wantTemplate := "zero"
		if o.Template == lib.UDPTemplateQUICInitial {
			wantTemplate = "quic"
		}
		if u.TTL != o.TTL || u.Repeats != wantRepeats || u.Template != wantTemplate || u.QUICOnly != o.QUICOnly {
			t.Errorf("%s: outbound reports %s", p.Name, proxy)
		}
	}
}

func parseAsProxy(t *testing.T, p Preset) []byte {
	t.Helper()
	y, err := yaml.Marshal(generatedProxy{Name: "p", Type: "desync", Options: p.Options})
	if err != nil {
		t.Fatal(err)
	}
	var mapping map[string]any
	if err := yaml.Unmarshal(y, &mapping); err != nil {
		t.Fatal(err)
	}
	proxy, err := adapter.ParseProxy(mapping)
	if err != nil {
		t.Fatalf("%s: the outbound rejects\n%s: %v", p.Name, y, err)
	}
	b, err := proxy.MarshalJSON()
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestPresetsJSONShape(t *testing.T) {
	b, err := json.Marshal(catalogFor(allCaps()))
	if err != nil {
		t.Fatal(err)
	}
	var m struct {
		Version string           `json:"catalogVersion"`
		Default string           `json:"defaultTcp"`
		TCP     []map[string]any `json:"tcp"`
		UDP     []map[string]any `json:"udp"`
	}
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	if m.Version == "" || m.Default == "" || len(m.TCP) == 0 || len(m.UDP) == 0 {
		t.Fatalf("catalog = %s", b)
	}
	for _, p := range append(m.TCP, m.UDP...) {
		for _, k := range []string{"id", "name", "description", "rank", "options"} {
			if _, ok := p[k]; !ok {
				t.Fatalf("preset without %q: %v", k, p)
			}
		}
	}
	if !strings.Contains(string(b), `"udp":{"ttl":`) || !strings.Contains(string(b), `"fake":{"ttl":`) {
		t.Fatalf("options are not in the outbound's keys: %s", b)
	}
}
