package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patch 0033 (fragment-opts): valid blocks are accepted
// on any proxy type. Patch 0040: an invalid one no longer fails validation; the
// engine drops the block with a warning and the profile loads.

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
	for _, opts := range []string{
		"      packets: tlshello\n      length: 0-5",
		"      packets: first",
		"      packets: 0-2",
		"      delay: soon",
	} {
		proxy := strings.Replace(fragmentOracleProxy, "FRAGMENT_OPTS", opts, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
			t.Errorf("invalid fragment-opts %q failed the profile: %s", opts, errMsg)
		}
	}
}
