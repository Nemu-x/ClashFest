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

// Product token every subscription request starts with. Marzban / Remnawave
// pick the clash-meta output format by matching the FIRST token against
// `^(clash-verge|clash[-.]?meta|flclash|mihomo)`, so this must stay in front;
// anything else there makes a panel fall back to the legacy clash format.
const upstreamProduct = "ClashMetaForAndroid"

// Our own product token, and the token panels that gate protocol support on
// the core version look for (`(mihomo|clash[.-]?meta)/v?X.Y.Z`).
const (
	product = "ClashFest"
	core    = "mihomo"
)

var semver = regexp.MustCompile(`^v?(\d+\.\d+\.\d+)`)
var releaseTag = regexp.MustCompile(`^v?(\d+\.\d+\.\d+)$`)

// CoreVersionFromTag turns the mihomo tag the core was built from ("v1.19.32")
// into the bare version advertised in the User-Agent ("1.19.32"). Anything
// that is not a release tag (a branch name, "unknown", an empty string from a
// checkout without tags) yields "", and Build then omits the core token: a
// panel must never be told a version the core is not.
func CoreVersionFromTag(tag string) string {
	m := releaseTag.FindStringSubmatch(strings.TrimSpace(tag))
	if m == nil {
		return ""
	}
	return m[1]
}

// Build composes the default subscription User-Agent, e.g.
//
//	ClashMetaForAndroid/1.1.1.Alpha ClashFest/1.1.1 mihomo/1.19.32
//
// The ClashFest token carries the bare semver of versionName (flavour and
// build-type suffixes stripped) so it can be matched like the mihomo one; the
// mihomo token is only present when coreVersion is known.
func Build(versionName, coreVersion string) string {
	var b strings.Builder
	b.WriteString(upstreamProduct)
	b.WriteByte('/')
	b.WriteString(versionName)
	b.WriteByte(' ')
	b.WriteString(product)
	b.WriteByte('/')
	b.WriteString(appVersion(versionName))
	if coreVersion != "" {
		b.WriteByte(' ')
		b.WriteString(core)
		b.WriteByte('/')
		b.WriteString(coreVersion)
	}
	return b.String()
}

func appVersion(versionName string) string {
	if m := semver.FindStringSubmatch(versionName); m != nil {
		return m[1]
	}
	return versionName
}
