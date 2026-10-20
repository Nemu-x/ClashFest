// Package legacy drops the proxies the Citrus core no longer supports (VMess, ShadowsocksR,
// Hysteria v1, TUIC v4, Shadowsocks stream ciphers, v2ray-plugin) from a raw config before it is
// parsed. Citrus already skips them inside proxy-providers, but a top-level `proxies:` entry
// fails the whole config, and most panels serve their nodes there. One legacy node must not
// leave a user without a working subscription.
package legacy

import (
	"errors"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

// MarkerFile is written next to config.yaml with the number of dropped proxies, so the app can
// tell the user after an update. Absent when nothing was dropped.
const MarkerFile = "removed-protocol-skipped"

// candidates are the proxy types that can carry a removed protocol or option; anything else is
// left to the normal parse so one probe per node is only paid where it can matter.
var candidates = map[string]bool{"vmess": true, "ssr": true, "ss": true, "hysteria": true, "tuic": true}

// isRemoved reports whether Citrus rejects this proxy because its protocol or option was removed.
func isRemoved(mapping map[string]any) bool {
	typ, _ := mapping["type"].(string)
	if !candidates[strings.ToLower(typ)] {
		return false
	}
	proxy, err := adapter.ParseProxy(mapping)
	if proxy != nil {
		_ = proxy.Close()
	}
	return errors.Is(err, C.ErrRemovedProtocol)
}

// Drop removes unsupported proxies from raw, along with the proxies that dial through them
// (dropping only the hop would send that traffic out directly), and prunes their names from
// proxy groups. A group left empty becomes REJECT, never DIRECT: DIRECT would leak the real IP
// for traffic the user routed through a proxy. Returns the dropped names in config order.
func Drop(raw *config.RawConfig) []string {
	removed := map[string]bool{}
	for _, mapping := range raw.Proxy {
		if name, _ := mapping["name"].(string); name != "" && isRemoved(mapping) {
			removed[name] = true
		}
	}
	if len(removed) == 0 {
		return nil
	}
	for changed := true; changed; {
		changed = false
		for _, mapping := range raw.Proxy {
			name, _ := mapping["name"].(string)
			via, _ := mapping["dialer-proxy"].(string)
			if name != "" && !removed[name] && removed[via] {
				removed[name] = true
				changed = true
			}
		}
	}

	var names []string
	kept := raw.Proxy[:0]
	for _, mapping := range raw.Proxy {
		if name, _ := mapping["name"].(string); removed[name] {
			names = append(names, name)
			continue
		}
		kept = append(kept, mapping)
	}
	raw.Proxy = kept

	for _, group := range raw.ProxyGroup {
		members, ok := group["proxies"].([]any)
		if !ok {
			continue
		}
		left := make([]any, 0, len(members))
		for _, member := range members {
			if name, _ := member.(string); removed[name] {
				continue
			}
			left = append(left, member)
		}
		if len(left) == len(members) {
			continue
		}
		_, usesProviders := group["use"]
		includesAll, _ := group["include-all"].(bool)
		includesProxies, _ := group["include-all-proxies"].(bool)
		if len(left) == 0 && !usesProviders && !includesAll && !includesProxies {
			left = append(left, "REJECT")
		}
		group["proxies"] = left
	}

	log.Warnln("[Citrus] skipped %d proxies that use a removed protocol or option (VMess, SSR, Hysteria v1, TUIC v4, Shadowsocks stream ciphers, v2ray-plugin)", len(names))
	return names
}

// WriteMarker records how many proxies Drop removed in profileDir, or clears the marker.
func WriteMarker(profileDir string, dropped int) {
	path := filepath.Join(profileDir, MarkerFile)
	if dropped == 0 {
		_ = os.Remove(path)
		return
	}
	_ = os.WriteFile(path, []byte(strconv.Itoa(dropped)), 0o600)
}
