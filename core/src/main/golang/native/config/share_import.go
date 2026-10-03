package config

import (
	"fmt"
	"net/url"
	"os"
	P "path"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/convert"
	"github.com/metacubex/mihomo/common/yaml"
)

func isInlineShareImport(raw string) bool {
	s := strings.TrimSpace(raw)
	if s == "" {
		return false
	}
	any := false
	for _, line := range strings.Split(s, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		any = true
		lower := strings.ToLower(line)
		if !strings.HasPrefix(lower, "mierus://") && !strings.HasPrefix(lower, "vless://") {
			return false
		}
	}
	return any
}

// renameDefaultMieruProfile replaces clash "name" derived from profile=default with "mieru"
// (and uniquifies if several links produced the same label).
func renameDefaultMieruProfile(proxies []map[string]any) {
	defaultCount := 0
	for i := range proxies {
		if proxies[i]["type"] != "mieru" {
			continue
		}
		n, ok := proxies[i]["name"].(string)
		if !ok {
			continue
		}
		if !strings.EqualFold(strings.TrimSpace(n), "default") {
			continue
		}
		defaultCount++
		if defaultCount == 1 {
			proxies[i]["name"] = "mieru"
		} else {
			proxies[i]["name"] = "mieru-" + strconv.Itoa(defaultCount)
		}
	}
}

func writeConfigFromShareLinks(configPath string, raw string) error {
	if !isInlineShareImport(raw) {
		return fmt.Errorf("unsupported share-link payload")
	}
	lines := make([]string, 0)
	expected := 0
	for i, line := range strings.Split(raw, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		// The upstream converter skips malformed nodes and logs their full links.
		// Validate its error cases first, without exposing credentials in errors/logs.
		u, err := url.Parse(line)
		if err != nil || u.Hostname() == "" || u.User == nil || u.User.Username() == "" {
			return fmt.Errorf("invalid share link on line %d", i+1)
		}
		query, err := url.ParseQuery(u.RawQuery)
		if err != nil {
			return fmt.Errorf("invalid share-link parameters on line %d", i+1)
		}
		if strings.EqualFold(u.Scheme, "vless") {
			port, err := strconv.Atoi(u.Port())
			if err != nil || port < 1 || port > 65535 {
				return fmt.Errorf("invalid share-link port on line %d", i+1)
			}
			if ed := query.Get("ed"); ed != "" {
				if _, err := strconv.Atoi(ed); err != nil {
					return fmt.Errorf("invalid share-link early data on line %d", i+1)
				}
			}
			if u.Fragment == "" {
				u.Fragment = u.Host
			}
			expected++
		} else {
			ports := query["port"]
			if len(ports) == 0 || len(ports) != len(query["protocol"]) {
				return fmt.Errorf("invalid mieru ports on line %d", i+1)
			}
			expected += len(ports)
		}
		lines = append(lines, u.String())
	}
	proxies, err := convert.ConvertsV2Ray([]byte(strings.Join(lines, "\n")))
	if err != nil {
		return fmt.Errorf("invalid share-link configuration")
	}
	if len(proxies) != expected {
		return fmt.Errorf("not all share links could be converted")
	}
	renameDefaultMieruProfile(proxies)
	names := make([]string, 0, len(proxies))
	group := "AUTO"
	used := map[string]bool{group: true, "DIRECT": true, "REJECT": true, "REJECT-DROP": true, "PASS": true, "COMPATIBLE": true}
	for _, p := range proxies {
		n, _ := p["name"].(string)
		if n == "" {
			return fmt.Errorf("converted proxy missing name")
		}
		base := n
		for suffix := 1; used[n]; suffix++ {
			n = base + "-" + strconv.Itoa(suffix)
		}
		used[n] = true
		p["name"] = n
		names = append(names, n)
	}
	if len(names) == 0 {
		return fmt.Errorf("converted proxies missing names")
	}
	doc := map[string]any{
		"mixed-port": 7890,
		"mode":       "rule",
		"log-level":  "info",
		"proxies":    proxies,
		"proxy-groups": []map[string]any{
			{
				"name":    group,
				"type":    "select",
				"proxies": names,
			},
		},
		"rules": []string{"MATCH," + group},
	}
	out, err := yaml.Marshal(doc)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(P.Dir(configPath), 0o700); err != nil {
		return err
	}
	return os.WriteFile(configPath, out, 0o600)
}

// Normalize fetched plaintext/base64 share lists after age decryption. YAML
// and other bodies are left byte-for-byte intact for the normal engine parser.
func convertShareLinksInPlace(configPath string) error {
	data, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	payload := strings.TrimSpace(string(data))
	if !isInlineShareImport(payload) {
		payload = strings.TrimSpace(string(convert.DecodeBase64([]byte(payload))))
		if !isInlineShareImport(payload) {
			return nil
		}
	}
	return writeConfigFromShareLinks(configPath, payload)
}
