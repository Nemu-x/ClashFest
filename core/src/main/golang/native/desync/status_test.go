package desync

import (
	"encoding/json"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

func TestCollectStatus(t *testing.T) {
	d, err := adapter.ParseProxy(map[string]any{"name": "dy", "type": "desync", "tlsrec": "1+s", "split": "1"})
	if err != nil {
		t.Fatal(err)
	}
	direct, err := adapter.ParseProxy(map[string]any{"name": "plain", "type": "direct"})
	if err != nil {
		t.Fatal(err)
	}
	st := collectStatus(map[string]C.Proxy{"dy": d, "plain": direct, "DIRECT": direct}, nil)
	if len(st.Proxies) != 1 || st.Proxies[0].Name != "dy" || st.Proxies[0].Provider != "" {
		t.Fatalf("status = %+v", st)
	}
	var obj struct {
		Strategy string `json:"strategy"`
		TCP      struct {
			TLSRec []string `json:"tlsrec"`
			Split  []string `json:"split"`
			Fake   any      `json:"fake"`
		} `json:"tcp"`
		UDP any `json:"udp"`
	}
	if err := json.Unmarshal(st.Proxies[0].Desync, &obj); err != nil {
		t.Fatal(err)
	}
	if obj.Strategy != "static" || len(obj.TCP.TLSRec) != 1 || obj.TCP.TLSRec[0] != "1+s" ||
		len(obj.TCP.Split) != 1 || obj.TCP.Split[0] != "1" || obj.TCP.Fake != nil || obj.UDP != nil {
		t.Fatalf("desync object = %s", st.Proxies[0].Desync)
	}
	if st.Proxies[0].DNSFallbacks != nil || st.Proxies[0].UDPDiagnostics != nil {
		t.Fatalf("diagnostics without dns-fallback / udp: %+v", st.Proxies[0])
	}

	b, err := json.Marshal(collectStatus(nil, nil))
	if err != nil || string(b) != `{"proxies":[],"routes":[]}` {
		t.Fatalf("empty status = %s, %v", b, err)
	}
}

func TestCollectStatusDNSFallback(t *testing.T) {
	d, err := adapter.ParseProxy(map[string]any{"name": "dy", "type": "desync", "tlsrec": "1+s", "dns-fallback": true})
	if err != nil {
		t.Fatal(err)
	}
	st := collectStatus(map[string]C.Proxy{"dy": d}, nil)
	if len(st.Proxies) != 1 || st.Proxies[0].DNSFallbacks == nil || *st.Proxies[0].DNSFallbacks != 0 {
		t.Fatalf("status = %+v", st)
	}
}

// The routes of an automatic profile, read off the engine's own parse of the
// generated YAML: one per fallback group, using its first candidate before
// any check ran.
func TestCollectStatusRoutes(t *testing.T) {
	c := Presets()
	if len(autoCandidates(c)) < 2 {
		t.Skip("no automatic selection on this host")
	}
	y, err := generateConfig(Request{Services: []string{"youtube", "discord"}}, c)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := config.UnmarshalRawConfig([]byte(y))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	st := collectStatus(cfg.Proxies, nil)
	if len(st.Routes) != 2 || st.Routes[0].Name != "desync-discord" || st.Routes[1].Name != "desync-youtube" ||
		st.Routes[1].Now != candidateName("desync-youtube", AutoCandidates[0]) {
		t.Fatalf("routes = %+v", st.Routes)
	}
	if len(st.Proxies) != 2*len(autoCandidates(c)) {
		t.Fatalf("proxies = %d", len(st.Proxies))
	}
}
