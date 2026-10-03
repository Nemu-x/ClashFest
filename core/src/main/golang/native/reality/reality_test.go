package reality

import "testing"

func TestParseVersion(t *testing.T) {
	for _, tc := range []struct {
		in   string
		want [3]byte
		ok   bool
	}{
		{"26.9.9", [3]byte{26, 9, 9}, true},
		{" v25.1.30 ", [3]byte{25, 1, 30}, true},
		{"1.8.2", [3]byte{1, 8, 2}, true},
		{"", [3]byte{}, false},
		{"26.9", [3]byte{}, false},
		{"26.9.9.1", [3]byte{}, false},
		{"26.x.9", [3]byte{}, false},
		{"300.0.0", [3]byte{}, false},
		{"-1.0.0", [3]byte{}, false},
	} {
		got, ok := ParseVersion(tc.in)
		if ok != tc.ok || got != tc.want {
			t.Errorf("ParseVersion(%q) = %v,%v want %v,%v", tc.in, got, ok, tc.want, tc.ok)
		}
	}
}

func TestFormatVersionRoundTrip(t *testing.T) {
	v, _ := ParseVersion("26.9.9")
	if FormatVersion(v) != "26.9.9" {
		t.Fatalf("FormatVersion = %q", FormatVersion(v))
	}
}

func TestMLKEMPolicyName(t *testing.T) {
	if MLKEMPolicyName(0) != "auto" || MLKEMPolicyName(1) != "on" || MLKEMPolicyName(2) != "off" || MLKEMPolicyName(7) != "auto" {
		t.Fatal("policy names drifted")
	}
}
