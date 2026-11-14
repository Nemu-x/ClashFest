package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patch 0037 (mux.cool): a vless proxy accepts the
// block and a disabled block is ignored. Patch 0040: the combinations the
// client cannot carry no longer fail validation; the engine ignores mux.cool
// with a warning and the profile loads.

const muxCoolOracleProxy = `
  - name: node
    type: PROXY_TYPE
    server: 1.2.3.4
    port: 443
    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
    network: tcp
    udp: true
EXTRA    mux.cool:
MUX_COOL
`

func muxCoolOracle(proxyType, extra, block string) string {
	proxy := strings.Replace(muxCoolOracleProxy, "PROXY_TYPE", proxyType, 1)
	proxy = strings.Replace(proxy, "EXTRA", extra, 1)
	return strings.Replace(proxy, "MUX_COOL", block, 1)
}

func TestCorePatchOracle_MuxCool(t *testing.T) {
	for _, block := range []string{
		"      enabled: true",
		"      enabled: true\n      max-concurrency: 16\n      max-connections: 0\n      max-carriers: 2\n      xudp-concurrency: 4\n      xudp-proxy-udp443: allow",
		"      enabled: true\n      xudp-proxy-udp443: skip",
		// A disabled block is not validated, as without the key.
		"      enabled: false\n      xudp-proxy-udp443: bogus",
	} {
		if errMsg := ValidateBytes([]byte(patchOracleProfile(muxCoolOracle("vless", "", block)))); errMsg != "" {
			t.Errorf("mux.cool %q rejected: %s", block, errMsg)
		}
	}
	for _, tc := range []struct {
		name, proxyType, extra, block string
	}{
		{"udp443", "vless", "", "      enabled: true\n      xudp-proxy-udp443: bogus"},
		{"negative", "vless", "", "      enabled: true\n      max-concurrency: -1"},
		{"vision", "vless", "    flow: xtls-rprx-vision\n", "      enabled: true"},
		{"smux", "vless", "    smux:\n      enabled: true\n", "      enabled: true"},
		{"vmess", "vmess", "    cipher: auto\n    alterId: 0\n", "      enabled: true"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if errMsg := ValidateBytes([]byte(patchOracleProfile(muxCoolOracle(tc.proxyType, tc.extra, tc.block)))); errMsg != "" {
				t.Fatalf("mux.cool that cannot apply failed the profile: %s", errMsg)
			}
		})
	}
}
