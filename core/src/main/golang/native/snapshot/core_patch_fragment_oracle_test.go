package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patch 0033 (fragment-opts): valid blocks are accepted
// on any proxy type, an invalid one fails validation instead of every dial.

const fragmentOracleProxy = `
  - name: node
    type: trojan
    server: 1.2.3.4
    port: 443
    password: dummy
    sni: www.example.com
    fragment-opts:
FRAGMENT_OPTS
`

func TestCorePatchOracle_FragmentOpts(t *testing.T) {
	for _, opts := range []string{
		"      packets: tlshello\n      length: 10-20\n      delay: 0",
		"      packets: tlshello",
		"      packets: 1-3\n      length: 50\n      delay: 5-10\n      max-split: 4",
	} {
		proxy := strings.Replace(fragmentOracleProxy, "FRAGMENT_OPTS", opts, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
			t.Errorf("fragment-opts %q rejected: %s", opts, errMsg)
		}
	}
	for opts, want := range map[string]string{
		"      packets: tlshello\n      length: 0-5": "fragment-opts.length",
		"      packets: first":                     "fragment-opts.packets",
		"      packets: 0-2":                       "fragment-opts.packets",
		"      delay: soon":                        "fragment-opts.delay",
	} {
		proxy := strings.Replace(fragmentOracleProxy, "FRAGMENT_OPTS", opts, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); !strings.Contains(errMsg, want) {
			t.Errorf("fragment-opts %q: got %q, want %q", opts, errMsg, want)
		}
	}
}
