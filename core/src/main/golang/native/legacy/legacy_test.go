package legacy

import (
	"testing"

	"github.com/metacubex/mihomo/config"
	_ "github.com/metacubex/mihomo/hub/executor"
)

const sample = `
proxies:
  - {name: vless-ok, type: vless, server: 192.0.2.1, port: 443, uuid: 2b2c6b5e-3f9a-4b8e-9d3c-1a2b3c4d5e6f}
  - {name: vmess-old, type: vmess, server: 192.0.2.2, port: 443, uuid: 2b2c6b5e-3f9a-4b8e-9d3c-1a2b3c4d5e6f, alterId: 0, cipher: auto}
  - {name: via-vmess, type: vless, server: 192.0.2.3, port: 443, uuid: 2b2c6b5e-3f9a-4b8e-9d3c-1a2b3c4d5e6f, dialer-proxy: vmess-old}
  - {name: ss-ok, type: ss, server: 192.0.2.4, port: 8388, cipher: aes-128-gcm, password: x}
proxy-groups:
  - {name: Main, type: select, proxies: [vless-ok, vmess-old, ss-ok]}
  - {name: OnlyOld, type: select, proxies: [vmess-old, via-vmess]}
rules:
  - MATCH,Main
`

func TestDropKeepsTheConfigLoadable(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(sample))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := config.ParseRawConfig(raw); err == nil {
		t.Fatal("expected Citrus to reject the VMess node before Drop")
	}

	raw, _ = config.UnmarshalRawConfig([]byte(sample))
	names := Drop(raw)
	if len(names) != 2 || names[0] != "vmess-old" || names[1] != "via-vmess" {
		t.Fatalf("dropped %v, want the VMess node and the node dialing through it", names)
	}
	if _, err := config.ParseRawConfig(raw); err != nil {
		t.Fatalf("config still fails after Drop: %v", err)
	}
	var only []any
	for _, group := range raw.ProxyGroup {
		if group["name"] == "OnlyOld" {
			only, _ = group["proxies"].([]any)
			break
		}
	}
	if len(only) != 1 || only[0] != "REJECT" {
		t.Fatalf("an emptied group must block, got %v", only)
	}
}

func TestDropLeavesSupportedConfigsAlone(t *testing.T) {
	raw, _ := config.UnmarshalRawConfig([]byte(`
proxies:
  - {name: a, type: vless, server: 192.0.2.1, port: 443, uuid: 2b2c6b5e-3f9a-4b8e-9d3c-1a2b3c4d5e6f}
proxy-groups:
  - {name: G, type: select, proxies: [a]}
rules:
  - MATCH,G
`))
	if names := Drop(raw); names != nil {
		t.Fatalf("dropped %v from a supported config", names)
	}
}
