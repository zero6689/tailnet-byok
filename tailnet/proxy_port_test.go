package mobile

import (
	"encoding/json"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

// The port the loopback proxy binds is remembered between runs. That is a
// cold-start performance property with a user-visible consequence -- a WebView
// cache is keyed by full URL, port included, so a new port every launch means
// re-downloading the whole UI every launch -- and it is also a behaviour that
// is easy to lose silently, because nothing about the app stops working when
// it regresses. Hence these tests.

// startProxy brings the proxy up and returns the port it bound, failing the
// test if it refused to start at all.
func startProxy(t *testing.T) int {
	t.Helper()
	const target = "http://100.64.0.1:3080"
	allowTarget(t, target)
	document := StartProxy(target)
	var payload struct {
		Running bool   `json:"running"`
		URL     string `json:"url"`
		Error   string `json:"error"`
	}
	if err := json.Unmarshal([]byte(document), &payload); err != nil {
		t.Fatalf("StartProxy returned something that is not JSON: %v (%q)", err, document)
	}
	if !payload.Running {
		t.Fatalf("proxy refused to start: %s", payload.Error)
	}
	parsed, err := url.Parse(payload.URL)
	if err != nil {
		t.Fatalf("proxy URL is not a URL: %v (%q)", err, payload.URL)
	}
	port, err := strconv.Atoi(parsed.Port())
	if err != nil {
		t.Fatalf("proxy URL carries no usable port: %v (%q)", err, payload.URL)
	}
	return port
}

// useStateDir points the proxy at a directory of its own and tears everything
// down afterwards, so no test can leave a listener or a remembered port behind
// for the next one.
func useStateDir(t *testing.T, dir string) {
	t.Helper()
	setProxyStateDir(dir)
	t.Cleanup(func() {
		StopProxy()
		setProxyStateDir("")
	})
}

func TestProxyPortIsReusedAfterRestart(t *testing.T) {
	dir := t.TempDir()
	useStateDir(t, dir)

	first := startProxy(t)
	StopProxy()

	second := startProxy(t)
	if first != second {
		t.Fatalf("port changed across a restart: first %d, second %d -- the WebView origin, and with it the HTTP cache, is lost on every launch", first, second)
	}

	// The record is what makes the next run able to reuse it, so it has to be
	// on disk and it has to be the port that was actually bound.
	raw, err := os.ReadFile(filepath.Join(dir, proxyPortFile))
	if err != nil {
		t.Fatalf("the bound port was not recorded: %v", err)
	}
	if got, err := strconv.Atoi(string(raw)); err != nil || got != second {
		t.Fatalf("recorded port is %q, want %d", string(raw), second)
	}
}

// A remembered port that is already taken is the case that decides whether this
// feature is safe: the app must come up anyway, on a port of the platform's
// choosing, and then remember that one.
func TestProxyFallsBackWhenRememberedPortIsTaken(t *testing.T) {
	dir := t.TempDir()
	useStateDir(t, dir)

	blocker, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("setup: cannot occupy a port: %v", err)
	}
	defer func() { _ = blocker.Close() }()
	taken := blocker.Addr().(*net.TCPAddr).Port

	if err := os.WriteFile(filepath.Join(dir, proxyPortFile), []byte(strconv.Itoa(taken)), 0o600); err != nil {
		t.Fatalf("setup: cannot write the remembered port: %v", err)
	}

	port := startProxy(t)
	if port == taken {
		t.Fatalf("proxy reported the port that was already taken (%d)", taken)
	}

	// And the fallback port is what the next run should prefer.
	raw, err := os.ReadFile(filepath.Join(dir, proxyPortFile))
	if err != nil {
		t.Fatalf("the fallback port was not recorded: %v", err)
	}
	if got, err := strconv.Atoi(string(raw)); err != nil || got != port {
		t.Fatalf("recorded port is %q, want the fallback %d", string(raw), port)
	}
}

// Every unusable record has to mean "no preference". If one of these instead
// produced an error or a privileged port, the failure would be an app that
// cannot show a page, so the table is deliberately unkind.
func TestRememberedProxyPortRejectsNonsense(t *testing.T) {
	cases := []struct {
		name    string
		content string
		want    int
	}{
		{"empty", "", 0},
		{"not a number", "abc", 0},
		{"negative", "-1", 0},
		{"zero", "0", 0},
		{"privileged", "80", 0},
		{"out of range", "70000", 0},
		{"trailing rubbish", "41235x", 0},
		{"inside a URL", "http://127.0.0.1:41235/", 0},
		{"valid", "41235", 41235},
		{"valid with whitespace", " 41235\n", 41235},
		{"lowest usable", strconv.Itoa(proxyPortFloor), proxyPortFloor},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			useStateDir(t, dir)
			if err := os.WriteFile(filepath.Join(dir, proxyPortFile), []byte(tc.content), 0o600); err != nil {
				t.Fatalf("setup: %v", err)
			}
			if got := rememberedProxyPort(); got != tc.want {
				t.Fatalf("rememberedProxyPort(%q) = %d, want %d", tc.content, got, tc.want)
			}
		})
	}
}

// The app's own default: no state directory recorded yet, so there is nowhere
// to remember a port. That must be an ordinary ephemeral bind, not a refusal.
func TestProxyStartsWithNoStateDirectory(t *testing.T) {
	useStateDir(t, "")

	port := startProxy(t)
	if port < proxyPortFloor || port > 65535 {
		t.Fatalf("ephemeral bind produced %d, which is not a usable port", port)
	}

	// Two starts in a row are allowed to differ in this configuration; what is
	// asserted is only that both work.
	StopProxy()
	if second := startProxy(t); second < proxyPortFloor || second > 65535 {
		t.Fatalf("second ephemeral bind produced %d", second)
	}
}
