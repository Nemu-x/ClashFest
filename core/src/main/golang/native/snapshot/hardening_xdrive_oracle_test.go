package snapshot

import (
	"os"
	P "path"
	"testing"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

// Engine oracle for YamlHardener's XDrive pass (fixtures written by the Kotlin
// WritePipelineOracleFixtureTest): a `service: local` node must load as REJECT
// under its original name, so the group that references it still resolves,
// while a cloud-storage xdrive node stays a working VLESS proxy.
func TestHardenedXDriveFixturesNeutraliseLocalStorage(t *testing.T) {
	for _, name := range []string{"xdrive_local_strict.yaml", "xdrive_local_compat.yaml"} {
		t.Run(name, func(t *testing.T) {
			data, err := os.ReadFile(P.Join("testdata", "hardening", name))
			if err != nil {
				t.Skip("xdrive hardening fixture absent — run WritePipelineOracleFixtureTest (Kotlin) first")
			}
			rawCfg, err := config.UnmarshalRawConfig(data)
			if err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			cfg, err := config.ParseRawConfig(rawCfg)
			if err != nil {
				t.Fatalf("engine rejected hardened config: %v", err)
			}
			defer closeProviders(cfg)

			want := map[string]C.AdapterType{
				"local-node": C.Reject,
				// Engine-equivalent key spellings (`xdrive_opts`, `XDrive-Opts`/`Service`).
				"snake-node": C.Reject,
				"caps-node":  C.Reject,
				"cloud-node": C.Vless,
				"G":          C.Selector,
			}
			for proxy, typ := range want {
				p, ok := cfg.Proxies[proxy]
				if !ok {
					t.Fatalf("%s missing after hardening", proxy)
				}
				if p.Type() != typ {
					t.Errorf("%s: type %s, want %s", proxy, p.Type(), typ)
				}
			}
			// `caps` spells its keys `Type:`/`Payload:`; the provider decoder still loads it.
			for providerName, node := range map[string]string{"inline": "provider-local", "caps": "provider-caps"} {
				provider, ok := cfg.Providers[providerName]
				if !ok {
					t.Fatalf("%s provider missing after hardening", providerName)
				}
				proxies := provider.Proxies()
				if len(proxies) != 1 || proxies[0].Name() != node || proxies[0].Type() != C.Reject {
					t.Errorf("%s payload not neutralised: %v", providerName, proxies)
				}
			}
			if len(cfg.Listeners) > 1 {
				t.Errorf("xdrive listener survived hardening: %d listeners", len(cfg.Listeners))
			}
		})
	}
}
