package snapshot

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"

	C "github.com/metacubex/mihomo/constant"
)

const persistentProfile = `
profile:
  store-selected: true
  store-fake-ip: true
dns:
  enable: true
  enhanced-mode: fake-ip
  fake-ip-range: 28.0.0.0/8
  nameserver: [1.1.1.1]
proxies:
  - {name: a, type: direct}
proxy-groups:
  - {name: pick, type: select, proxies: [a, DIRECT]}
rules:
  - MATCH,pick
`

// A read-only parse must not open the engine's cache file: its bbolt lock is
// exclusive and kept for the life of the process, so a UI process that opened
// it first left the core of the service process without fake-ip mappings.
// cachefile opens once per process, so the parse runs in a child process with
// a fresh home directory.
func TestReadOnlyParseLeavesCacheFileAlone(t *testing.T) {
	if home := os.Getenv("SNAPSHOT_READONLY_HOME"); home != "" {
		C.SetHomeDir(home)
		if msg := ValidateBytes([]byte(persistentProfile)); msg != "" {
			t.Fatalf("ValidateBytes: %s", msg)
		}
		if ResolvedGroupsJSON([]byte(persistentProfile)) == "" {
			t.Fatal("ResolvedGroupsJSON failed")
		}
		return
	}
	home := t.TempDir()
	cmd := exec.Command(os.Args[0], "-test.run=^TestReadOnlyParseLeavesCacheFileAlone$")
	cmd.Env = append(os.Environ(), "SNAPSHOT_READONLY_HOME="+home)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("child: %v\n%s", err, out)
	}
	if _, err := os.Stat(filepath.Join(home, "cache.db")); !os.IsNotExist(err) {
		t.Fatalf("cache.db was created by a read-only parse (stat: %v)", err)
	}
}
