package desync

import (
	"regexp"
	"testing"
)

func TestServicesTable(t *testing.T) {
	id := regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)
	ids := map[string]bool{}
	owner := map[string]string{}
	for _, s := range Services() {
		if !id.MatchString(s.ID) {
			t.Errorf("service id %q is not a lowercase token", s.ID)
		}
		if ids[s.ID] {
			t.Errorf("service %q listed twice", s.ID)
		}
		ids[s.ID] = true
		if s.Name == "" || len(s.Domains) == 0 {
			t.Errorf("service %q needs a name and domains", s.ID)
		}
		for _, d := range s.Domains {
			n, err := normalizeDomain(d)
			if err != nil || n != d {
				t.Errorf("service %q: domain %q is not a bare lower-case host (%q, %v)", s.ID, d, n, err)
			}
			if o, dup := owner[d]; dup {
				t.Errorf("domain %q is in %q and %q", d, o, s.ID)
			}
			owner[d] = s.ID
		}
	}
	for _, want := range []string{"youtube", "discord"} {
		if !ids[want] {
			t.Errorf("service %q missing", want)
		}
	}
	if owner["googlevideo.com"] != "youtube" || owner["discord.gg"] != "discord" {
		t.Errorf("core domains missing: googlevideo.com -> %q, discord.gg -> %q", owner["googlevideo.com"], owner["discord.gg"])
	}
}

func TestServicesReturnsCopy(t *testing.T) {
	s := Services()
	s[0].Domains[0] = "changed.example"
	if Services()[0].Domains[0] == "changed.example" {
		t.Fatal("Services() exposes the table")
	}
}

func TestNormalizeDomain(t *testing.T) {
	for in, want := range map[string]string{
		"Example.COM":                  "example.com",
		" *.rutracker.org ":            "rutracker.org",
		".cdn.example.net.":            "cdn.example.net",
		"https://www.example.org/path": "www.example.org",
		"example.com:443":              "example.com",
		"xn--80ak6aa92e.com":           "xn--80ak6aa92e.com",
	} {
		if got, err := normalizeDomain(in); err != nil || got != want {
			t.Errorf("normalizeDomain(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, bad := range []string{"", "localhost", "a,b.com", "exa mple.com", "-bad.com", "foo..com", "DOMAIN-SUFFIX,x.com,DIRECT"} {
		if got, err := normalizeDomain(bad); err == nil {
			t.Errorf("normalizeDomain(%q) = %q, want an error", bad, got)
		}
	}
}
