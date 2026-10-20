package snapshot

import (
	"encoding/json"
	"fmt"
	"os"
	P "path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/common/yaml"
	tlsC "github.com/metacubex/mihomo/component/tls"
)

// Citrus compatibility suite: client-side example configs of getmikan/Citrus
// (docs/config.yaml, the site docs, the interop tests) run through the engine
// validator offline, so the gap between what a Citrus-targeted subscription may
// contain and what vanilla mihomo plus the ClashFest patch series accepts is a
// test result instead of a guess. See docs/core-patches.md.
//
// testdata/citrus/manifest.json lists every case with its expected outcome:
//
//   - "accept": the engine validates it and reads every Citrus-only key the
//     case declares (minus the ones listed as harmless).
//   - "unsupported:<reason>": the owner does not want it (DNSCrypt, the PROTOCOL
//     rule, server-only material, ...).
//   - "deferred:<reason>": a gap too big for a small patch.
//
// An unsupported or deferred case must still show its gap (an engine error or an
// ignored key); when it stops showing one, the test fails so the manifest is
// updated rather than going stale.
//
// "Silently ignored" is measured, not listed: every declared Citrus-only key is
// replaced by a mapping and then by a sequence. A key the engine decodes fails
// validation for at least one of them; a key it skips passes both. The
// client-fingerprint of every proxy is also looked up, because vanilla mihomo
// logs an unknown name and falls back to Go's TLS instead of failing.

type citrusManifest struct {
	Citrus   string           `json:"citrus"`
	Fixtures []citrusFixture `json:"fixtures"`
}

type citrusFixture struct {
	File  string                `json:"file"`
	Split string                `json:"split,omitempty"` // "", "proxies" or "rules"
	Cases map[string]citrusCase `json:"cases"`         // "" for an unsplit fixture
}

type citrusCase struct {
	Outcome    string   `json:"outcome"`
	Note       string   `json:"note,omitempty"`
	CitrusOnly []string `json:"citrus-only,omitempty"` // key paths, relative to the proxy for split proxies
	Harmless   []string `json:"harmless,omitempty"`    // ignored keys/values vanilla handles the same way
}

type citrusResult struct {
	id, outcome, engineErr string
	ignored                []string
}

func TestCitrusCompat(t *testing.T) {
	dir := P.Join("testdata", "citrus")
	raw, err := os.ReadFile(P.Join(dir, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var m citrusManifest
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("manifest.json: %v", err)
	}

	listed := map[string]bool{}
	for _, f := range m.Fixtures {
		listed[f.File] = true
	}
	files, _ := P.Glob(P.Join(dir, "*.yaml"))
	for _, f := range files {
		if !listed[P.Base(f)] {
			t.Errorf("%s is not in manifest.json", P.Base(f))
		}
	}

	var results []citrusResult
	for _, f := range m.Fixtures {
		data, err := os.ReadFile(P.Join(dir, f.File))
		if err != nil {
			t.Errorf("%s: %v", f.File, err)
			continue
		}
		cases, err := citrusExpand(data, f.Split)
		if err != nil {
			t.Errorf("%s: %v", f.File, err)
			continue
		}
		for name := range f.Cases {
			if _, ok := cases[name]; !ok {
				t.Errorf("%s: manifest case %q is not in the fixture", f.File, name)
			}
		}
		names := make([]string, 0, len(cases))
		for name := range cases {
			names = append(names, name)
		}
		sort.Strings(names)
		for _, name := range names {
			id := f.File
			if name != "" {
				id += "#" + name
			}
			want, ok := f.Cases[name]
			if !ok {
				r := citrusRun(t, cases[name], f.Split, citrusCase{})
				t.Errorf("%s: no manifest entry (engine: %q, ignored: %v)", id, r.engineErr, r.ignored)
				continue
			}
			r := citrusRun(t, cases[name], f.Split, want)
			r.id, r.outcome = id, want.Outcome
			results = append(results, r)
			citrusCheck(t, r, want)
		}
	}

	counts := map[string]int{}
	for _, r := range results {
		kind, _, _ := strings.Cut(r.outcome, ":")
		counts[kind]++
		line := fmt.Sprintf("%-58s %s", r.id, r.outcome)
		if r.engineErr != "" {
			line += " | engine: " + r.engineErr
		}
		if len(r.ignored) > 0 {
			line += " | ignored: " + strings.Join(r.ignored, ", ")
		}
		t.Log(line)
	}
	t.Logf("Citrus@%s: %d cases, %d accept, %d unsupported, %d deferred",
		m.Citrus, len(results), counts["accept"], counts["unsupported"], counts["deferred"])
}

func citrusCheck(t *testing.T, r citrusResult, want citrusCase) {
	t.Helper()
	harmless := map[string]bool{}
	for _, h := range want.Harmless {
		harmless[h] = true
	}
	var gaps []string
	for _, ig := range r.ignored {
		if !harmless[ig] {
			gaps = append(gaps, ig)
		}
	}
	for _, h := range want.Harmless {
		if !slices.Contains(r.ignored, h) {
			t.Errorf("%s: %q is listed as harmless but the engine reads it now", r.id, h)
		}
	}
	kind, reason, _ := strings.Cut(want.Outcome, ":")
	switch kind {
	case "accept":
		if r.engineErr != "" {
			t.Errorf("%s: engine rejected it: %s", r.id, r.engineErr)
		}
		if len(gaps) > 0 {
			t.Errorf("%s: accepted but silently ignored: %v", r.id, gaps)
		}
	case "unsupported", "deferred":
		if reason == "" {
			t.Errorf("%s: outcome %q needs a reason", r.id, want.Outcome)
		}
		if r.engineErr == "" && len(gaps) == 0 {
			t.Errorf("%s: marked %s but the engine accepts it and reads every key: update manifest.json", r.id, want.Outcome)
		}
	default:
		t.Errorf("%s: unknown outcome %q", r.id, want.Outcome)
	}
}

// citrusExpand turns a fixture into its cases: the whole document, or one case
// per proxy / per rule.
func citrusExpand(data []byte, split string) (map[string]map[string]any, error) {
	doc := map[string]any{}
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	switch split {
	case "":
		return map[string]map[string]any{"": doc}, nil
	case "proxies":
		list, _ := doc["proxies"].([]any)
		byName := map[string]any{}
		for _, p := range list {
			byName[citrusName(p)] = p
		}
		cases := map[string]map[string]any{}
		for _, p := range list {
			name := citrusName(p)
			if _, dup := cases[name]; dup || name == "" {
				return nil, fmt.Errorf("proxy name %q missing or repeated", name)
			}
			c := citrusClone(doc)
			// the proxy, the proxies its dialer-proxy chain names, a group and a rule using it
			proxies := []any{p}
			for q := p; ; {
				dialer, _ := q.(map[string]any)["dialer-proxy"].(string)
				next, ok := byName[dialer]
				if !ok {
					break
				}
				proxies = append(proxies, next)
				q = next
			}
			c["proxies"] = proxies
			c["proxy-groups"] = []any{map[string]any{"name": "citrus-compat", "type": "select", "proxies": []any{name}}}
			c["rules"] = []any{"MATCH,citrus-compat"}
			cases[name] = c
		}
		return cases, nil
	case "rules":
		list, _ := doc["rules"].([]any)
		cases := map[string]map[string]any{}
		for _, r := range list {
			rule, _ := r.(string)
			c := citrusClone(doc)
			c["rules"] = []any{rule, "MATCH,DIRECT"}
			cases[rule] = c
		}
		return cases, nil
	}
	return nil, fmt.Errorf("unknown split %q", split)
}

func citrusRun(t *testing.T, doc map[string]any, split string, want citrusCase) citrusResult {
	t.Helper()
	var r citrusResult
	r.engineErr = citrusValidate(t, doc)
	if r.engineErr != "" {
		return r
	}
	for _, path := range want.CitrusOnly {
		full := path
		if split == "proxies" {
			full = "proxies[0]." + path
		}
		if !citrusHas(doc, full) {
			t.Errorf("citrus-only key %q is not in the case", path)
			continue
		}
		consumed := false
		for _, probe := range []any{
			map[string]any{"citrus-compat-probe": []any{1, 2}},
			[]any{"citrus-compat-probe", 1},
		} {
			c := citrusClone(doc)
			citrusSet(c, full, probe)
			if citrusValidate(t, c) != "" {
				consumed = true
				break
			}
		}
		if !consumed {
			r.ignored = append(r.ignored, path)
		}
	}
	proxies, _ := doc["proxies"].([]any)
	for _, p := range proxies {
		fp, _ := p.(map[string]any)["client-fingerprint"].(string)
		if fp == "" || fp == "none" {
			continue
		}
		if _, ok := tlsC.GetFingerprint(fp); !ok {
			r.ignored = append(r.ignored, "client-fingerprint: "+fp)
		}
	}
	return r
}

func citrusValidate(t *testing.T, doc map[string]any) string {
	t.Helper()
	out, err := yaml.Marshal(doc)
	if err != nil {
		t.Fatalf("marshal case: %v", err)
	}
	return ValidateBytes(out)
}

func citrusName(p any) string {
	m, _ := p.(map[string]any)
	name, _ := m["name"].(string)
	return name
}

func citrusClone(v any) map[string]any {
	return citrusCloneAny(v).(map[string]any)
}

func citrusCloneAny(v any) any {
	switch x := v.(type) {
	case map[string]any:
		c := make(map[string]any, len(x))
		for k, e := range x {
			c[k] = citrusCloneAny(e)
		}
		return c
	case []any:
		c := make([]any, len(x))
		for i, e := range x {
			c[i] = citrusCloneAny(e)
		}
		return c
	}
	return v
}

// citrusSegments splits "proxies[0].reality-opts.mldsa65-verify" into
// "proxies", 0, "reality-opts", "mldsa65-verify".
func citrusSegments(path string) []any {
	var segs []any
	for _, part := range strings.Split(path, ".") {
		key, idx, hasIdx := strings.Cut(part, "[")
		segs = append(segs, key)
		if hasIdx {
			n, _ := strconv.Atoi(strings.TrimSuffix(idx, "]"))
			segs = append(segs, n)
		}
	}
	return segs
}

func citrusWalk(doc any, segs []any) (parent any, last any, ok bool) {
	cur := doc
	for i, s := range segs {
		if i == len(segs)-1 {
			switch k := s.(type) {
			case string:
				m, isMap := cur.(map[string]any)
				if !isMap {
					return nil, nil, false
				}
				_, ok = m[k]
				return m, k, ok
			case int:
				l, isList := cur.([]any)
				return l, k, isList && k < len(l)
			}
		}
		switch k := s.(type) {
		case string:
			m, _ := cur.(map[string]any)
			cur = m[k]
		case int:
			l, _ := cur.([]any)
			if k >= len(l) {
				return nil, nil, false
			}
			cur = l[k]
		}
	}
	return nil, nil, false
}

func citrusHas(doc map[string]any, path string) bool {
	_, _, ok := citrusWalk(doc, citrusSegments(path))
	return ok
}

func citrusSet(doc map[string]any, path string, v any) {
	parent, last, _ := citrusWalk(doc, citrusSegments(path))
	switch p := parent.(type) {
	case map[string]any:
		p[last.(string)] = v
	case []any:
		p[last.(int)] = v
	}
}

// TestCitrusCompatProbe checks the "silently ignored" probe itself: a key the
// engine decodes must count as read, a key nobody knows as ignored.
func TestCitrusCompatProbe(t *testing.T) {
	cases, err := citrusExpand([]byte(`
proxies:
  - {name: node, type: ss, server: 192.0.2.1, port: 443, cipher: chacha20-ietf-poly1305, password: password, udp: true, citrus-compat-unknown: 1}
`), "proxies")
	if err != nil {
		t.Fatal(err)
	}
	r := citrusRun(t, cases["node"], "proxies", citrusCase{CitrusOnly: []string{"udp", "cipher", "citrus-compat-unknown"}})
	if r.engineErr != "" {
		t.Fatal(r.engineErr)
	}
	if len(r.ignored) != 1 || r.ignored[0] != "citrus-compat-unknown" {
		t.Fatalf("ignored = %v, want only citrus-compat-unknown", r.ignored)
	}
}
