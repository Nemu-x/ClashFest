package snapshot

import (
	"encoding/json"
	"testing"
)

// Repro of a user report (2026-07): a select group with `include-all: true` and
// `filter: '^(?!.*Netherlands 2)(…)'` rendered with a single member offline while other mihomo
// clients showed four. Root cause was our Kotlin preview: isSafeFilterPattern rejects every
// quantifier and every lookaround, so the filter could not be evaluated and the group collapsed
// to its declared `proxies:`. This oracle proves the engine handles the exact same YAML, which is
// why the offline preview now asks the engine instead of re-implementing the semantics.
const lookaheadFilterYAML = `
mixed-port: 7890
mode: rule
proxies:
  - {name: "🇩🇪 Germany", type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: a}
  - {name: "🇫🇮 Finland", type: ss, server: 1.1.1.2, port: 443, cipher: aes-128-gcm, password: a}
  - {name: "🇳🇱 Netherlands 1", type: ss, server: 1.1.1.3, port: 443, cipher: aes-128-gcm, password: a}
  - {name: "🇳🇱 Netherlands 2", type: ss, server: 1.1.1.4, port: 443, cipher: aes-128-gcm, password: a}
  - {name: "🇳🇱 Netherlands 3", type: ss, server: 1.1.1.5, port: 443, cipher: aes-128-gcm, password: a}
  - {name: "🇸🇪 Sweden", type: ss, server: 1.1.1.6, port: 443, cipher: aes-128-gcm, password: a}
proxy-groups:
  - name: "▶️ YouTube"
    type: select
    include-all: true
    filter: '^(?!.*Netherlands 2)(🇳🇱|NL|Netherlands|🇸🇪|SE|Sweden)'
    proxies:
      - "🎲 Fastest YouTube"
  - name: "🎲 Fastest YouTube"
    type: url-test
    url: https://www.gstatic.com/generate_204
    interval: 300
    lazy: true
    hidden: true
    filter: '^(?!.*Netherlands 2)(🇳🇱|NL|Netherlands|🇸🇪|SE|Sweden)'
    include-all: true
rules:
  - MATCH,▶️ YouTube
`

func decodeGroups(t *testing.T, blob string) []ResolvedGroup {
	t.Helper()
	if blob == "" {
		t.Fatal("ResolvedGroupsJSON returned empty (parse failed)")
	}
	var groups []ResolvedGroup
	if err := json.Unmarshal([]byte(blob), &groups); err != nil {
		t.Fatalf("decode groups: %v (raw: %s)", err, blob)
	}
	return groups
}

func groupByName(t *testing.T, groups []ResolvedGroup, name string) ResolvedGroup {
	t.Helper()
	for _, g := range groups {
		if g.Name == name {
			return g
		}
	}
	t.Fatalf("group %q not found in %+v", name, groups)
	return ResolvedGroup{}
}

func TestResolvedGroups_LookaheadFilterIsHonoured(t *testing.T) {
	groups := decodeGroups(t, ResolvedGroupsJSON([]byte(lookaheadFilterYAML)))

	yt := groupByName(t, groups, "▶️ YouTube")

	// include-all + filter must expand to the three matching nodes, plus the declared sub-group.
	want := map[string]bool{
		"🎲 Fastest YouTube": true,
		"🇳🇱 Netherlands 1":  true,
		"🇳🇱 Netherlands 3":  true,
		"🇸🇪 Sweden":         true,
	}
	if len(yt.All) != len(want) {
		t.Fatalf("expected %d members, got %d: %v", len(want), len(yt.All), yt.All)
	}
	for _, name := range yt.All {
		if !want[name] {
			t.Fatalf("unexpected member %q (negative lookahead must drop Netherlands 2, filter must drop Germany/Finland); got %v", name, yt.All)
		}
	}
}

func TestResolvedGroups_PreservesConfigOrderAndHidden(t *testing.T) {
	groups := decodeGroups(t, ResolvedGroupsJSON([]byte(lookaheadFilterYAML)))

	if len(groups) != 2 {
		t.Fatalf("expected 2 groups, got %d: %+v", len(groups), groups)
	}
	// cfg.Proxies is a map; we iterate rawCfg.ProxyGroup so the config's own order survives.
	if groups[0].Name != "▶️ YouTube" || groups[1].Name != "🎲 Fastest YouTube" {
		t.Fatalf("group order not preserved: %s, %s", groups[0].Name, groups[1].Name)
	}
	if groups[0].Hidden {
		t.Error("▶️ YouTube must not be hidden")
	}
	if !groups[1].Hidden {
		t.Error("🎲 Fastest YouTube declares hidden: true, engine must report it")
	}
	if groups[0].Type == "" {
		t.Error("group type must be reported")
	}
}

func TestResolvedGroups_InvalidYamlReturnsEmpty(t *testing.T) {
	if got := ResolvedGroupsJSON([]byte(": : not yaml : :")); got != "" {
		t.Fatalf("invalid YAML must yield empty string so callers can fall back, got %q", got)
	}
}

func TestResolvedGroups_NoGroupsYieldsEmptyArray(t *testing.T) {
	const noGroups = `
mixed-port: 7890
mode: rule
proxies:
  - {name: "🇩🇪 Germany", type: ss, server: 1.1.1.1, port: 443, cipher: aes-128-gcm, password: a}
rules:
  - MATCH,DIRECT
`
	if got := ResolvedGroupsJSON([]byte(noGroups)); got != "[]" {
		t.Fatalf("a valid config without groups must yield \"[]\", got %q", got)
	}
}
