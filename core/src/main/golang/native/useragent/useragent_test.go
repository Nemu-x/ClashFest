package useragent

import (
	"regexp"
	"testing"
)

func TestBuildWithCoreVersion(t *testing.T) {
	got := Build("1.1.1.Alpha", "1.19.32")
	want := "mihomo/1.19.32 Mikan/1.1.1"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestBuildWithoutCoreVersionSendsBareMihomoToken(t *testing.T) {
	got := Build("1.1.1.Alpha.debug", "")
	want := "mihomo Mikan/1.1.1"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

// The panel-side format pickers this UA has to satisfy.
var (
	marzbanClashMeta = regexp.MustCompile(`^([Cc]lash-verge|[Cc]lash[-.]?[Mm]eta|[Ff][Ll][Cc]lash|[Mm]ihomo)`)
	marzbanLegacy    = regexp.MustCompile(`^([Cc]lash|[Ss]tash)`)
	mikanCore        = regexp.MustCompile(`(mihomo|clash[.-]?meta)/v?(\d+\.\d+\.\d+)`)
)

func TestBuildMatchesPanelFormatPickers(t *testing.T) {
	for _, ua := range []string{Build("1.1.1", "1.19.32"), Build("1.1.1.Alpha", "")} {
		if !marzbanClashMeta.MatchString(ua) {
			t.Errorf("%q must select the clash-meta format on Marzban/Remnawave", ua)
		}
		if marzbanLegacy.MatchString(ua) {
			t.Errorf("%q must not fall into the legacy clash branch", ua)
		}
	}
	if m := mikanCore.FindStringSubmatch(Build("1.1.1", "1.19.32")); m == nil || m[2] != "1.19.32" {
		t.Errorf("mikan must read the core version from %q", Build("1.1.1", "1.19.32"))
	}
	if mikanCore.MatchString(Build("1.1.1", "")) {
		t.Errorf("no core version known: mikan must not see one in %q", Build("1.1.1", ""))
	}
}

func TestBuildNonSemverVersionNamePassesThrough(t *testing.T) {
	got := Build("unknown", "1.19.32")
	want := "mihomo/1.19.32 Mikan/unknown"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestCoreVersionFromTag(t *testing.T) {
	cases := map[string]string{
		"v1.19.32":        "1.19.32",
		"1.19.32":         "1.19.32",
		" v1.19.32\n":     "1.19.32",
		"":                "",
		"unknown":         "",
		"Alpha":           "",
		"v1.19.32-3-gabc": "", // describe output off a tag is not a release
		"a5fd5dd":         "",
	}
	for tag, want := range cases {
		if got := CoreVersionFromTag(tag); got != want {
			t.Errorf("CoreVersionFromTag(%q) = %q, want %q", tag, got, want)
		}
	}
}
