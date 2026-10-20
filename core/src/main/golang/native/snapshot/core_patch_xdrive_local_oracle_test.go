package snapshot

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/transport/xdrive"
)

// Engine oracle for the Android guard in core patch 0003: with local storage
// refused (as on GOOS=android), an xdrive `service: local` node fails when the
// proxy is built, wherever it comes from. YamlHardener only rewrites config.yaml
// and inline payloads; an http or file proxy-provider is parsed by the engine
// alone, so the engine has to refuse it itself.

const xdriveLocalNode = `- name: local-node
  type: vless
  server: 127.0.0.1
  port: 443
  uuid: 27848739-7e62-4138-9fd3-098a63964b6b
  network: xdrive
  encryption: ` + xdriveEncryption + `
  xdrive-opts:
    service: local
    remote-folder: /data/local/tmp/xdrive
`

func indent(text, prefix string) string {
	lines := strings.Split(strings.TrimRight(text, "\n"), "\n")
	for i, l := range lines {
		lines[i] = prefix + l
	}
	return strings.Join(lines, "\n") + "\n"
}

func refuseLocalXDrive(t *testing.T) {
	t.Helper()
	saved := xdrive.AllowLocalService
	xdrive.AllowLocalService = false
	t.Cleanup(func() { xdrive.AllowLocalService = saved })
}

const wantLocalRefused = "xdrive service local"

func TestCorePatchOracle_XDriveLocalRefusedInProxies(t *testing.T) {
	refuseLocalXDrive(t)
	profile := "proxies:\n" + indent(xdriveLocalNode, "  ") + `proxy-groups:
  - name: G
    type: select
    proxies: [local-node]
rules:
  - MATCH,G
`
	if errMsg := ValidateBytes([]byte(profile)); !strings.Contains(errMsg, wantLocalRefused) {
		t.Fatalf("config.yaml node: got %q, want the local-storage refusal", errMsg)
	}
}

func TestCorePatchOracle_XDriveLocalRefusedInInlineProvider(t *testing.T) {
	refuseLocalXDrive(t)
	profile := `proxy-providers:
  inline:
    type: inline
    payload:
` + indent(xdriveLocalNode, "      ") + `proxy-groups:
  - name: G
    type: select
    use: [inline]
rules:
  - MATCH,G
`
	if errMsg := ValidateBytes([]byte(profile)); !strings.Contains(errMsg, wantLocalRefused) {
		t.Fatalf("inline provider: got %q, want the local-storage refusal", errMsg)
	}
}

// A file provider goes through the same parser as an http provider once the
// body is on disk, and neither passes through the app's hardener.
func TestCorePatchOracle_XDriveLocalRefusedInFileProvider(t *testing.T) {
	refuseLocalXDrive(t)
	home := t.TempDir()
	savedHome := C.Path.HomeDir()
	C.SetHomeDir(home)
	t.Cleanup(func() { C.SetHomeDir(savedHome) })

	if err := os.WriteFile(filepath.Join(home, "local.yaml"), []byte("proxies:\n"+xdriveLocalNode), 0o600); err != nil {
		t.Fatal(err)
	}
	profile := `proxy-providers:
  remote:
    type: file
    path: ./local.yaml
    health-check:
      enable: false
proxy-groups:
  - name: G
    type: select
    use: [remote]
rules:
  - MATCH,G
`
	rawCfg, err := config.UnmarshalRawConfig([]byte(profile))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatalf("a file provider is loaded at runtime, not at validation: %v", err)
	}
	defer closeProviders(cfg)
	err = cfg.Providers["remote"].Initial()
	if err == nil || !strings.Contains(err.Error(), wantLocalRefused) {
		t.Fatalf("file provider: got %v, want the local-storage refusal", err)
	}
	if n := len(cfg.Providers["remote"].Proxies()); n != 0 {
		t.Fatalf("file provider loaded %d proxies from a refused body", n)
	}
}
