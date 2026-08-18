package configscript

import (
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

func run(t *testing.T, cfg, script string) map[string]any {
	t.Helper()
	out, err := ApplyScript([]byte(cfg), script, "profile")
	if err != nil {
		t.Fatalf("ApplyScript: %v", err)
	}
	var m map[string]any
	if err := yaml.Unmarshal(out, &m); err != nil {
		t.Fatalf("result is not valid YAML: %v\n%s", err, out)
	}
	return m
}

const baseConfig = `
mode: rule
port: 7890
dns:
  enable: false
proxies:
  - name: A
    type: ss
rules:
  - MATCH,DIRECT
`

func TestApplyScriptMutatesConfig(t *testing.T) {
	m := run(t, baseConfig, `
		function main(config) {
			config.dns.enable = true
			config.mode = "global"
			return config
		}
	`)
	dns, _ := m["dns"].(map[string]any)
	if dns["enable"] != true {
		t.Fatalf("dns.enable not set: %#v", m["dns"])
	}
	if m["mode"] != "global" {
		t.Fatalf("mode not set: %#v", m["mode"])
	}
}

func TestApplyScriptPreservesUntouchedKeys(t *testing.T) {
	m := run(t, baseConfig, `function main(config) { return config }`)
	if m["port"] != 7890 {
		t.Fatalf("port changed: %#v (%T)", m["port"], m["port"])
	}
	rules, _ := m["rules"].([]any)
	if len(rules) != 1 || rules[0] != "MATCH,DIRECT" {
		t.Fatalf("rules changed: %#v", m["rules"])
	}
	proxies, _ := m["proxies"].([]any)
	if len(proxies) != 1 {
		t.Fatalf("proxies changed: %#v", m["proxies"])
	}
}

// The second argument is what Clash Verge Rev passes; scripts written for FlClash take one
// argument and must keep working.
func TestApplyScriptProfileNameArgument(t *testing.T) {
	m := run(t, baseConfig, `function main(config, name) { config.mode = name; return config }`)
	if m["mode"] != "profile" {
		t.Fatalf("profile name not passed: %#v", m["mode"])
	}
}

func TestApplyScriptCanAddProxies(t *testing.T) {
	m := run(t, baseConfig, `
		function main(config) {
			config.proxies.push({ name: "B", type: "trojan", server: "example.com", port: 443 })
			return config
		}
	`)
	proxies, _ := m["proxies"].([]any)
	if len(proxies) != 2 {
		t.Fatalf("expected 2 proxies, got %#v", m["proxies"])
	}
	second, _ := proxies[1].(map[string]any)
	if second["name"] != "B" || second["port"] != 443 {
		t.Fatalf("added proxy wrong: %#v", second)
	}
}

func TestApplyScriptEmptyIsNoOp(t *testing.T) {
	out, err := ApplyScript([]byte(baseConfig), "", "profile")
	if err != nil {
		t.Fatal(err)
	}
	if string(out) != baseConfig {
		t.Fatalf("empty script rewrote the document:\n%s", out)
	}
}

// Modern syntax matters: people paste scripts written against QuickJS and boa.
func TestApplyScriptModernSyntax(t *testing.T) {
	m := run(t, baseConfig, `
		function main(config) {
			const extra = config.extra ?? {}
			const names = config.proxies.map(p => p.name)
			return { ...config, names, extra, touched: config?.dns?.enable === false }
		}
	`)
	names, _ := m["names"].([]any)
	if len(names) != 1 || names[0] != "A" {
		t.Fatalf("spread/map/arrow failed: %#v", m["names"])
	}
	if m["touched"] != true {
		t.Fatalf("optional chaining failed: %#v", m["touched"])
	}
}

// U+2028 is legal inside a JSON string but terminates a line in JS source — the reason the
// document is handed to JSON.parse as a value instead of being spliced into source.
func TestApplyScriptLineSeparatorInValue(t *testing.T) {
	cfg := "mode: rule\nnote: \"a b\"\n"
	m := run(t, cfg, `function main(config) { return config }`)
	if m["note"] != "a b" {
		t.Fatalf("U+2028 mangled: %q", m["note"])
	}
}

func TestApplyScriptErrors(t *testing.T) {
	cases := []struct {
		name   string
		script string
		want   error
	}{
		{"syntax", `function main( {`, ErrScriptCompile},
		{"no main", `var x = 1`, ErrScriptNoMain},
		{"throws", `function main() { throw new Error("boom") }`, ErrScriptRuntime},
		{"returns nothing", `function main(config) { }`, ErrScriptBadValue},
		{"returns scalar", `function main(config) { return 42 }`, ErrScriptBadValue},
		{"infinite loop", `function main(config) { while (true) {} }`, ErrScriptTimeout},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			_, err := ApplyScript([]byte(baseConfig), c.script, "profile")
			if err == nil {
				t.Fatal("expected an error")
			}
			if !errors.Is(err, c.want) {
				t.Fatalf("got %v, want %v", err, c.want)
			}
		})
	}
}

// A script gets the config and nothing else: no fs, no net, no require, no timers.
func TestApplyScriptHasNoHostBindings(t *testing.T) {
	for _, global := range []string{"require", "process", "fetch", "setTimeout", "XMLHttpRequest"} {
		script := "function main(config) { config.probe = typeof " + global + "; return config }"
		m := run(t, baseConfig, script)
		if m["probe"] != "undefined" {
			t.Fatalf("%s is reachable from a user script: %v", global, m["probe"])
		}
	}
}

func TestApplyScriptRejectsBrokenConfig(t *testing.T) {
	_, err := ApplyScript([]byte("mode: [unclosed\n"), `function main(c) { return c }`, "p")
	if err == nil || !strings.Contains(err.Error(), "parsing config") {
		t.Fatalf("expected a parse error, got %v", err)
	}
}

func TestApplyScriptJSONSuccess(t *testing.T) {
	var res Result
	if err := json.Unmarshal([]byte(ApplyScriptJSON([]byte(baseConfig),
		`function main(c) { c.mode = "global"; return c }`, "p")), &res); err != nil {
		t.Fatal(err)
	}
	if !res.Ok || res.Code != "" {
		t.Fatalf("expected success: %#v", res)
	}
	var m map[string]any
	if err := yaml.Unmarshal([]byte(res.YAML), &m); err != nil {
		t.Fatalf("payload is not YAML: %v", err)
	}
	if m["mode"] != "global" {
		t.Fatalf("script did not apply: %#v", m["mode"])
	}
}

func TestApplyScriptJSONCarriesStableCodes(t *testing.T) {
	cases := map[string]string{
		`function main( {`:                   "script-compile",
		`var x = 1`:                          "script-no-main",
		`function main() { throw 1 }`:        "script-runtime",
		`function main(c) { return 42 }`:     "script-bad-value",
		`function main(c) { while(true){} }`: "script-timeout",
	}
	for script, want := range cases {
		var res Result
		if err := json.Unmarshal([]byte(ApplyScriptJSON([]byte(baseConfig), script, "p")), &res); err != nil {
			t.Fatal(err)
		}
		if res.Ok {
			t.Fatalf("%s: expected failure", script)
		}
		if res.Code != want {
			t.Fatalf("%s: got code %q, want %q", script, res.Code, want)
		}
		if res.YAML != "" {
			t.Fatalf("%s: failure must not carry a document", script)
		}
	}
}
