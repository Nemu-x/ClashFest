package snapshot

import (
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
)

// Engine oracle for core patch 0005 (proxy groups of type chain): a chain is
// expanded into dialer-proxy wiring at load time, so the engine itself must
// accept a valid cascade, wire groups and providers used as hops, and reject
// loops and conflicting dialer-proxy settings at validation time.

const chainOracleProxies = `proxies:
  - {name: entry, type: socks5, server: 127.0.0.1, port: 1081}
  - {name: mid, type: socks5, server: 127.0.0.1, port: 1082}
  - {name: exit, type: ss, server: 127.0.0.1, port: 1083, cipher: aes-128-gcm, password: pass}
  - {name: exit-b, type: ss, server: 127.0.0.1, port: 1084, cipher: aes-128-gcm, password: pass}
  - {name: other, type: socks5, server: 127.0.0.1, port: 1085}
`

func parseChainOracle(t *testing.T, profile string) *config.Config {
	t.Helper()
	rawCfg, err := config.UnmarshalRawConfig([]byte(profile))
	if err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatalf("engine rejected a valid chain: %v", err)
	}
	t.Cleanup(func() { closeProviders(cfg) })
	return cfg
}

func dialerOf(t *testing.T, cfg *config.Config, name string) string {
	t.Helper()
	p, ok := cfg.Proxies[name]
	if !ok {
		t.Fatalf("proxy %s missing", name)
	}
	return p.ProxyInfo().DialerProxy
}

func TestCorePatchOracle_ChainThreeHops(t *testing.T) {
	profile := chainOracleProxies + `proxy-groups:
  - {name: cascade, type: chain, proxies: [entry, mid, exit]}
  - {name: G, type: select, proxies: [cascade, DIRECT]}
rules:
  - MATCH,G
`
	if errMsg := ValidateBytes([]byte(profile)); errMsg != "" {
		t.Fatalf("3-hop chain rejected: %s", errMsg)
	}
	cfg := parseChainOracle(t, profile)
	for name, want := range map[string]string{"entry": "", "mid": "entry", "exit": "mid", "exit-b": ""} {
		if got := dialerOf(t, cfg, name); got != want {
			t.Errorf("%s: dialer-proxy %q, want %q", name, got, want)
		}
	}
	chain := cfg.Proxies["cascade"]
	if chain.Type().String() != "Chain" {
		t.Fatalf("cascade type %s", chain.Type())
	}
	js, err := chain.MarshalJSON()
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"now":"exit"`, `"all":["entry","mid","exit"]`, `"hops":[`, `"position":3`} {
		if !strings.Contains(string(js), want) {
			t.Errorf("/proxies JSON %s lacks %s", js, want)
		}
	}
}

func TestCorePatchOracle_ChainWiresGroupAndProviderHops(t *testing.T) {
	profile := chainOracleProxies + `proxy-providers:
  subs:
    type: inline
    payload:
      - {name: sub-1, type: ss, server: 127.0.0.1, port: 1090, cipher: aes-128-gcm, password: pass}
proxy-groups:
  - {name: entries, type: fallback, proxies: [entry, other], url: "http://127.0.0.1/"}
  - {name: exits, type: url-test, proxies: [exit, exit-b], use: [subs], url: "http://127.0.0.1/"}
  - {name: cascade, type: chain, proxies: [entries, exits]}
rules:
  - MATCH,cascade
`
	cfg := parseChainOracle(t, profile)
	for name, want := range map[string]string{"exit": "entries", "exit-b": "entries", "entry": "", "other": ""} {
		if got := dialerOf(t, cfg, name); got != want {
			t.Errorf("%s: dialer-proxy %q, want %q", name, got, want)
		}
	}
	pd, ok := cfg.Providers["subs"]
	if !ok {
		t.Fatal("provider subs missing")
	}
	if err := pd.Initial(); err != nil {
		t.Fatalf("inline provider: %v", err)
	}
	proxies := pd.Proxies()
	if len(proxies) != 1 || proxies[0].ProxyInfo().DialerProxy != "entries" {
		t.Fatalf("provider proxies not wired through the previous hop: %v", proxies)
	}
}

func TestCorePatchOracle_ChainRejectsLoopsAndConflicts(t *testing.T) {
	for _, tc := range []struct {
		name, extra, groups, want string
	}{
		{
			name:   "loop through a group",
			groups: "  - {name: g, type: select, proxies: [cascade, other]}\n  - {name: cascade, type: chain, proxies: [g, exit]}\n",
			want:   "chain [cascade]: loop detected: cascade -> g -> cascade",
		},
		{
			name:   "loop made by the wiring",
			groups: "  - {name: exits, type: fallback, proxies: [exit, entry]}\n  - {name: cascade, type: chain, proxies: [entry, exits]}\n",
			want:   "loop detected: entry -> entry",
		},
		{
			name:   "dialer-proxy conflict",
			extra:  "  - {name: pinned, type: ss, server: 127.0.0.1, port: 1086, cipher: aes-128-gcm, password: pass, dialer-proxy: other}\n",
			groups: "  - {name: cascade, type: chain, proxies: [entry, pinned]}\n",
			want:   "chain [cascade]: proxy [pinned] (hop 2) already has dialer-proxy [other], the chain needs [entry]",
		},
		{
			name:   "two chains disagree",
			groups: "  - {name: c1, type: chain, proxies: [entry, exit]}\n  - {name: c2, type: chain, proxies: [mid, exit]}\n",
			want:   "must go through [mid], but chain [c1] already sends it through [entry]",
		},
	} {
		t.Run(tc.name, func(t *testing.T) {
			profile := chainOracleProxies + tc.extra + "proxy-groups:\n" + tc.groups + "rules:\n  - MATCH,DIRECT\n"
			errMsg := ValidateBytes([]byte(profile))
			if !strings.Contains(errMsg, tc.want) {
				t.Fatalf("got %q, want an error containing %q", errMsg, tc.want)
			}
		})
	}
}
