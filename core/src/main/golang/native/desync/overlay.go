package desync

// Desync on top of a subscription (the profile's desync overlay).
//
// ComposeOverlay adds desync to a subscription config without changing what
// the subscription routes: for every route of the request it adds the desync
// candidate proxies and a hidden select group named like the route
// (desync-<service>), whose members are the candidates and every outbound the
// subscription's rules can name, and it prepends the route's DOMAIN-SUFFIX
// rules, so the overlay's rules are matched first. Nothing else changes: DNS,
// sniffer, the subscription's groups and rules stay as fetched, with one
// exception, below.
//
// Which member a group uses is decided at run time (overlay_runtime.go): first
// the outbound the subscription's own rules would pick for the service, so a
// route is never worse than without the overlay, then a candidate once the
// transfer test shows it carries the service, and back to the subscription's
// outbound when it stops doing so.
//
// The exception: a subscription group with include-all / include-all-proxies
// takes every proxy of the config, and would take the desync candidates too.
// A url-test group would then send everything through a desync candidate, a
// direct connection, around the VPN. Such groups get an exclude-filter on the
// candidates' names, so they keep exactly the members they had.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"gopkg.in/yaml.v3"

	"cfa/native/snapshot"
)

// overlayCandidateFilter matches the candidate names candidateName gives
// (desync-<route>~<preset>), as a regular expression of exclude-filter.
const overlayCandidateFilter = `^desync-[a-z0-9-]+~`

// builtinOutbounds are the outbounds every config has.
var builtinOutbounds = []string{"DIRECT", "REJECT", "REJECT-DROP"}

type overlayGroup struct {
	Name    string   `yaml:"name"`
	Type    string   `yaml:"type"`
	Hidden  bool     `yaml:"hidden"`
	Proxies []string `yaml:"proxies"`
}

// ComposeOverlay returns base with the desync routes of req added, checked
// with the engine's parser.
func ComposeOverlay(base []byte, req Request) (string, error) {
	return composeOverlay(base, req, catalogFor(effectiveCapabilities()))
}

func composeOverlay(base []byte, req Request, catalog Catalog) (string, error) {
	pl, err := planFor(req, catalog)
	if err != nil {
		return "", err
	}

	var doc yaml.Node
	if err := yaml.Unmarshal(base, &doc); err != nil {
		return "", fmt.Errorf("the subscription is not YAML the overlay can read: %w", err)
	}
	if doc.Kind == 0 {
		doc = yaml.Node{Kind: yaml.DocumentNode, Content: []*yaml.Node{{Kind: yaml.MappingNode, Tag: "!!map"}}}
	}
	if doc.Kind != yaml.DocumentNode || len(doc.Content) != 1 || doc.Content[0].Kind != yaml.MappingNode {
		return "", errors.New("the subscription is not a YAML mapping")
	}
	root := doc.Content[0]

	var proxies, groups, rules *yaml.Node
	for _, slot := range []struct {
		key  string
		node **yaml.Node
	}{{"proxies", &proxies}, {"proxy-groups", &groups}, {"rules", &rules}} {
		if *slot.node, err = sequenceOf(root, slot.key); err != nil {
			return "", err
		}
	}

	// Every outbound the subscription's rules can name, for the groups.
	var targets []string
	taken := map[string]bool{}
	for _, list := range []*yaml.Node{proxies, groups} {
		for _, item := range list.Content {
			if name := scalarOf(item, "name"); name != "" {
				targets = append(targets, name)
				taken[name] = true
			}
		}
	}
	for _, r := range pl.routes {
		if taken[r.proxy] {
			return "", fmt.Errorf("the subscription already has an outbound named %q", r.proxy)
		}
	}
	if final := finalTarget(rules); final != "" {
		// The engine's choice before overlay_runtime.go knows better: the
		// subscription's own final rule.
		targets = append([]string{final}, targets...)
	}
	targets = append(targets, builtinOutbounds...)

	for _, item := range groups.Content {
		if isTrue(item, "include-all") || isTrue(item, "include-all-proxies") {
			filter := overlayCandidateFilter
			if old := scalarOf(item, "exclude-filter"); old != "" {
				filter = old + "`" + filter
			}
			setScalar(item, "exclude-filter", filter)
		}
	}

	var newRules []*yaml.Node
	for _, r := range pl.routes {
		group := overlayGroup{Name: r.proxy, Type: "select", Hidden: true}
		for _, tcp := range pl.tcps {
			name := candidateName(r.proxy, tcp.Name)
			if taken[name] {
				return "", fmt.Errorf("the subscription already has an outbound named %q", name)
			}
			opts := pl.optionsOf(tcp)
			// The subscription's direct nameservers are often plain DNS, which
			// a filtering network answers with nothing for the very names
			// desync is for; its own resolver (DoH, routed by its rules) is
			// not. DNS itself is not changed.
			opts.DNSFallback = true
			// Such a network may also answer with a block-page AAAA and no A
			// (Beeline: 2001:4188:2:600:10:10:34:36). IPv4 only, that answer
			// is empty and falls back too.
			node, err := nodeOf(generatedProxy{Name: name, Type: "desync", IPVersion: "ipv4", Options: opts})
			if err != nil {
				return "", err
			}
			proxies.Content = append(proxies.Content, node)
			group.Proxies = append(group.Proxies, name)
		}
		group.Proxies = appendUnique(group.Proxies, targets...)
		node, err := nodeOf(group)
		if err != nil {
			return "", err
		}
		groups.Content = append(groups.Content, node)
		for _, d := range r.domains {
			newRules = append(newRules, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: "DOMAIN-SUFFIX," + d + "," + r.proxy})
		}
	}
	rules.Content = append(newRules, rules.Content...)

	var buf bytes.Buffer
	enc := yaml.NewEncoder(&buf)
	enc.SetIndent(2)
	if err := enc.Encode(&doc); err != nil {
		return "", err
	}
	if err := enc.Close(); err != nil {
		return "", err
	}
	out := buf.String()
	if msg := snapshot.ValidateBytes([]byte(out)); msg != "" {
		return "", fmt.Errorf("the engine rejects the subscription with desync: %s", msg)
	}
	return out, nil
}

// ComposeOverlayJSON is ComposeOverlay for the bridge: the base YAML and a
// JSON Request in, a JSON Result out.
func ComposeOverlayJSON(base, request string) string {
	var res Result
	var req Request
	if err := json.Unmarshal([]byte(request), &req); err != nil {
		res.Error = "malformed request: " + err.Error()
	} else if y, err := ComposeOverlay([]byte(base), req); err != nil {
		res.Error = err.Error()
	} else {
		res.YAML = y
	}
	b, _ := json.Marshal(res)
	return string(b)
}

// finalTarget is the outbound of the last MATCH rule, or "".
func finalTarget(rules *yaml.Node) string {
	for i := len(rules.Content) - 1; i >= 0; i-- {
		fields := strings.Split(rules.Content[i].Value, ",")
		if len(fields) >= 2 && strings.EqualFold(strings.TrimSpace(fields[0]), "MATCH") {
			return strings.TrimSpace(fields[1])
		}
	}
	return ""
}

// sequenceOf returns the sequence under key, adding an empty one when the
// key is missing or null.
func sequenceOf(m *yaml.Node, key string) (*yaml.Node, error) {
	v := valueOf(m, key)
	switch {
	case v == nil:
		v = &yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
		m.Content = append(m.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, v)
	case v.Kind == yaml.SequenceNode:
	case v.Kind == yaml.ScalarNode && v.Tag == "!!null":
		*v = yaml.Node{Kind: yaml.SequenceNode, Tag: "!!seq"}
	default:
		return nil, fmt.Errorf("%s is not a list", key)
	}
	return v, nil
}

func valueOf(m *yaml.Node, key string) *yaml.Node {
	if m.Kind != yaml.MappingNode {
		return nil
	}
	for i := 0; i+1 < len(m.Content); i += 2 {
		if m.Content[i].Value == key {
			return m.Content[i+1]
		}
	}
	return nil
}

func scalarOf(m *yaml.Node, key string) string {
	if v := valueOf(m, key); v != nil && v.Kind == yaml.ScalarNode {
		return v.Value
	}
	return ""
}

func isTrue(m *yaml.Node, key string) bool {
	v := valueOf(m, key)
	if v == nil || v.Kind != yaml.ScalarNode {
		return false
	}
	var b bool
	return v.Decode(&b) == nil && b
}

func setScalar(m *yaml.Node, key, value string) {
	if v := valueOf(m, key); v != nil {
		*v = yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value}
		return
	}
	m.Content = append(m.Content,
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key},
		&yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value})
}

func nodeOf(v any) (*yaml.Node, error) {
	var n yaml.Node
	if err := n.Encode(v); err != nil {
		return nil, err
	}
	return &n, nil
}

func appendUnique(list []string, items ...string) []string {
	seen := map[string]bool{}
	for _, s := range list {
		seen[s] = true
	}
	for _, s := range items {
		if s != "" && !seen[s] {
			seen[s] = true
			list = append(list, s)
		}
	}
	return list
}
