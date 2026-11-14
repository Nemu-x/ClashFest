package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for core patch 0031 (reality-opts.mlkem-client-fingerprint):
// a fingerprint with the X25519MLKEM768 key share is accepted. Patch 0040: one
// without it or an unknown name is ignored with a warning instead of failing
// the profile.

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
	for _, fp := range []string{"edge", "chrome120", "firefox120", "opera", "none"} {
		proxy := strings.Replace(realityProxyWithMLKEMFingerprint, "MLKEM_FINGERPRINT", fp, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
			t.Errorf("mlkem-client-fingerprint %s failed the profile: %s", fp, errMsg)
		}
	}
}
