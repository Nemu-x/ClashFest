// Package useragent builds the User-Agent sent on subscription fetches.
//
// It is dependency-free on purpose: cfa/native/app pulls in Android-only
// platform helpers, so this package is what `go test` exercises on a
// developer workstation (see goTestNativeSnapshot in core/build.gradle.kts).
package useragent

import (
	"regexp"
	"strings"
)

// Panels choose the output format by matching the FIRST product token:
// Marzban / Remnawave serve the clash-meta format for
// `^(clash-verge|clash[-.]?meta|flclash|mihomo)` and fall back to the legacy
// clash format for anything else that starts with `clash` — which is exactly
// what `ClashFest/...` would hit. So the core token leads and the app token
// follows. mikan gates protocols on `(mihomo|clash[.-]?meta)/v?X.Y.Z` found
// anywhere in the UA.
const (
	core    = "mihomo"
	product = "ClashFest"
)

var semver = regexp.MustCompile(`^v?(\d+\.\d+\.\d+)`)
var releaseTag = regexp.MustCompile(`^v?(\d+\.\d+\.\d+)$`)

// CoreVersionFromTag turns the mihomo tag the core was built from ("v1.19.32")
// into the bare version advertised in the User-Agent ("1.19.32"). Anything
// that is not a release tag (a branch name, "unknown", an empty string from a
// checkout without tags) yields "", and Build then sends a bare `mihomo`
// token: a panel must never be told a version the core is not.
func CoreVersionFromTag(tag string) string {
	m := releaseTag.FindStringSubmatch(strings.TrimSpace(tag))
	if m == nil {
		return ""
	}
	return m[1]
}

// Build composes the default subscription User-Agent, e.g.
//
//	mihomo/1.19.32 ClashFest/1.1.1
//	mihomo/1.19.32 ClashFest/1.1.1 (xdrive)
//
// The ClashFest token carries the bare semver of versionName (flavour and
// build-type suffixes stripped) so it can be matched like the mihomo one.
// Without a known core version the first token is a bare `mihomo` (a product
// token without a version is valid UA grammar) — still the right format for
// every panel, never a made-up number.
//
// features are the optional core capabilities (cfa/native/features), sent as a
// trailing UA comment so panels can serve transports vanilla mihomo rejects.
// They never move the mihomo token off the first position.
func Build(versionName, coreVersion string, features ...string) string {
	var b strings.Builder
	b.WriteString(core)
	if coreVersion != "" {
		b.WriteByte('/')
		b.WriteString(coreVersion)
	}
	b.WriteByte(' ')
	b.WriteString(product)
	b.WriteByte('/')
	b.WriteString(appVersion(versionName))
	if comment := featureComment(features); comment != "" {
		b.WriteString(" (")
		b.WriteString(comment)
		b.WriteByte(')')
	}
	return b.String()
}

func appVersion(versionName string) string {
	if m := semver.FindStringSubmatch(versionName); m != nil {
		return m[1]
	}
	return versionName
}

func featureComment(features []string) string {
	tokens := make([]string, 0, len(features))
	for _, f := range features {
		if f = strings.TrimSpace(f); f != "" {
			tokens = append(tokens, f)
		}
	}
	return strings.Join(tokens, "; ")
}
