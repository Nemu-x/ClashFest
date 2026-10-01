package snapshot

import (
	"os"
	P "path/filepath"
	"testing"
)

// Engine-oracle for the DNS & Hosts writer. WritePipelineOracleFixtureTest
// (Kotlin) emits testdata/dnshosts/original.yaml and edited.yaml, the latter
// re-written through DnsHostsYamlEdit from a model that includes a list host
// (`multi.example.com: [1.1.1.1, 2.2.2.2]`). mihomo must read both the same.
//
// Skips if fixtures are absent (run the Kotlin emitter first).
func TestDnsHostsEditOracle_SnapshotEquality(t *testing.T) {
	dir := P.Join("testdata", "dnshosts")
	original := P.Join(dir, "original.yaml")
	edited := P.Join(dir, "edited.yaml")
	if _, err := os.Stat(original); err != nil {
		t.Skip("dnshosts fixtures absent — run WritePipelineOracleFixtureTest (Kotlin) first")
	}
	base := snapshotJSON(t, original)
	got := snapshotJSON(t, edited)
	if got != base {
		t.Errorf("edited.yaml: snapshot differs from original\n  base = %s\n  got  = %s", base, got)
	}
}
