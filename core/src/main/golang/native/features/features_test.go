package features

import (
	"regexp"
	"testing"
)

func TestListIsWellFormed(t *testing.T) {
	token := regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)
	seen := map[string]bool{}
	for _, f := range List() {
		if !token.MatchString(f) {
			t.Errorf("feature %q is not a lowercase token", f)
		}
		if seen[f] {
			t.Errorf("feature %q listed twice", f)
		}
		seen[f] = true
	}
	if !seen["xdrive"] {
		t.Fatal("xdrive missing although core patch 0003 is compiled in")
	}
	if !seen["chain"] {
		t.Fatal("chain missing although core patch 0005 is compiled in")
	}
	for _, f := range []string{"mldsa65", "pin-list", "ech-xray"} {
		if !seen[f] {
			t.Fatalf("%s missing although its core patch is compiled in", f)
		}
	}
}

func TestString(t *testing.T) {
	if got := String(); got != "xdrive,chain,mldsa65,pin-list,ech-xray" {
		t.Fatalf("String() = %q", got)
	}
}
