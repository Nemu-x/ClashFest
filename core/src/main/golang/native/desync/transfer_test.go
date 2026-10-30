package desync

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

// transferRoutes parses fallback routes over desync proxies a and b, both
// alive for the groups' delay-test URL, and returns the proxies.
func transferRoutes(t *testing.T) map[string]C.Proxy {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(srv.Close)
	y := fmt.Sprintf(`
proxies:
  - {name: a, type: desync, tlsrec: 1+s}
  - {name: b, type: desync, tlsrec: 3+s}
proxy-groups:
  - {name: desync-discord, type: fallback, proxies: [a, b], url: %[1]q, interval: 600, lazy: true}
  - {name: plain, type: fallback, proxies: [a, b], url: %[1]q, interval: 600, lazy: true}
rules:
  - MATCH,DIRECT
`, srv.URL+"/")
	raw, err := config.UnmarshalRawConfig([]byte(y))
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.ParseRawConfig(raw)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	for _, name := range []string{"a", "b"} {
		if _, err := cfg.Proxies[name].URLTest(ctx, srv.URL+"/", nil); err != nil {
			t.Fatal(err)
		}
	}
	return cfg.Proxies
}

// fakeTransfer makes the transfer test pass only through the named proxies
// and records the requests.
type fakeTransfer struct {
	mu    sync.Mutex
	pass  map[string]bool
	calls []string
}

func (f *fakeTransfer) test(_ context.Context, p C.Proxy, target *url.URL, minBytes, _ int64, _ time.Duration) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls = append(f.calls, fmt.Sprintf("%s %s %d", p.Name(), target, minBytes))
	if f.pass[p.Name()] {
		return transferMaxBytes, nil
	}
	return 16 << 10, errors.New("transfer stalled after 16384 bytes")
}

func useFakeTransfer(t *testing.T, pass ...string) *fakeTransfer {
	f := &fakeTransfer{pass: map[string]bool{}}
	for _, name := range pass {
		f.pass[name] = true
	}
	old := transferTest
	transferTest = f.test
	transferVerdicts.Range(func(k, _ any) bool { transferVerdicts.Delete(k); return true })
	t.Cleanup(func() {
		transferTest = old
		transferVerdicts.Range(func(k, _ any) bool { transferVerdicts.Delete(k); return true })
	})
	return f
}

// routeAfterTransfer reads the status, waits for the background check of the
// route and reads it again.
func routeAfterTransfer(t *testing.T, proxies map[string]C.Proxy, name string) RouteStatus {
	t.Helper()
	collectStatus(proxies, nil)
	deadline := time.Now().Add(5 * time.Second)
	for {
		_, checked := transferVerdicts.Load(name)
		_, running := transferRunning.Load(name)
		if checked && !running {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("%s: no transfer verdict", name)
		}
		time.Sleep(10 * time.Millisecond)
	}
	for _, r := range collectStatus(proxies, nil).Routes {
		if r.Name == name {
			return r
		}
	}
	t.Fatalf("%s: no route", name)
	return RouteStatus{}
}

func TestTransferPinsTheCandidateThatCarriesThePage(t *testing.T) {
	proxies := transferRoutes(t)
	f := useFakeTransfer(t, "b")

	r := routeAfterTransfer(t, proxies, "desync-discord")
	if !r.Checked || !r.Working || r.Now != "b" {
		t.Fatalf("route = %+v, want b pinned and working", r)
	}
	want := []string{
		"a https://discord.com/login 32768",
		"b https://discord.com/login 32768",
	}
	if fmt.Sprint(f.calls) != fmt.Sprint(want) {
		t.Errorf("calls = %q, want %q", f.calls, want)
	}
}

func TestTransferFreezeMeansNeedsVPN(t *testing.T) {
	proxies := transferRoutes(t)
	useFakeTransfer(t)

	r := routeAfterTransfer(t, proxies, "desync-discord")
	if !r.Checked || r.Working {
		t.Fatalf("route = %+v, want checked and not working", r)
	}
}

func TestTransferSkipsPlainHTTPRoutes(t *testing.T) {
	proxies := transferRoutes(t)
	f := useFakeTransfer(t)

	for _, r := range collectStatus(proxies, nil).Routes {
		if r.Name == "plain" && !r.Working {
			t.Errorf("plain = %+v, want working", r)
		}
	}
	time.Sleep(50 * time.Millisecond)
	if _, ok := transferVerdicts.Load("plain"); ok {
		t.Errorf("plain was checked: %q", f.calls)
	}
}
