package useragent

import "testing"

func TestBuildWithCoreVersion(t *testing.T) {
	got := Build("1.1.1.Alpha", "1.19.32")
	want := "ClashMetaForAndroid/1.1.1.Alpha ClashFest/1.1.1 mihomo/1.19.32"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestBuildWithoutCoreVersionOmitsMihomoToken(t *testing.T) {
	got := Build("1.1.1.Alpha.debug", "")
	want := "ClashMetaForAndroid/1.1.1.Alpha.debug ClashFest/1.1.1"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

func TestBuildKeepsUpstreamTokenFirst(t *testing.T) {
	// Marzban / Remnawave match the first token to pick the clash-meta format.
	got := Build("1.1.1", "1.19.32")
	if got[:len("ClashMetaForAndroid/")] != "ClashMetaForAndroid/" {
		t.Fatalf("first token must stay ClashMetaForAndroid, got %q", got)
	}
}

func TestBuildNonSemverVersionNamePassesThrough(t *testing.T) {
	got := Build("unknown", "")
	want := "ClashMetaForAndroid/unknown ClashFest/unknown"
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
