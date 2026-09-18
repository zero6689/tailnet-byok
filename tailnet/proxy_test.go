package mobile

// Tests for the loopback reverse proxy.
//
// These run on the host, against `httptest` servers standing in for the real
// target, because every rule the proxy enforces is a rule about HTTP and none
// of them need a tailnet to be wrong. The one thing they cannot cover is the
// dial itself -- there is no node here, so `proxyDialContext` takes its
// platform-network branch -- and that branch is exercised on-device.
//
// The cases below are the failure modes this proxy was written against, in the
// order they were hit while working out that the approach was viable at all: a
// login handshake that never completes, a page that renders blank, an event
// stream that looks frozen, and a loopback port that would otherwise be an open
// door to the user's server.

import (
	"bufio"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// proxyDoc mirrors the JSON this package returns for the proxy entry points.
type proxyDoc struct {
	Running bool   `json:"running"`
	URL     string `json:"url"`
	Target  string `json:"target"`
	Error   string `json:"error"`
}

func decodeProxyDoc(t *testing.T, raw string) proxyDoc {
	t.Helper()
	var doc proxyDoc
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		t.Fatalf("undecodable proxy document %q: %v", raw, err)
	}
	return doc
}

// startProxyFor brings a proxy up against target and stops it when the test
// ends, so no test can inherit a stale listener from another.
func startProxyFor(t *testing.T, target string) proxyDoc {
	t.Helper()
	// The proxy fails closed without an allowlist, so a test grants exactly the
	// target it is about to dial.
	allowTarget(t, target)
	doc := decodeProxyDoc(t, StartProxy(target))
	if !doc.Running || doc.URL == "" {
		t.Fatalf("StartProxy refused %q: %s", target, doc.Error)
	}
	t.Cleanup(func() { StopProxy() })
	return doc
}

func loopbackAuthority(t *testing.T, doc proxyDoc) string {
	t.Helper()
	parsed, err := url.Parse(doc.URL)
	if err != nil {
		t.Fatalf("proxy URL is unparseable: %v", err)
	}
	return parsed.Host
}

// ---------------------------------------------------------------------------
// Starting and stopping
// ---------------------------------------------------------------------------

func TestStartProxyRejectsATargetThatIsNotAbsoluteHTTP(t *testing.T) {
	for _, target := range []string{
		"",
		"   ",
		"100.101.102.103:3080", // no scheme: parses as a scheme, but has no host
		"ftp://100.101.102.103:3080",
		"unix:///tmp/socket",
		"http://",
	} {
		doc := decodeProxyDoc(t, StartProxy(target))
		if doc.Running {
			t.Errorf("StartProxy accepted %q; it must refuse anything that is not an absolute http(s) URL", target)
		}
		if doc.Error == "" {
			t.Errorf("the refusal of %q did not say why", target)
		}
	}
}

func TestProxyListensOnLoopbackOnly(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	host, port, err := net.SplitHostPort(loopbackAuthority(t, doc))
	if err != nil {
		t.Fatalf("proxy authority is not host:port: %v", err)
	}
	// A proxy bound to 0.0.0.0 would publish the user's server to whatever
	// network the phone is on, which is the exact opposite of the point.
	if host != "127.0.0.1" {
		t.Errorf("proxy bound %s, must bind 127.0.0.1", host)
	}
	if port == "0" || port == "" {
		t.Errorf("proxy reported no concrete port: %q", port)
	}
}

func TestStartProxyIsIdempotentWhileRunning(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer upstream.Close()

	first := startProxyFor(t, upstream.URL)
	// A second call must not rebind: the URL the WebView is already holding
	// would go dead underneath it.
	second := startProxyFor(t, upstream.URL)
	if first.URL != second.URL {
		t.Errorf("second StartProxy rebound the listener:\n first %s\nsecond %s", first.URL, second.URL)
	}
}

func TestStopProxyClosesThePortAndReportsIt(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	if _, err := http.Get(doc.URL); err != nil {
		t.Fatalf("proxy did not answer before StopProxy: %v", err)
	}

	if status := decodeProxyDoc(t, StopProxy()); status.Running {
		t.Error("StopProxy still reports a running proxy")
	}
	if _, err := http.Get(doc.URL); err == nil {
		t.Error("the loopback port still answers after StopProxy; it must be closed")
	}
}

// ---------------------------------------------------------------------------
// Authorisation
// ---------------------------------------------------------------------------

func TestProxyRefusesARequestWithoutItsToken(t *testing.T) {
	var upstreamHits int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&upstreamHits, 1)
		_, _ = io.WriteString(w, "the user's server")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	// Ask the way a stranger on the device would: the right port, no token.
	// Loopback is not a security boundary on Android -- any app can reach this
	// port -- so the token is the only thing standing between another app and an
	// authenticated path into the user's server.
	response, err := http.Get("http://" + loopbackAuthority(t, doc) + "/")
	if err != nil {
		t.Fatalf("request to the proxy failed outright: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusForbidden {
		t.Errorf("a request without the token got %d, want %d", response.StatusCode, http.StatusForbidden)
	}
	if hits := atomic.LoadInt32(&upstreamHits); hits != 0 {
		t.Errorf("a request without the token reached the target %d time(s); it must be refused first", hits)
	}
}

func TestProxyPlantsItsTokenAndStripsItFromTheUpstreamRequest(t *testing.T) {
	var seenQuery, seenPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seenQuery = r.URL.RawQuery
		seenPath = r.URL.Path
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("cannot build a jar: %v", err)
	}
	client := &http.Client{Jar: jar}

	// First navigation carries the token, the way the WebView loads it.
	first, err := client.Get(doc.URL)
	if err != nil {
		t.Fatalf("first navigation failed: %v", err)
	}
	defer first.Body.Close()
	if first.StatusCode != http.StatusOK {
		t.Fatalf("first navigation got %d", first.StatusCode)
	}
	if seenQuery != "" {
		t.Errorf("the proxy forwarded its own token upstream as %q", seenQuery)
	}

	// A jar is the wrong place to look for HttpOnly: net/http/cookiejar
	// deliberately does not round-trip attributes that exist only to constrain a
	// browser's script policy. Assert it on the wire.
	planted := false
	for _, cookie := range jar.Cookies(mustParseURL(t, doc.URL)) {
		if cookie.Name == proxyTokenCookie {
			planted = true
		}
	}
	if !planted {
		t.Fatalf("the proxy did not plant its %s cookie; the page's own requests cannot be authorised", proxyTokenCookie)
	}
	httpOnly := false
	for _, raw := range first.Header.Values("Set-Cookie") {
		if strings.HasPrefix(raw, proxyTokenCookie+"=") {
			httpOnly = strings.Contains(raw, "HttpOnly")
		}
	}
	if !httpOnly {
		t.Error("the token cookie must be HttpOnly: page script has no use for it")
	}

	// Second request rides the cookie alone, which is how every absolute path in
	// the loaded page will arrive.
	second, err := client.Get("http://" + loopbackAuthority(t, doc) + "/api/migrate-on-429/state")
	if err != nil {
		t.Fatalf("cookie-authorised request failed: %v", err)
	}
	defer second.Body.Close()
	if second.StatusCode != http.StatusOK {
		t.Errorf("cookie-authorised request got %d; the planted cookie was not accepted", second.StatusCode)
	}
	if seenPath != "/api/migrate-on-429/state" {
		t.Errorf("upstream saw path %q, want the requested path", seenPath)
	}
}

func mustParseURL(t *testing.T, raw string) *url.URL {
	t.Helper()
	parsed, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("unparseable URL %q: %v", raw, err)
	}
	return parsed
}

// ---------------------------------------------------------------------------
// The four load-bearing behaviours
// ---------------------------------------------------------------------------

func TestProxyRewritesHostOriginAndStripsReferer(t *testing.T) {
	type observed struct{ host, origin, referer, forwardedFor, forwardedHost string }
	var got observed

	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = observed{
			host:          r.Host,
			origin:        r.Header.Get("Origin"),
			referer:       r.Header.Get("Referer"),
			forwardedFor:  r.Header.Get("X-Forwarded-For"),
			forwardedHost: r.Header.Get("X-Forwarded-Host"),
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	request, err := http.NewRequest(http.MethodGet, doc.URL, nil)
	if err != nil {
		t.Fatalf("cannot build the request: %v", err)
	}
	request.Header.Set("Referer", "http://127.0.0.1:1/ignored")
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	// The target's auth proxy only mints its session cookie when the request
	// carries the authority it expects. From the WebView's side the authority is
	// loopback, so this rewrite is what turns a 401 into a rendered page.
	wantAuthority := strings.TrimPrefix(upstream.URL, "http://")
	if got.host != wantAuthority {
		t.Errorf("upstream Host = %q, want the target authority %q", got.host, wantAuthority)
	}
	if got.origin != upstream.URL {
		t.Errorf("upstream Origin = %q, want %q", got.origin, upstream.URL)
	}
	// Referer is dropped: the loopback hop is this app's business, and the
	// target's auth and /api fence depend on Host and Origin, not on Referer.
	if got.referer != "" {
		t.Errorf("upstream Referer = %q, want it stripped", got.referer)
	}
	if got.forwardedFor != "" || got.forwardedHost != "" {
		t.Errorf("client-address headers were forwarded upstream: for=%q host=%q", got.forwardedFor, got.forwardedHost)
	}
}

func TestProxyStripsCookieDomainSoTheWebViewWillStoreIt(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The shape the DSH auth proxy actually sets, plus one cookie that must
		// come through untouched.
		w.Header().Add("Set-Cookie", "dsh-auth=abc123; Domain=100.101.102.103; Path=/; HttpOnly; SameSite=Strict")
		w.Header().Add("Set-Cookie", "plain=1; Path=/")
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	response, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	// The first response legitimately carries three cookies: the proxy plants its
	// own token here (that is how the page's later absolute-path requests get
	// authorised), alongside the target's two. Only the target's are under test.
	var raw []string
	planted := false
	for _, cookie := range response.Header.Values("Set-Cookie") {
		if strings.HasPrefix(cookie, proxyTokenCookie+"=") {
			planted = true
			continue
		}
		raw = append(raw, cookie)
	}
	if !planted {
		t.Errorf("the first response did not carry the proxy's own %s cookie", proxyTokenCookie)
	}
	if len(raw) != 2 {
		t.Fatalf("got %d upstream Set-Cookie headers, want 2: %v", len(raw), raw)
	}
	// A cookie scoped to the target's hostname is rejected outright by a page
	// served from 127.0.0.1, and the login handshake then never completes.
	if strings.Contains(strings.ToLower(raw[0]), "domain=") {
		t.Errorf("Domain survived into the WebView: %q", raw[0])
	}
	for _, attribute := range []string{"dsh-auth=abc123", "Path=/", "HttpOnly", "SameSite=Strict"} {
		if !strings.Contains(raw[0], attribute) {
			t.Errorf("stripping Domain also lost %q: %q", attribute, raw[0])
		}
	}
	if !strings.Contains(raw[1], "plain=1") {
		t.Errorf("a cookie without a Domain attribute was altered: %q", raw[1])
	}

	var auth *http.Cookie
	for _, cookie := range response.Cookies() {
		if cookie.Name == "dsh-auth" {
			auth = cookie
		}
	}
	if auth == nil {
		t.Fatalf("the target's cookie did not survive the proxy: %v", response.Cookies())
	}
	if auth.Domain != "" {
		t.Errorf("parsed cookie still carries Domain %q", auth.Domain)
	}
	if auth.Value != "abc123" || auth.Path != "/" {
		t.Errorf("parsed cookie lost its value or path: %+v", auth)
	}
}

// ---------------------------------------------------------------------------
// Location shapes: the corpus
// ---------------------------------------------------------------------------

// locationShape is one Location value and what rewriteLocation must do with it.
//
// The corpus is the one an adversarial pass ran against both this code and a
// WHATWG URL parser (`new URL(shape, 'http://127.0.0.1:<port>/panel?x=1')`).
// Every `refused` entry resolves, in a browser, to an authority that is not the
// loopback origin -- and every one of them was forwarded verbatim by the version
// of rewriteLocation that treated `Scheme == "" && Host == ""` as "a relative
// reference the browser can only resolve back into this origin". Go's parser and
// a browser disagree about that, and the disagreement is exactly one authority
// wide: a browser normalises `\` to `/` for a special scheme and skips any
// number of leading `/` and `\`, so two or more slash-like characters at the
// start of a relative reference begin an authority.
//
// The rule that replaced it is `staysOnOrigin`: normalise the value the way a
// browser would, then refuse it if `//` is left at the front.
// TestRewriteLocationDecisionsMatchTheCorpus asserts `refused` shape by shape.
//
// Escaping note, because it is the one thing that is easy to get wrong here: in
// Go source a single literal backslash is written `\\`, and a refused entry
// needs TWO OR MORE leading slash-likes -- so `"\\\\\\\\evil.com/x"` is the
// two-backslash case, while `"\\\\evil.com/x"` (one backslash) is a relative
// reference the browser resolves against the base's own host and is allowed.
type locationShape struct {
	raw     string
	refused bool
	// why is only for the entries whose expectation is not obvious.
	why string
}

var locationShapes = []locationShape{
	// --- refused: a browser reads an authority out of these ------------------
	{raw: "//evil.com/x", refused: true},
	{raw: "///evil.com/x", refused: true, why: "authority-ignore-slashes skips the third slash"},
	{raw: "////evil.com/x", refused: true, why: "the shape that broke the first fix attempt"},
	{raw: "/////evil.com/x", refused: true},
	{raw: "//evil.com", refused: true},
	{raw: "///", refused: true},
	{raw: "//", refused: true},
	{raw: "\\\\evil.com/x", refused: true, why: "two backslashes: two slash-likes once normalised"},
	{raw: "\\/\\/evil.com/x", refused: true},
	{raw: "/\\evil.com/x", refused: true},
	{raw: "/\\\\evil.com/x", refused: true},
	{raw: "\\/evil.com/x", refused: true, why: "backslash then slash"},
	{raw: "\\\\\\evil.com/x", refused: true},
	{raw: "\\//evil.com", refused: true},
	{raw: "\\/\\\\/evil.com", refused: true},
	{raw: "\\\\evil.com", refused: true},
	{raw: "//@evil.com", refused: true},
	{raw: "/\\\\@evil.com", refused: true, why: "becomes ///@evil.com"},
	{raw: "\t//evil.com", refused: true, why: "a WHATWG parser strips the tab; Go rejects the value outright"},
	{raw: "\n//evil.com", refused: true, why: "same, with a newline"},
	{raw: "http://evil.com/x", refused: true, why: "absolute, different origin"},
	{raw: "https://evil.com/x", refused: true, why: "absolute, different scheme"},
	{raw: "//evil.com:8080/x", refused: true, why: "protocol-relative"},
	{raw: "javascript:alert(1)", refused: true, why: "opaque scheme"},
	{raw: "data:text/html,<script>alert(1)</script>", refused: true, why: "opaque scheme"},
	{raw: "http:/evil.com/x", refused: true, why: "no authority, so not the target's origin either"},

	// --- allowed: these cannot leave the origin ------------------------------
	{raw: "/"},
	{raw: "/app"},
	{raw: "/app?tab=1"},
	{raw: "/app#frag"},
	{raw: "settings"},
	{raw: "a/b"},
	{raw: "../settings"},
	{raw: "./settings"},
	{raw: "?q=1"},
	{raw: "#frag"},
	{raw: "/a//b", why: "two slashes, but not at the start"},
	{raw: "/a///b"},
	{raw: "/foo/../bar"},
	{raw: "/.."},
	{raw: "/%2F%2Fevil.com", why: "percent-encoded slashes are not path separators here"},
	{raw: "/%5C%5Cevil.com", why: "percent-encoded backslashes likewise"},
	{raw: "/%09%09//evil.com"},
	{raw: "\\evil.com/x", why: "one leading backslash: the base's own host is kept"},
	{raw: "/x\\y", why: "backslash mid-path"},
	{raw: "a\\b"},
	{raw: "/.evil.com"},
	{raw: "/..evil.com"},
	{raw: "/@evil.com"},
	{raw: "/;evil.com"},
	{raw: "/foo:bar"},
}

// rewriteLocationFor runs the real decision against a synthetic response, so a
// corpus case is judged by the code that ships rather than by a copy of its
// rules.
func rewriteLocationFor(t *testing.T, target, location string) *http.Response {
	t.Helper()
	p := &proxyRuntime{target: mustParseURL(t, target)}
	response := &http.Response{
		StatusCode:    http.StatusFound,
		Status:        "302 Found",
		Header:        http.Header{"Location": {location}},
		Body:          io.NopCloser(strings.NewReader("upstream body")),
		ContentLength: int64(len("upstream body")),
		Request:       &http.Request{URL: mustParseURL(t, target+"/panel?x=1")},
	}
	if err := p.rewriteLocation(response); err != nil {
		t.Fatalf("rewriteLocation(%q) returned an error: %v", location, err)
	}
	return response
}

func TestRewriteLocationDecisionsMatchTheCorpus(t *testing.T) {
	const target = "http://100.101.102.103:3080"
	for _, shape := range locationShapes {
		response := rewriteLocationFor(t, target, shape.raw)
		location := response.Header.Get("Location")

		if !shape.refused {
			if response.StatusCode != http.StatusFound {
				t.Errorf("%q: status %d, want the redirect passed through", shape.raw, response.StatusCode)
				continue
			}
			if location != shape.raw {
				t.Errorf("%q: rewritten to %q, want it kept as the target wrote it", shape.raw, location)
			}
			continue
		}

		if response.StatusCode != http.StatusBadGateway {
			t.Errorf("%q: status %d, want %d (%s)", shape.raw, response.StatusCode, http.StatusBadGateway, shape.why)
		}
		if location != "" {
			t.Errorf("%q: Location = %q survived; it must not reach the WebView", shape.raw, location)
		}
	}
}

// The same corpus through the real proxy, so the decision is also proven to be
// the one that reaches the WebView over the wire. Cases whose value cannot be
// written as an HTTP header field (the control-character entries) are covered by
// the unit test above; the rest are exercised here end to end.
func TestProxyRefusesOffOriginLocationShapesEndToEnd(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", r.URL.Query().Get("loc"))
		w.WriteHeader(http.StatusFound)
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatalf("cannot build a jar: %v", err)
	}
	client := &http.Client{
		Jar:           jar,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	// One navigation consumes the one-shot token and plants the standing cookie,
	// so every case below is an authorised request.
	if _, err := client.Get(doc.URL); err != nil {
		t.Fatalf("warm-up navigation failed: %v", err)
	}

	base := "http://" + loopbackAuthority(t, doc) + "/jump?loc="
	for _, shape := range locationShapes {
		if strings.ContainsAny(shape.raw, "\t\n\r") {
			continue
		}
		response, err := client.Get(base + url.QueryEscape(shape.raw))
		if err != nil {
			t.Fatalf("%q: request failed: %v", shape.raw, err)
		}
		location := response.Header.Get("Location")
		status := response.StatusCode
		response.Body.Close()

		if shape.refused {
			if status != http.StatusBadGateway || location != "" {
				t.Errorf("%q: got %d Location=%q, want 502 with no Location (%s)", shape.raw, status, location, shape.why)
			}
			continue
		}
		if status != http.StatusFound || location != shape.raw {
			t.Errorf("%q: got %d Location=%q, want 302 with the value unchanged", shape.raw, status, location)
		}
	}
}

// The rewrite can create the shape the rule refuses. A same-origin absolute
// Location whose path begins with two slashes reduces, by path-and-query, to
// `//evil.com/x` -- which the browser then reads as a fresh authority. Checking
// the input alone would miss it, which is why the check runs on the value that
// is about to be written.
func TestProxyRefusesASameOriginLocationWhosePathReintroducesAnAuthority(t *testing.T) {
	var upstream *httptest.Server
	upstream = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", upstream.URL+"//evil.com/x")
		w.WriteHeader(http.StatusFound)
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	response, err := client.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusBadGateway {
		t.Errorf("got %d, want %d: the reduced path would have been read as an authority", response.StatusCode, http.StatusBadGateway)
	}
	if location := response.Header.Get("Location"); location != "" {
		t.Errorf("Location = %q survived; `//` at the front of an origin-form value is an authority", location)
	}
}

// A subframe load never reaches WebViewClient.shouldOverrideUrlLoading, so the
// Java-side navigation filter cannot see it. Confining frames to this origin is
// the cheap mitigation for that gap, and object-src removes the plugin path.
func TestProxyAddsAFrameConfiningContentSecurityPolicy(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "<html></html>")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	response, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	policies := response.Header.Values("Content-Security-Policy")
	if len(policies) == 0 {
		t.Fatal("no Content-Security-Policy was added")
	}
	joined := strings.Join(policies, " | ")
	for _, want := range []string{"frame-src 'self'", "object-src 'none'"} {
		if !strings.Contains(joined, want) {
			t.Errorf("the policy %q is missing %q", joined, want)
		}
	}
}

// A target that already sends its own policy must not have it replaced: a second
// Content-Security-Policy header narrows the first, while Set would have thrown
// the target's own (possibly stricter) policy away.
func TestProxyNeverReplacesTheTargetsOwnContentSecurityPolicy(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'")
		_, _ = io.WriteString(w, "<html></html>")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	response, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	joined := strings.Join(response.Header.Values("Content-Security-Policy"), " | ")
	if !strings.Contains(joined, "default-src 'none'") {
		t.Errorf("the target's own policy was replaced: %q", joined)
	}
	if !strings.Contains(joined, "frame-src 'self'") {
		t.Errorf("the frame confinement was not added alongside it: %q", joined)
	}
}

func TestStripCookieDomain(t *testing.T) {
	cases := []struct{ name, in, want string }{
		{"domain dropped", "dsh-auth=abc; Domain=host.example; Path=/", "dsh-auth=abc; Path=/"},
		{"lowercase domain dropped", "dsh-auth=abc; domain=host.example; Path=/", "dsh-auth=abc; Path=/"},
		{"no domain is a no-op", "dsh-auth=abc; Path=/; HttpOnly; SameSite=Strict", "dsh-auth=abc; Path=/; HttpOnly; SameSite=Strict"},
		{"every domain dropped", "a=b; Domain=one; Domain=two; Path=/", "a=b; Path=/"},
		{"the name=value pair is never treated as an attribute", "Domain=host.example", "Domain=host.example"},
		{"secure and httponly survive", "a=b; Secure; HttpOnly", "a=b; Secure; HttpOnly"},
	}
	for _, testCase := range cases {
		if got := stripCookieDomain(testCase.in); got != testCase.want {
			t.Errorf("%s: stripCookieDomain(%q) = %q, want %q", testCase.name, testCase.in, got, testCase.want)
		}
	}
}

// The login handshake is `303 + Set-Cookie`, and the browser's own jar is what
// completes it, so the redirect has to reach the WebView rather than being
// followed here. See rewriteLocation for why the Location that reaches it is
// nevertheless not simply whatever the target wrote.
//
// The relative form is what the DSH auth proxy actually sends (`Location: /`).
// For a relative reference the passthrough is byte-for-byte on purpose: it
// cannot leave the origin (the browser resolves it against the loopback URL it
// loaded) and re-encoding it could only disturb the target's own routing.
func TestProxyKeepsRelativeLocationsRelativeAndDoesNotFollowThem(t *testing.T) {
	for _, location := range []string{"/", "/app?tab=1", "settings"} {
		t.Run(location, func(t *testing.T) {
			var upstreamHits int32
			upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				atomic.AddInt32(&upstreamHits, 1)
				if r.Header.Get("Cookie") == "" {
					w.Header().Set("Set-Cookie", "dsh-auth=tok; Path=/; HttpOnly")
					w.Header().Set("Location", location)
					w.WriteHeader(http.StatusSeeOther)
					return
				}
				_, _ = io.WriteString(w, "the real page")
			}))
			defer upstream.Close()

			doc := startProxyFor(t, upstream.URL)

			client := &http.Client{
				CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
			}
			response, err := client.Get(doc.URL)
			if err != nil {
				t.Fatalf("request failed: %v", err)
			}
			defer response.Body.Close()

			if response.StatusCode != http.StatusSeeOther {
				t.Errorf("got %d, want the 303 passed through", response.StatusCode)
			}
			if got := response.Header.Get("Location"); got != location {
				t.Errorf("Location = %q, want the relative reference kept as the target wrote it, %q", got, location)
			}
			if hits := atomic.LoadInt32(&upstreamHits); hits != 1 {
				t.Errorf("the proxy followed the redirect itself (%d upstream requests, want 1)", hits)
			}
		})
	}
}

// An absolute Location that names the target's own origin is the case a hosted
// DSH host produces when it is configured with its own public name. It is the
// same destination, but stated in a form the WebView must never be handed: if
// the browser is left holding the target's authority, the next navigation is
// the browser's own, not the proxy's, and the loopback origin -- and with it
// every rewrite in Rewrite -- is gone.
func TestProxyRelativizesASameOriginAbsoluteLocation(t *testing.T) {
	var upstream *httptest.Server
	upstream = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", upstream.URL+"/console?tab=logs")
		w.WriteHeader(http.StatusFound)
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	response, err := client.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusFound {
		t.Errorf("got %d, want the 302 passed through", response.StatusCode)
	}
	// Path and query only. Not "the target's absolute URL" and not even
	// "loopback plus the path": a relative reference is the one spelling that is
	// correct no matter which loopback port this run happened to bind.
	if got := response.Header.Get("Location"); got != "/console?tab=logs" {
		t.Errorf("Location = %q, want the same-origin absolute URL reduced to %q", got, "/console?tab=logs")
	}
}

// The case the whole rewriteLocation rule exists for: an upstream -- or whoever
// controls it, a plugin, a misconfigured auth proxy, an open redirect on the
// target -- naming a destination that is not the target. The WebView has no
// navigation filter of its own and the app permits cleartext, so passing this
// through would move the user's browser to an attacker's page inside a window
// whose chrome still says this app.
func TestProxyRefusesARedirectOffTheTargetOrigin(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Location", "http://elsewhere.example/login")
		w.WriteHeader(http.StatusFound)
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	response, err := client.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusBadGateway {
		t.Errorf("got %d, want %d for a redirect off the target's origin", response.StatusCode, http.StatusBadGateway)
	}
	if location := response.Header.Get("Location"); location != "" {
		t.Errorf("Location = %q survived; an off-target destination must not be handed to the WebView at all", location)
	}
	if contentType := response.Header.Get("Content-Type"); !strings.HasPrefix(contentType, "text/plain") {
		t.Errorf("Content-Type = %q, want text/plain for the refusal body", contentType)
	}
	if encoding := response.Header.Get("Content-Encoding"); encoding != "" {
		t.Errorf("Content-Encoding = %q survived onto a plain-text body; the browser would try to decode it", encoding)
	}

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("cannot read the refusal body: %v", err)
	}
	if !strings.Contains(string(body), "off-target") || !strings.Contains(string(body), "refused") {
		t.Errorf("the refusal body does not say what happened: %q", body)
	}
	// The host came from the upstream, so it is exactly the string that must not
	// be rendered: a visible URL in a page the user trusts is a phishing aid.
	if strings.Contains(string(body), "elsewhere.example") {
		t.Errorf("the refusal echoed the off-target host back to the user: %q", body)
	}
	if response.ContentLength != int64(len(body)) {
		t.Errorf("Content-Length = %d for a %d-byte body", response.ContentLength, len(body))
	}
}

// closeTracker is a body that records whether it was closed, so the test can
// prove the discarded upstream response was released rather than abandoned.
type closeTracker struct {
	io.Reader
	closed bool
}

func (c *closeTracker) Close() error {
	c.closed = true
	return nil
}

// The unit-level half of the rule above: what the refusal does to the response
// object, including the headers that only matter when it is built by hand (an
// upstream that sent a Content-Encoding is the case the pipeline test cannot
// reach without sending a real compressed body).
func TestRefuseOffTargetRedirectRewritesTheWholeResponse(t *testing.T) {
	upstreamBody := &closeTracker{Reader: strings.NewReader("upstream secret body")}
	response := &http.Response{
		StatusCode: http.StatusFound,
		Status:     "302 Found",
		Header: http.Header{
			"Location":         {"http://elsewhere.example/x"},
			"Content-Encoding": {"gzip"},
			"Content-Length":   {strconv.Itoa(len("upstream secret body"))},
			"Content-Type":     {"text/html; charset=utf-8"},
		},
		Body:          upstreamBody,
		ContentLength: int64(len("upstream secret body")),
	}

	if err := refuseOffTargetRedirect(response); err != nil {
		t.Fatalf("refuseOffTargetRedirect returned an error: %v", err)
	}

	if response.StatusCode != http.StatusBadGateway {
		t.Errorf("StatusCode = %d, want %d", response.StatusCode, http.StatusBadGateway)
	}
	if response.Header.Get("Location") != "" {
		t.Errorf("Location = %q survived", response.Header.Get("Location"))
	}
	if response.Header.Get("Content-Encoding") != "" {
		t.Errorf("Content-Encoding = %q survived a plain-text body", response.Header.Get("Content-Encoding"))
	}
	if got := response.Header.Get("Content-Type"); got != "text/plain; charset=utf-8" {
		t.Errorf("Content-Type = %q", got)
	}
	if got := response.Header.Get("Content-Length"); got != strconv.Itoa(len(offTargetRedirectBody)) {
		t.Errorf("Content-Length header = %q, want %d", got, len(offTargetRedirectBody))
	}
	if response.ContentLength != int64(len(offTargetRedirectBody)) {
		t.Errorf("ContentLength = %d, want %d", response.ContentLength, len(offTargetRedirectBody))
	}

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("cannot read the replacement body: %v", err)
	}
	if string(body) != offTargetRedirectBody {
		t.Errorf("body = %q, want %q", body, offTargetRedirectBody)
	}
	// The upstream body is discarded, so its stream has to be released here:
	// nothing downstream is ever going to read it.
	if !upstreamBody.closed {
		t.Error("the discarded upstream body was not closed; its connection is left hanging")
	}
}

func TestSameOriginNormalizesCaseAndDefaultPorts(t *testing.T) {
	cases := []struct {
		name, a, b string
		want       bool
	}{
		{"identical", "http://100.101.102.103:3080/x", "http://100.101.102.103:3080/", true},
		{"host case", "http://Host.Example:3080/", "http://host.example:3080/", true},
		{"implicit default port", "http://host.example/x", "http://host.example:80/", true},
		{"implicit default port, https", "https://host.example/x", "https://host.example:443/", true},
		{"different scheme", "http://host.example/", "https://host.example/", false},
		{"different port", "http://host.example:3080/", "http://host.example:3081/", false},
		{"different host", "http://host.example/", "http://other.example/", false},
		{"explicit vs different explicit port", "http://host.example:80/", "http://host.example:8080/", false},
	}
	for _, testCase := range cases {
		got := sameOrigin(mustParseURL(t, testCase.a), mustParseURL(t, testCase.b))
		if got != testCase.want {
			t.Errorf("%s: sameOrigin(%q, %q) = %v, want %v", testCase.name, testCase.a, testCase.b, got, testCase.want)
		}
	}
}

func TestProxyStreamsWithoutBuffering(t *testing.T) {
	release := make(chan struct{})
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		flusher, ok := w.(http.Flusher)
		if !ok {
			t.Error("httptest writer cannot flush; the test cannot prove anything")
			return
		}
		_, _ = io.WriteString(w, "data: one\n\n")
		flusher.Flush()
		// Hold the stream open, the way the DSH UI's event stream does.
		<-release
		_, _ = io.WriteString(w, "data: two\n\n")
		flusher.Flush()
	}))
	defer upstream.Close()
	defer close(release)

	doc := startProxyFor(t, upstream.URL)
	response, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	reader := bufio.NewReader(response.Body)
	first := make(chan string, 1)
	go func() {
		line, _ := reader.ReadString('\n')
		first <- line
	}()

	select {
	case line := <-first:
		if line != "data: one\n" {
			t.Errorf("first streamed line = %q, want %q", line, "data: one\n")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("the first event did not arrive while the stream was still open: the proxy is buffering")
	}
}

func TestProxyLeavesContentEncodingAlone(t *testing.T) {
	// Go's transport would add its own Accept-Encoding and decompress
	// transparently, which desynchronises the header from the body. In a browser
	// that renders as a blank page, so the upstream must never see an encoding
	// the browser did not ask for.
	var sawAcceptEncoding string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sawAcceptEncoding = r.Header.Get("Accept-Encoding")
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)
	request, err := http.NewRequest(http.MethodGet, doc.URL, nil)
	if err != nil {
		t.Fatalf("cannot build the request: %v", err)
	}
	// The test client must not ask for gzip either, or it would be the one
	// supplying the header and the assertion below would prove nothing about the
	// proxy.
	client := &http.Client{Transport: &http.Transport{DisableCompression: true}}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	if sawAcceptEncoding != "" {
		t.Errorf("upstream saw Accept-Encoding %q from a client that asked for none", sawAcceptEncoding)
	}
}

// Every header in the X-Forwarded family describes the hop the request took to
// get here -- this device, this port, this loopback name -- and none of it is
// the target's business. Go's ReverseProxy forwards all of them untouched, so
// the proxy drops them by name; the list is asserted in full rather than one at
// a time because the failure mode is a missing entry, not a wrong one.
func TestProxyStripsEveryForwardingHeader(t *testing.T) {
	var seen http.Header
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = r.Header.Clone()
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	forwarded := []string{
		"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
		"X-Forwarded-Port", "X-Forwarded-Server", "X-Forwarded-Prefix",
		"X-Real-IP",
	}
	request, err := http.NewRequest(http.MethodGet, doc.URL, nil)
	if err != nil {
		t.Fatalf("cannot build the request: %v", err)
	}
	for _, header := range forwarded {
		request.Header.Set(header, "198.51.100.7")
	}
	request.Header.Set("Forwarded", "for=198.51.100.7;proto=http")

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("request failed: %v", err)
	}
	defer response.Body.Close()

	for _, header := range append(forwarded, "Forwarded") {
		if got := seen.Get(header); got != "" {
			t.Errorf("upstream saw %s = %q; every forwarding header must be dropped", header, got)
		}
	}
}

// A 429 or 503 here arrives in the middle of a page load, when dozens of
// requests are in flight at once. Without Retry-After the browser backs off on
// its own schedule, which is how a momentarily busy proxy becomes a page that
// looks like it is still loading. The 429 side is asserted in
// TestProxyRateLimitsRequests; this is the in-flight cap, which is the one an
// open event stream can cause on its own.
func TestProxyReturnsRetryAfterWhenTheConnectionLimitIsReached(t *testing.T) {
	release := make(chan struct{})
	var releaseOnce sync.Once
	unblock := func() { releaseOnce.Do(func() { close(release) }) }
	defer unblock()

	started := make(chan struct{})
	var startedOnce sync.Once
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/hold" {
			startedOnce.Do(func() { close(started) })
			<-release
		}
		_, _ = io.WriteString(w, "ok")
	}))
	defer upstream.Close()

	// One slot: the second concurrent request must be refused, not queued.
	cfg := `{"allowedTargets":["` + originOfTarget(t, upstream.URL) + `"],"maxConnections":1}`
	if msg := SetSecurityConfig(cfg); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}
	doc := decodeProxyDoc(t, StartProxy(upstream.URL))
	if !doc.Running {
		t.Fatalf("proxy refused to start: %s", doc.Error)
	}
	t.Cleanup(func() { StopProxy() })

	// One quick request first, both to consume the one-shot token and to pick up
	// the standing cookie: the point of this test is what happens to a
	// *legitimate* client, so the refused request has to be authorised.
	warm, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("warm-up request failed: %v", err)
	}
	warm.Body.Close()
	session := ""
	for _, cookie := range warm.Cookies() {
		if cookie.Name == proxyTokenCookie {
			session = cookie.Value
		}
	}
	if session == "" {
		t.Fatal("the warm-up request did not plant the session cookie")
	}

	base := "http://" + loopbackAuthority(t, doc) + "/"
	busyDone := make(chan struct{})
	go func() {
		defer close(busyDone)
		request, buildErr := http.NewRequest(http.MethodGet, base+"hold", nil)
		if buildErr != nil {
			return
		}
		request.AddCookie(&http.Cookie{Name: proxyTokenCookie, Value: session})
		response, doErr := http.DefaultClient.Do(request)
		if doErr == nil {
			response.Body.Close()
		}
	}()

	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("the long-lived request never reached the target; the test cannot prove anything")
	}

	blocked, err := http.NewRequest(http.MethodGet, base+"other", nil)
	if err != nil {
		t.Fatalf("cannot build the second request: %v", err)
	}
	blocked.AddCookie(&http.Cookie{Name: proxyTokenCookie, Value: session})
	response, err := http.DefaultClient.Do(blocked)
	if err != nil {
		t.Fatalf("second request failed outright: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusServiceUnavailable {
		t.Errorf("second concurrent request got %d, want %d", response.StatusCode, http.StatusServiceUnavailable)
	}
	if retry := response.Header.Get("Retry-After"); retry != "1" {
		t.Errorf("Retry-After = %q, want \"1\"", retry)
	}

	unblock()
	select {
	case <-busyDone:
	case <-time.After(5 * time.Second):
		t.Error("the long-lived request did not finish after being released")
	}
}

// Item 8 of the hardening pass: MaxIdleConnsPerHost. Go's default of 2 means a
// page that loads its assets in parallel keeps re-dialling through the userspace
// netstack, which is where all the cost is on a tailnet link. This is a
// performance setting and nothing else -- every one of these connections still
// goes to p.target -- so the assertion is that it is set, and set in step with
// MaxIdleConns rather than left at the default.
func TestProxyTransportKeepsIdleConnectionsPerHost(t *testing.T) {
	p := &proxyRuntime{target: mustParseURL(t, "http://100.101.102.103:3080")}
	reverse := p.reverseProxy()
	transport, ok := reverse.Transport.(*http.Transport)
	if !ok {
		t.Fatalf("the reverse proxy transport is %T, want *http.Transport", reverse.Transport)
	}
	if transport.MaxIdleConnsPerHost < 8 {
		t.Errorf("MaxIdleConnsPerHost = %d; Go's default of 2 makes parallel asset loads re-dial the netstack", transport.MaxIdleConnsPerHost)
	}
	if transport.MaxIdleConnsPerHost != transport.MaxIdleConns {
		t.Errorf("MaxIdleConnsPerHost = %d but MaxIdleConns = %d; the per-host budget is the one that matters here",
			transport.MaxIdleConnsPerHost, transport.MaxIdleConns)
	}
	if !transport.DisableCompression {
		t.Error("DisableCompression is off; Go would negotiate an encoding the browser never asked for")
	}
}
