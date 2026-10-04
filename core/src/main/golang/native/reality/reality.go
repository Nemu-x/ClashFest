// Package reality holds the bridge-side helpers for the REALITY policy that
// the Kotlin settings push into the core (see docs/core-patches.md: patches
// 0001 and 0002 expose tlsC.RealityClientVersion and SetRealityMLKEMPolicy).
package reality

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseVersion parses an Xray-style "x.y.z" into the three bytes the REALITY
// ClientHello carries. Anything else (empty, "v" prefix aside, more or fewer
// parts, values outside 0..255) is rejected so a typo never becomes 0.0.0.
func ParseVersion(s string) ([3]byte, bool) {
	s = strings.TrimPrefix(strings.TrimSpace(s), "v")
	parts := strings.Split(s, ".")
	if len(parts) != 3 {
		return [3]byte{}, false
	}
	var out [3]byte
	for i, part := range parts {
		n, err := strconv.Atoi(part)
		if err != nil || n < 0 || n > 255 {
			return [3]byte{}, false
		}
		out[i] = byte(n)
	}
	return out, true
}

// FormatVersion is the inverse of ParseVersion, for logs.
func FormatVersion(v [3]byte) string {
	return fmt.Sprintf("%d.%d.%d", v[0], v[1], v[2])
}

// MLKEMPolicyName maps the integer the bridge receives (0 auto, 1 on, 2 off)
// to a stable log token; unknown values read as auto.
func MLKEMPolicyName(policy int) string {
	switch policy {
	case 1:
		return "on"
	case 2:
		return "off"
	default:
		return "auto"
	}
}
