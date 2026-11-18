package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patches 0041-0042: finalmask blocks are accepted by the patched engine; an unusable
// finalmask block (wrong network, unknown mask, xicmp under cmfa) keeps the
// profile loading, as patch 0040 does for other options; the proxy then fails
// its dials instead of connecting without its masks.

const finalmaskOracleProxy = `
  - name: node
    type: TYPE
    server: 1.2.3.4
    port: 443
CREDENTIALS
    servername: www.example.com
    client-fingerprint: FINGERPRINT
FINALMASK
`

func finalmaskProxy(typ, fingerprint, finalmask string) string {
	credentials := "    uuid: 27848739-7e62-4138-9fd3-098a63964b6b\n    tls: true"
	if typ == "trojan" {
		credentials = "    password: dummy"
	}
	return strings.NewReplacer("TYPE", typ, "CREDENTIALS", credentials, "FINGERPRINT", fingerprint, "FINALMASK", finalmask).Replace(finalmaskOracleProxy)
}

func TestCorePatchOracle_FinalMask(t *testing.T) {
	valid := map[string]string{
		"fragment": `    finalmask:
      tcp:
        - type: fragment
          packets: tlshello
          length: 100-200
          delay: 10-20`,
		"header-custom+sudoku": `    finalmask:
      tcp:
        - type: header-custom
          clients:
            - - packet: "GET / HTTP/1.1\r\n"
                type: str
        - type: sudoku
          password: sudoku`,
		"xray-nesting": `    finalmask:
      tcp:
        - type: fragment
          settings:
            packets: tlshello
            length: 10-20`,
	}
	for _, typ := range []string{"vless", "trojan"} {
		for name, block := range valid {
			if errMsg := ValidateBytes([]byte(patchOracleProfile(finalmaskProxy(typ, "chrome", block)))); errMsg != "" {
				t.Errorf("%s %s: patched engine rejected a valid finalmask: %s", typ, name, errMsg)
			}
		}
	}

	xhttpH3 := `
  - name: node
    type: vless
    server: 1.2.3.4
    port: 443
    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
    tls: true
    servername: www.example.com
    network: xhttp
    alpn: [h3]
    xhttp-opts:
      path: /x
    finalmask:
      udp:
        - type: noise
          noise:
            - rand: 10-20
        - type: udphop
          interval: 30-60
`
	if errMsg := ValidateBytes([]byte(patchOracleProfile(xhttpH3))); errMsg != "" {
		t.Errorf("xhttp h3 with UDP masks rejected: %s", errMsg)
	}

	unusable := map[string]string{
		"udp on tcp": `    finalmask:
      udp:
        - type: noise
          noise:
            - rand: 8`,
		"unknown mask": `    finalmask:
      tcp:
        - type: no-such-mask`,
		"xicmp": `    finalmask:
      udp:
        - type: xicmp
          ips: [1.2.3.4]`,
	}
	for name, block := range unusable {
		if errMsg := ValidateBytes([]byte(patchOracleProfile(finalmaskProxy("vless", "chrome", block)))); errMsg != "" {
			t.Errorf("unusable finalmask %q failed the profile: %s", name, errMsg)
		}
	}
}
