package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patch 0031 (reality-opts.mlkem-client-fingerprint):
// a fingerprint with the X25519MLKEM768 key share is accepted, one without it
// or an unknown name fails validation.

const realityProxyWithMLKEMFingerprint = `
  - name: node
    type: vless
    server: 1.2.3.4
    port: 443
    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
    network: tcp
    tls: true
    servername: www.example.com
    client-fingerprint: chrome
    reality-opts:
      public-key: SuEmqx2lN3HPJCv0YBhmTc-B4MIlGiEfxv-gYTdkbAQ
      short-id: a1
      mlkem-client-fingerprint: MLKEM_FINGERPRINT
`

func TestCorePatchOracle_RealityMLKEMClientFingerprint(t *testing.T) {
	for _, fp := range []string{"firefox", "safari", "chrome", "chrome155", "HelloFirefox_148", "random", `""`} {
		proxy := strings.Replace(realityProxyWithMLKEMFingerprint, "MLKEM_FINGERPRINT", fp, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
			t.Errorf("mlkem-client-fingerprint %s rejected: %s", fp, errMsg)
		}
	}
	for fp, want := range map[string]string{
		"edge":       "has no X25519MLKEM768 key share",
		"chrome120":  "has no X25519MLKEM768 key share",
		"firefox120": "has no X25519MLKEM768 key share",
		"opera":      "invalid REALITY mlkem-client-fingerprint",
		"none":       "invalid REALITY mlkem-client-fingerprint",
	} {
		proxy := strings.Replace(realityProxyWithMLKEMFingerprint, "MLKEM_FINGERPRINT", fp, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); !strings.Contains(errMsg, want) {
			t.Errorf("mlkem-client-fingerprint %s: got %q, want %q", fp, errMsg, want)
		}
	}
}
