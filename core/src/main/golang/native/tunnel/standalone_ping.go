package tunnel

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"time"

	"github.com/metacubex/http"
	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/ca"
	C "github.com/metacubex/mihomo/constant"
)

// standalonePingTimeout bounds the whole measurement: dial through the proxy, TLS, response headers.
const standalonePingTimeout = 10 * time.Second

// URLTestProxyYAML measures one proxy without a loaded profile and without the VPN: the proxy is
// built from its own YAML block, a GET goes to testURL through it, and the time to the response
// headers is returned in ms. The adapter is closed afterwards, nothing is registered in the tunnel.
// A blank testURL uses the stock health-check endpoint.
func URLTestProxyYAML(proxyYAML string, testURL string) (int, error) {
	if testURL == "" {
		testURL = defaultHealthCheckURL
	}

	var mapping map[string]any
	if err := yaml.Unmarshal([]byte(proxyYAML), &mapping); err != nil {
		return 0, fmt.Errorf("proxy yaml: %w", err)
	}
	proxy, err := adapter.ParseProxy(mapping)
	if err != nil {
		return 0, err
	}
	defer func() { _ = proxy.Close() }()

	u, err := url.Parse(testURL)
	if err != nil {
		return 0, err
	}
	port := u.Port()
	if port == "" {
		switch u.Scheme {
		case "https":
			port = "443"
		case "http":
			port = "80"
		default:
			return 0, fmt.Errorf("unsupported test url scheme %q", u.Scheme)
		}
	}
	var metadata C.Metadata
	if err := metadata.SetRemoteAddress(net.JoinHostPort(u.Hostname(), port)); err != nil {
		return 0, err
	}

	ctx, cancel := context.WithTimeout(context.Background(), standalonePingTimeout)
	defer cancel()

	start := time.Now()
	conn, err := proxy.DialContext(ctx, &metadata)
	if err != nil {
		return 0, err
	}
	defer func() { _ = conn.Close() }()

	tlsConfig, err := ca.GetTLSConfig(ca.Option{})
	if err != nil {
		return 0, err
	}
	used := false
	transport := &http.Transport{
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			// One dial through the proxy per measurement; a second one means a redirect
			// to another host, which the client below does not follow anyway.
			if used {
				return nil, errors.New("standalone ping: connection already used")
			}
			used = true
			return conn, nil
		},
		TLSClientConfig:     tlsConfig,
		TLSHandshakeTimeout: standalonePingTimeout,
		DisableKeepAlives:   true,
	}
	defer transport.CloseIdleConnections()
	client := http.Client{
		Transport: transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, testURL, nil)
	if err != nil {
		return 0, err
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	elapsed := time.Since(start)
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
	_ = resp.Body.Close()

	ms := int(elapsed.Milliseconds())
	if ms < 1 {
		ms = 1
	}
	return ms, nil
}
