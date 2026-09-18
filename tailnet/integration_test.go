package mobile

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"
)

// TestProxyAgainstTheRealServer drives the loopback proxy against a live DSH
// entry, over the system dialer (no node involved, which is exactly the branch a
// test host can exercise).
//
// Why it exists: every other proxy test runs against a mock upstream, so the two
// properties that actually decide whether the phone's page renders are never
// checked together against the real thing --
//
//  1. the Host rewrite. The entry mints its session cookie only when the Host
//     header is the authority it expects, and it answers a bare GET / with 303 +
//     Set-Cookie. Our proxy has to rewrite the header and hand the redirect back
//     to the client rather than following it.
//  2. the live channel is Server-Sent Events, so it must pass through without
//     being buffered.
//
// Opt-in on purpose, and by environment variable rather than a constant: the
// target is a private tailnet address, and this repository is public. Set
// TAILNET_INTEGRATION_TARGET (for example http://100.101.102.103:3080) to run it;
// without it the test skips, which is what happens on a normal `go test ./...`.
func TestProxyAgainstTheRealServer(t *testing.T) {
	target := strings.TrimSpace(os.Getenv("TAILNET_INTEGRATION_TARGET"))
	if target == "" {
		t.Skip("set TAILNET_INTEGRATION_TARGET to run this against a live entry")
	}

	// Reachability guard: this test is about our proxy, not about whether the
	// other end happens to be up right now.
	u, err := url.Parse(target)
	if err != nil {
		t.Fatalf("TAILNET_INTEGRATION_TARGET is not a URL: %v", err)
	}
	host := u.Host
	if u.Port() == "" {
		if u.Scheme == "https" {
			host = net.JoinHostPort(u.Hostname(), "443")
		} else {
			host = net.JoinHostPort(u.Hostname(), "80")
		}
	}
	conn, err := net.DialTimeout("tcp", host, 4*time.Second)
	if err != nil {
		t.Skipf("entry not reachable from here (%v); skipping", err)
	}
	conn.Close()

	allowTarget(t, target)
	doc := StartProxy(target)
	defer StopProxy()

	var started struct {
		Running bool   `json:"running"`
		URL     string `json:"url"`
		Error   string `json:"error"`
	}
	if err := json.Unmarshal([]byte(doc), &started); err != nil {
		t.Fatalf("StartProxy returned something that is not JSON (%s): %v", doc, err)
	}
	if !started.Running || started.URL == "" {
		t.Fatalf("proxy did not start: %s", started.Error)
	}

	entry, err := url.Parse(started.URL)
	if err != nil {
		t.Fatalf("proxy URL unparseable: %v", err)
	}
	origin := entry.Scheme + "://" + entry.Host

	jar, _ := cookiejar.New(nil)
	client := &http.Client{
		Jar:     jar,
		Timeout: 25 * time.Second,
		// The 303 is the thing under test, so it must not be swallowed here.
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}

	// 1) browser-shaped first visit through the proxy: 303 + Set-Cookie.
	resp, err := client.Get(started.URL)
	if err != nil {
		t.Fatalf("first visit failed: %v", err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("the entry answered %d, not 303: the Host rewrite or the redirect pass-through is broken", resp.StatusCode)
	}
	if len(jar.Cookies(entry)) == 0 {
		t.Fatal("no cookie was stored: the entry's Set-Cookie did not survive the hop")
	}

	// 2) replay with that cookie: a real page, not an error body.
	resp2, err := client.Get(origin + "/")
	if err != nil {
		t.Fatalf("replay failed: %v", err)
	}
	body, _ := io.ReadAll(io.LimitReader(resp2.Body, 1<<20))
	resp2.Body.Close()
	if resp2.StatusCode != http.StatusOK {
		t.Fatalf("replay answered %d (%d bytes); the session did not survive the proxy", resp2.StatusCode, len(body))
	}
	if len(body) < 1024 || !bytes.Contains(bytes.ToLower(body), []byte("<html")) {
		t.Fatalf("that does not look like the app's page (%d bytes)", len(body))
	}

	// 3) the live channel: SSE, and it has to be reachable through the proxy.
	resp3, err := client.Get(origin + "/plugins/events")
	if err != nil {
		t.Fatalf("event stream request failed: %v", err)
	}
	ct := resp3.Header.Get("Content-Type")
	resp3.Body.Close()
	if resp3.StatusCode != http.StatusOK {
		t.Fatalf("event stream answered %d", resp3.StatusCode)
	}
	if !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("event stream is %q; the live channel is expected to be SSE", ct)
	}
}
