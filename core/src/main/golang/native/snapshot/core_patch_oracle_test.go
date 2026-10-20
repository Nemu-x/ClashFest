package snapshot

import (
	"strings"
	"testing"
)

// Engine oracle for the config surface added by core patches 0003 (VLESS over
// XDrive) and 0004 (per-proxy reality-opts.client-version): the patched engine
// must accept valid proxies and reject broken ones at validation time, so a
// bad subscription fails loudly on import instead of at dial time.
// Validation is offline: building an XDrive client contacts no storage.

const xdriveEncryption = "mlkem768x25519plus.native.0rtt.XKbaq1BuzJKKKPxZbwgRpn-gYQbnhrYketgezASCL1I"

func patchOracleProfile(proxy string) string {
	return `
proxies:
` + proxy + `
proxy-groups:
  - name: GLOBAL
    type: select
    proxies:
      - node
rules:
  - MATCH,GLOBAL
`
}

const xdriveTemplateProxy = `
  - name: node
    type: vless
    server: storage.example.com
    port: 443
    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
    network: xdrive
    encryption: ` + xdriveEncryption + `
    udp: true
    xdrive-opts:
      service: template
      remote-folder: tunnel
      fronting: front.example.com:443
      template:
        auth:
          type: static
          header:
            Authorization: Bearer dummy-token
        put:
          method: PUT
          url: https://storage.example.com/folder/{name}
        get:
          method: GET
          url: https://storage.example.com/folder/{name}
        delete:
          method: DELETE
          url: https://storage.example.com/folder/{name}
        list:
          method: PROPFIND
          url: https://storage.example.com/folder/
          namesRegex: "<d:href>/folder/([^<]+)</d:href>"
`

const xdriveGoogleDriveProxy = `
  - name: node
    type: vless
    server: www.googleapis.com
    port: 443
    uuid: 27848739-7e62-4138-9fd3-098a63964b6b
    network: xdrive
    encryption: ` + xdriveEncryption + `
    xdrive-opts:
      service: google-drive
      remote-folder: dummy-folder-id
      secrets:
        - dummy-client-id
        - dummy-client-secret
        - dummy-refresh-token
`

const realityProxyWithVersion = `
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
      client-version: CLIENT_VERSION
`

func TestCorePatchOracle_AcceptsXDrive(t *testing.T) {
	for name, proxy := range map[string]string{
		"template":     xdriveTemplateProxy,
		"google-drive": xdriveGoogleDriveProxy,
	} {
		t.Run(name, func(t *testing.T) {
			if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
				t.Fatalf("patched engine rejected a valid xdrive proxy: %s", errMsg)
			}
		})
	}
}

func TestCorePatchOracle_RejectsInvalidXDrive(t *testing.T) {
	for _, tc := range []struct {
		name, from, to, want string
	}{
		{"missing-opts", "    xdrive-opts:\n      service: google-drive\n      remote-folder: dummy-folder-id\n      secrets:\n        - dummy-client-id\n        - dummy-client-secret\n        - dummy-refresh-token\n", "", "xdrive requires xdrive-opts"},
		{"unknown-service", "service: google-drive", "service: dropbox", "unsupported xdrive service"},
		{"missing-secret", "        - dummy-refresh-token\n", "", "google-drive needs"},
		{"missing-folder", "      remote-folder: dummy-folder-id\n", "", "remote-folder"},
		{"no-encryption", "encryption: " + xdriveEncryption, "encryption: none", "requires VLESS encryption"},
		{"flow", "    network: xdrive\n", "    network: xdrive\n    flow: xtls-rprx-vision\n", "flow"},
		{"outer-tls", "    network: xdrive\n", "    network: xdrive\n    tls: true\n    reality-opts:\n      public-key: SuEmqx2lN3HPJCv0YBhmTc-B4MIlGiEfxv-gYTdkbAQ\n", "outer TLS"},
		{"smux", "    network: xdrive\n", "    network: xdrive\n    smux:\n      enabled: true\n", "smux"},
		{"bad-fronting", "      remote-folder: dummy-folder-id\n", "      remote-folder: dummy-folder-id\n      fronting: no-port\n", "fronting"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			proxy := strings.Replace(xdriveGoogleDriveProxy, tc.from, tc.to, 1)
			if proxy == xdriveGoogleDriveProxy {
				t.Fatalf("fixture edit %q did not apply", tc.from)
			}
			errMsg := ValidateBytes([]byte(patchOracleProfile(proxy)))
			if errMsg == "" {
				t.Fatal("patched engine accepted an invalid xdrive proxy")
			}
			if !strings.Contains(errMsg, tc.want) {
				t.Fatalf("error %q does not mention %q", errMsg, tc.want)
			}
		})
	}

	t.Run("bad-template", func(t *testing.T) {
		proxy := strings.Replace(xdriveTemplateProxy, `namesRegex: "<d:href>/folder/([^<]+)</d:href>"`, `namesRegex: "no-capture-group"`, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); !strings.Contains(errMsg, "capture group") {
			t.Fatalf("template without a capture group: got %q", errMsg)
		}
	})
}

func TestCorePatchOracle_RealityClientVersion(t *testing.T) {
	for _, v := range []string{"26.9.9", "1.8.2", `""`} {
		proxy := strings.Replace(realityProxyWithVersion, "CLIENT_VERSION", v, 1)
		if errMsg := ValidateBytes([]byte(patchOracleProfile(proxy))); errMsg != "" {
			t.Errorf("client-version %s rejected: %s", v, errMsg)
		}
	}
	for _, v := range []string{"26.9", "26.x.9", "300.0.0", "26.9.9.9"} {
		proxy := strings.Replace(realityProxyWithVersion, "CLIENT_VERSION", v, 1)
		errMsg := ValidateBytes([]byte(patchOracleProfile(proxy)))
		if !strings.Contains(errMsg, "invalid REALITY client version") {
			t.Errorf("client-version %s: got %q, want a REALITY client version error", v, errMsg)
		}
	}
}
