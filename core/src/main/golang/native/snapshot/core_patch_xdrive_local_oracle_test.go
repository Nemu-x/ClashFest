package snapshot

import (
	"context"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/transport/xdrive"
)

const xdriveEncryption = "mlkem768x25519plus.native.0rtt.XKbaq1BuzJKKKPxZbwgRpn-gYQbnhrYketgezASCL1I"

// Engine oracle for the Android guard in core patch 0003: with local storage
// refused (as on GOOS=android), a config with an xdrive `service: local` node
// still validates, so one bad node does not fail a whole subscription, but the
// node never touches the filesystem and every dial through it fails. YamlHardener
// only rewrites config.yaml and inline payloads; an http or file proxy-provider
// is parsed by the engine alone, so the engine has to defuse it itself.

func xdriveLocalNode(folder string) string {
	return `- name: local-node
  type: vless
  server: 127.0.0.1
  port: 443
  uuid: 27848739-7e62-4138-9fd3-098a63964b6b
  network: xdrive
  encryption: ` + xdriveEncryption + `
  udp: true
  xdrive-opts:
    service: local
    remote-folder: ` + filepath.ToSlash(folder) + `
`
}

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

// localFolder is a remote-folder that does not exist yet; the guard must
// leave it that way.
func localFolder(t *testing.T) string {
	t.Helper()
	return filepath.Join(t.TempDir(), "xdrive")
}

const wantLocalRefused = "xdrive service local (a folder on the device) is disabled in this build"

func parseProfile(t *testing.T, profile string) *config.Config {
	t.Helper()
	if errMsg := ValidateBytes([]byte(profile)); errMsg != "" {
		t.Fatalf("engine rejected a config with a refused local node: %s", errMsg)
	}
	rawCfg, err := config.UnmarshalRawConfig([]byte(profile))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(rawCfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { closeProviders(cfg) })
	return cfg
}

// assertLocalNodeDefused dials the node over TCP and UDP and checks that both
// fail with the guard's error and that nothing was created on disk.
func assertLocalNodeDefused(t *testing.T, proxy C.Proxy, folder string) {
	t.Helper()
	if proxy == nil {
		t.Fatal("refused local node missing from the loaded config")
	}
	if proxy.Type() != C.Vless {
		t.Fatalf("refused local node loaded as %v, want Vless", proxy.Type())
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	metadata := &C.Metadata{NetWork: C.TCP, Host: "example.com", DstPort: 443}
	if conn, err := proxy.DialContext(ctx, metadata); err == nil || !strings.Contains(err.Error(), wantLocalRefused) {
		if conn != nil {
			_ = conn.Close()
		}
		t.Fatalf("TCP dial: got %v, want the local-storage refusal", err)
	}
	udp := &C.Metadata{NetWork: C.UDP, DstIP: netip.MustParseAddr("1.1.1.1"), DstPort: 53}
	if pc, err := proxy.ListenPacketContext(ctx, udp); err == nil || !strings.Contains(err.Error(), wantLocalRefused) {
		if pc != nil {
			_ = pc.Close()
		}
		t.Fatalf("UDP dial: got %v, want the local-storage refusal", err)
	}
	if _, err := os.Stat(folder); !os.IsNotExist(err) {
		t.Fatalf("refused local node touched the filesystem at %s: %v", folder, err)
	}
}

func TestCorePatchOracle_XDriveLocalDefusedInProxies(t *testing.T) {
	refuseLocalXDrive(t)
	folder := localFolder(t)
	profile := "proxies:\n" + indent(xdriveLocalNode(folder), "  ") + `proxy-groups:
  - name: G
    type: select
    proxies: [local-node]
rules:
  - MATCH,G
`
	cfg := parseProfile(t, profile)
	assertLocalNodeDefused(t, cfg.Proxies["local-node"], folder)
}

func TestCorePatchOracle_XDriveLocalDefusedInInlineProvider(t *testing.T) {
	refuseLocalXDrive(t)
	folder := localFolder(t)
	profile := `proxy-providers:
  inline:
    type: inline
    health-check:
      enable: false
    payload:
` + indent(xdriveLocalNode(folder), "      ") + `proxy-groups:
  - name: G
    type: select
    use: [inline]
rules:
  - MATCH,G
`
	cfg := parseProfile(t, profile)
	provider := cfg.Providers["inline"]
	if err := provider.Initial(); err != nil {
		t.Fatalf("inline provider: %v", err)
	}
	proxies := provider.Proxies()
	if len(proxies) != 1 {
		t.Fatalf("inline provider loaded %d proxies, want 1", len(proxies))
	}
	assertLocalNodeDefused(t, proxies[0], folder)
}

// A file provider goes through the same parser as an http provider once the
// body is on disk, and neither passes through the app's hardener.
func TestCorePatchOracle_XDriveLocalDefusedInFileProvider(t *testing.T) {
	refuseLocalXDrive(t)
	folder := localFolder(t)
	// Loading the provider opens the engine's cache.db under the home dir and
	// keeps it open for the process, which t.TempDir cannot remove on Windows.
	home, err := os.MkdirTemp("", "xdrive-local-home")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(home) })
	savedHome := C.Path.HomeDir()
	C.SetHomeDir(home)
	t.Cleanup(func() { C.SetHomeDir(savedHome) })

	if err := os.WriteFile(filepath.Join(home, "local.yaml"), []byte("proxies:\n"+xdriveLocalNode(folder)), 0o600); err != nil {
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
	cfg := parseProfile(t, profile)
	provider := cfg.Providers["remote"]
	if err := provider.Initial(); err != nil {
		t.Fatalf("file provider must load a refused local node: %v", err)
	}
	proxies := provider.Proxies()
	if len(proxies) != 1 {
		t.Fatalf("file provider loaded %d proxies, want 1", len(proxies))
	}
	assertLocalNodeDefused(t, proxies[0], folder)
}
