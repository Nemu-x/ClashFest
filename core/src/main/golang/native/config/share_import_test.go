package config

import (
	"bytes"
	"cfa/native/snapshot"
	"encoding/base64"
	"os"
	"path/filepath"
	"strings"
	"testing"

	mihomoConfig "github.com/metacubex/mihomo/config"
)

const testVlessLink = "vless://00000000-0000-0000-0000-000000000001@vpn.example:443?encryption=none&security=tls&sni=vpn.example&type=tcp"

func TestShareImportAcceptsInlineVless(t *testing.T) {
	if !isInlineShareImport(testVlessLink) {
		t.Fatal("VLESS cannot enter the inline import path")
	}
}

func TestShareImportVlessEngineOracle(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.yaml")
	if err := writeConfigFromShareLinks(path, testVlessLink); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if verdict := snapshot.ValidateBytes(data); verdict != "" {
		t.Fatalf("engine rejected converted VLESS: %s", verdict)
	}
	raw, err := mihomoConfig.UnmarshalRawConfig(data)
	if err != nil {
		t.Fatal(err)
	}
	p := raw.Proxy[0]
	if p["type"] != "vless" || p["server"] != "vpn.example" || p["tls"] != true || p["servername"] != "vpn.example" {
		t.Fatal("VLESS endpoint or TLS parameters changed")
	}
}

func TestShareImportRejectsPartialConversion(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.yaml")
	err := writeConfigFromShareLinks(path, testVlessLink+"#valid\nvless://invalid")
	if err == nil {
		t.Fatal("malformed node was silently dropped")
	}
	if strings.Contains(err.Error(), "vless://") {
		t.Fatal("error exposes a share link")
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("failed conversion wrote a partial configuration")
	}
}

func TestShareImportFetchedPayloads(t *testing.T) {
	list := "  " + testVlessLink + "#One\r\n\n" + testVlessLink + "#Two  \n"
	for name, payload := range map[string]string{
		"plain":           list,
		"base64":          base64.StdEncoding.EncodeToString([]byte(list)),
		"base64-unpadded": base64.RawStdEncoding.EncodeToString([]byte(list)),
	} {
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "config.yaml")
			if err := os.WriteFile(path, []byte(payload), 0600); err != nil {
				t.Fatal(err)
			}
			if err := convertShareLinksInPlace(path); err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if verdict := snapshot.ValidateBytes(data); verdict != "" {
				t.Fatal(verdict)
			}
			raw, err := mihomoConfig.UnmarshalRawConfig(data)
			if err != nil {
				t.Fatal(err)
			}
			if len(raw.Proxy) != 2 {
				t.Fatal("subscription nodes were lost")
			}
			if err := convertShareLinksInPlace(path); err != nil {
				t.Fatal(err)
			}
			again, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(data, again) {
				t.Fatal("revalidation changed converted YAML")
			}
		})
	}
}

func TestShareImportPreservesYamlAndUnknownBodies(t *testing.T) {
	for _, body := range []string{
		"# vless:// is only a comment\r\nproxies: []\r\nrules: [MATCH,DIRECT]\r\n",
		"<!doctype html>Subscription unavailable",
		"configuration error",
		"",
	} {
		path := filepath.Join(t.TempDir(), "config.yaml")
		if err := os.WriteFile(path, []byte(body), 0600); err != nil {
			t.Fatal(err)
		}
		if err := convertShareLinksInPlace(path); err != nil {
			t.Fatal(err)
		}
		data, err := os.ReadFile(path)
		if err != nil || string(data) != body {
			t.Fatal("non-share payload was rewritten")
		}
	}
}

func TestShareImportTransportsEngineOracle(t *testing.T) {
	for name, suffix := range map[string]string{
		"ws":      "&type=ws&path=%2Fsocket&host=front.example#WebSocket",
		"grpc":    "&type=grpc&serviceName=mikan#GRPC",
		"reality": "&type=tcp&security=reality&flow=xtls-rprx-vision&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=0123456789abcdef#REALITY",
	} {
		t.Run(name, func(t *testing.T) {
			// Avoid duplicate query keys: the converter uses the first value.
			link := "vless://00000000-0000-0000-0000-000000000001@vpn.example:443?encryption=none&sni=vpn.example"
			if name != "reality" {
				link += "&security=tls"
			}
			path := filepath.Join(t.TempDir(), "config.yaml")
			if err := writeConfigFromShareLinks(path, link+suffix); err != nil {
				t.Fatal(err)
			}
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatal(err)
			}
			if verdict := snapshot.ValidateBytes(data); verdict != "" {
				t.Fatal(verdict)
			}
			raw, err := mihomoConfig.UnmarshalRawConfig(data)
			if err != nil {
				t.Fatal(err)
			}
			p := raw.Proxy[0]
			if p["tls"] != true || p["servername"] != "vpn.example" {
				t.Fatal("TLS parameters lost")
			}
			switch name {
			case "ws":
				opts := p["ws-opts"].(map[string]any)
				if p["network"] != "ws" || opts["path"] != "/socket" || opts["headers"].(map[string]any)["Host"] != "front.example" {
					t.Fatal("WS settings lost")
				}
			case "grpc":
				if p["network"] != "grpc" || p["grpc-opts"].(map[string]any)["grpc-service-name"] != "mikan" {
					t.Fatal("gRPC settings lost")
				}
			case "reality":
				opts := p["reality-opts"].(map[string]any)
				if opts["public-key"] != "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" || opts["short-id"] != "0123456789abcdef" || p["flow"] != "xtls-rprx-vision" {
					t.Fatal("REALITY settings lost")
				}
			}
		})
	}
}

func TestShareImportNamesAndMieruEngineOracle(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.yaml")
	links := testVlessLink + "#AUTO\n" + testVlessLink + "#DIRECT\n" + testVlessLink + "#DIRECT\n" + testVlessLink + "#DIRECT-01\n" +
		"mierus://user:password@vpn.example?port=443&protocol=TCP&port=444&protocol=UDP&profile=default"
	if err := writeConfigFromShareLinks(path, links); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if verdict := snapshot.ValidateBytes(data); verdict != "" {
		t.Fatal(verdict)
	}
	raw, err := mihomoConfig.UnmarshalRawConfig(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(raw.Proxy) != 6 {
		t.Fatal("Mieru ports or VLESS nodes lost")
	}
	seen := map[string]bool{"AUTO": true, "DIRECT": true}
	for _, p := range raw.Proxy {
		name := p["name"].(string)
		if seen[name] {
			t.Fatal("node names collide with nodes/groups/builtins")
		}
		seen[name] = true
	}
}

func TestShareImportInvalidLinksLeaveFileUnchanged(t *testing.T) {
	for _, invalid := range []string{
		"vless://missing-user.example:443",
		"vless://id@vpn.example:0",
		"vless://id@vpn.example:65536",
		"vless://id@vpn.example:443?ed=invalid&type=ws",
		"vless://id@vpn.example:443?path=%zz",
		"mierus://user:password@vpn.example?port=invalid&protocol=TCP",
		"mierus://user:password@vpn.example?port=443",
	} {
		path := filepath.Join(t.TempDir(), "config.yaml")
		original := testVlessLink + "#valid\n" + invalid
		if err := os.WriteFile(path, []byte(original), 0600); err != nil {
			t.Fatal(err)
		}
		if err := convertShareLinksInPlace(path); err == nil {
			t.Fatal("invalid share list accepted")
		}
		data, err := os.ReadFile(path)
		if err != nil || string(data) != original {
			t.Fatal("failed import changed file")
		}
	}
}
