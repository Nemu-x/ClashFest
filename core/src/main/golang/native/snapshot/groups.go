package snapshot

import (
	"encoding/json"

	"github.com/metacubex/mihomo/config"

	// Same linkname reason as validate.go: config.ParseRawConfig needs
	// hub/executor in the link graph.
	_ "github.com/metacubex/mihomo/hub/executor"
)

// ResolvedGroup is the engine's own answer for a single proxy-group, in the shape the UI consumes.
// Field names mirror mihomo's proxy JSON so the payload is recognisable at a glance.
type ResolvedGroup struct {
	Name   string   `json:"name"`
	Type   string   `json:"type"`
	Now    string   `json:"now,omitempty"`
	Hidden bool     `json:"hidden,omitempty"`
	All    []string `json:"all"`
}

// ResolvedGroupsJSON runs the engine's own parse pipeline over in-memory YAML and returns the
// proxy-group membership mihomo actually computes — `include-all*` expanded, `use:` resolved,
// `filter` / `exclude-filter` / `exclude-type` applied, provider name prefixes in place.
//
// This is the offline counterpart of tunnel.QueryProxyGroup. The UI needs the same answer while
// the tunnel is down, and re-implementing mihomo's group semantics in Kotlin has repeatedly
// diverged from the engine (a `filter` containing a quantifier or lookahead silently collapsed
// the group to its declared `proxies:`; `use:`-backed groups resolved to nothing at all). Asking
// the engine removes that whole class of divergence instead of chasing it directive by directive.
//
// No listeners and no TUN are started: this is the same parse ValidateBytes performs, so the cost
// is one config parse. Providers opened during ParseRawConfig are always released.
//
// Returns a JSON array of [ResolvedGroup] in the config's own group order (mihomo's cfg.Proxies is
// a map and would randomise it). An empty string means the YAML could not be parsed — callers
// should fall back to their own preview; a valid config with no groups yields "[]".
func ResolvedGroupsJSON(data []byte) string {
	rawCfg, err := config.UnmarshalRawConfig(data)
	if err != nil {
		return ""
	}

	// Snapshot the declared order BEFORE parsing: ParseRawConfig topologically sorts
	// rawCfg.ProxyGroup *in place* (a group referenced by another has to be built first), so
	// reading it afterwards would hand the UI mihomo's build order instead of the user's.
	order := make([]string, 0, len(rawCfg.ProxyGroup))
	for _, raw := range rawCfg.ProxyGroup {
		if name, _ := raw["name"].(string); name != "" {
			order = append(order, name)
		}
	}

	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		return ""
	}
	defer closeProviders(cfg)

	out := make([]ResolvedGroup, 0, len(order))
	for _, name := range order {
		p, ok := cfg.Proxies[name]
		if !ok {
			continue
		}
		blob, err := p.MarshalJSON()
		if err != nil {
			continue
		}
		var meta struct {
			Type   string   `json:"type"`
			Now    string   `json:"now"`
			Hidden bool     `json:"hidden"`
			All    []string `json:"all"`
		}
		// Only outbound *groups* carry `all`; a plain proxy marshals without it. That is the
		// cheapest reliable discriminator and it comes straight from the engine.
		if err := json.Unmarshal(blob, &meta); err != nil || meta.All == nil {
			continue
		}
		out = append(out, ResolvedGroup{
			Name:   name,
			Type:   meta.Type,
			Now:    meta.Now,
			Hidden: meta.Hidden,
			All:    meta.All,
		})
	}

	encoded, err := json.Marshal(out)
	if err != nil {
		return ""
	}
	return string(encoded)
}
