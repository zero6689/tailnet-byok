package mobile

// Tests for Mobile.Fetch's target policy.
//
// Fetch is the second thing in this package that dials, next to the loopback
// reverse proxy, and until this pass it dialled wherever it was told: it parsed
// the URL, did not consult the allowlist, and forwarded whatever headers the
// caller supplied. What is asserted here is the rule that replaced that -- the
// same allowlist, through the same predicate, checked before anything can open
// a socket -- plus the two header decisions that go with it.
//
// The one thing these tests cannot do is prove a dial did not happen by looking
// at a socket, because there is no tsnet node in a host test: `node` is nil, so
// the dial path is unreachable by construction. What they pin down instead is
// the ordering, which is the property that actually matters -- an off-allowlist
// target returns the allowlist error even when no node is running, which is only
// possible if the gate ran before the node was consulted. Reorder the check and
// these tests fail. A real netstack dial belongs to integration_test.go, which
// needs a tailnet and skips without one.

import (
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

type fetchDoc struct {
	Status int    `json:"status"`
	Error  string `json:"error"`
}

func decodeFetchDoc(t *testing.T, raw string) fetchDoc {
	t.Helper()
	var doc fetchDoc
	if err := json.Unmarshal([]byte(raw), &doc); err != nil {
		t.Fatalf("undecodable fetch document %q: %v", raw, err)
	}
	return doc
}

func TestFetchTargetValidation(t *testing.T) {
	if msg := SetSecurityConfig(`{"allowedTargets":["http://100.101.102.103:3080"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}

	cases := []struct {
		name, target, wantSubstring string
	}{
		{"allowlisted", "http://100.101.102.103:3080/api/health", ""},
		{"allowlisted, https on the same host is a different origin", "https://100.101.102.103:3080/x", "not allowed"},
		{"another port", "http://100.101.102.103:9999/x", "not allowed"},
		{"another host", "http://169.254.169.254/latest/meta-data/", "not allowed"},
		{"a public name", "http://example.com/", "not allowed"},
		{"a path-only target", "/api/health", "absolute http(s)"},
		{"an opaque scheme", "file:///etc/passwd", "absolute http(s)"},
		{"another scheme", "ftp://100.101.102.103:3080/", "absolute http(s)"},
		{"no scheme", "100.101.102.103:3080", "malformed URL"},
		{"empty", "", "no target URL"},
		{"whitespace", "   ", "no target URL"},
	}
	for _, testCase := range cases {
		_, err := fetchTarget(testCase.target, "")
		if testCase.wantSubstring == "" {
			if err != nil {
				t.Errorf("%s: fetchTarget(%q) refused an allowlisted target: %v", testCase.name, testCase.target, err)
			}
			continue
		}
		if err == nil {
			t.Errorf("%s: fetchTarget(%q) accepted a target it must refuse", testCase.name, testCase.target)
			continue
		}
		if !strings.Contains(err.Error(), testCase.wantSubstring) {
			t.Errorf("%s: fetchTarget(%q) = %q, want it to mention %q", testCase.name, testCase.target, err, testCase.wantSubstring)
		}
	}
}

// The gate has to answer before anything else does, and the shape of the
// refusal is what proves it: with no node running, an off-allowlist target gets
// the allowlist error rather than "tailnet node is not running".
func TestFetchRefusesATargetOutsideTheAllowlistBeforeItWouldDial(t *testing.T) {
	if msg := SetSecurityConfig(`{"allowedTargets":["http://100.101.102.103:3080"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}

	// 169.254.169.254 is the cloud metadata address: the canonical SSRF target,
	// and one that would hang rather than fail fast if it were ever dialled.
	started := time.Now()
	doc := decodeFetchDoc(t, Fetch("http://169.254.169.254/latest/meta-data/", "GET", "", "", 0, 0))
	elapsed := time.Since(started)

	if doc.Error == "" {
		t.Fatal("Fetch accepted a target outside the allowlist")
	}
	if !strings.Contains(doc.Error, "not allowed") {
		t.Errorf("the refusal does not name the allowlist rule: %q", doc.Error)
	}
	if strings.Contains(doc.Error, "node is not running") {
		t.Errorf("the node check ran before the allowlist gate: %q", doc.Error)
	}
	if doc.Status != 0 {
		t.Errorf("status = %d for a refused request, want 0", doc.Status)
	}
	// A dial to a non-routable address takes as long as its timeout; a refusal
	// that reaches the network at all would not come back this fast.
	if elapsed > time.Second {
		t.Errorf("the refusal took %s; it should not have touched the network", elapsed)
	}
}

// The other half of the ordering assertion: an allowlisted target gets past the
// gate and fails at the next thing in line, which with no node is the node
// check. Between these two tests, "the gate is first" is pinned from both sides.
func TestFetchLetsAnAllowlistedTargetThroughTheGate(t *testing.T) {
	if msg := SetSecurityConfig(`{"allowedTargets":["http://100.101.102.103:3080"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}

	doc := decodeFetchDoc(t, Fetch("http://100.101.102.103:3080/api/health", "GET", "", "", 0, 0))
	if strings.Contains(doc.Error, "not allowed") {
		t.Errorf("an allowlisted target was refused by the allowlist: %q", doc.Error)
	}
	if !strings.Contains(doc.Error, "node is not running") {
		t.Errorf("error = %q, want the node check -- an allowlisted target must clear the gate", doc.Error)
	}
}

// Fail closed is the property the whole policy rests on, so it is asserted
// against Fetch too and not only against StartProxy.
func TestFetchRefusesEverythingWithoutASecurityConfig(t *testing.T) {
	if msg := SetSecurityConfig(""); msg != "" {
		t.Fatalf("clearing the config failed: %s", msg)
	}
	doc := decodeFetchDoc(t, Fetch("http://100.101.102.103:3080/", "GET", "", "", 0, 0))
	if !strings.Contains(doc.Error, "not allowed") {
		t.Errorf("error = %q; with no allowlist installed Fetch must refuse everything", doc.Error)
	}
}

// The caller does not get to choose the authority. Go writes the Host header
// from Request.Host, so the plain spelling is inert; the `Host:` spelling
// reaches the wire as a literal `Host::` line that some servers and proxies read
// as an authority -- and either way it is a request to address a host the caller
// was not granted.
// Host is not the only header that names a destination: the hop-describing
// headers are dropped here too, from the same list the reverse proxy's Rewrite
// uses.
func TestApplyFetchHeadersIgnoresTheHostHeaderInBothSpellings(t *testing.T) {
	request, err := http.NewRequest(http.MethodGet, "http://100.101.102.103:3080/api/health", nil)
	if err != nil {
		t.Fatalf("cannot build the request: %v", err)
	}
	applyFetchHeaders(request, map[string]string{
		"Host":               "evil.example",
		"Host:":              "evil.example",
		"X-Forwarded-Host":   "evil.example",
		"X-Forwarded-For":    "198.51.100.7",
		"X-Forwarded-Proto":  "https",
		"X-Forwarded-Port":   "443",
		"X-Forwarded-Server": "evil.example",
		"X-Forwarded-Prefix": "/evil",
		"Forwarded":          "for=198.51.100.7;host=evil.example",
		"X-Real-IP":          "198.51.100.7",
		"X-Test":             "kept",
		"Cookie":             "dsh_proxy=abc123; theme=dark",
		"Referer":            "http://127.0.0.1:1/",
	})

	// net/http seeds Request.Host from the URL, which Fetch built from the
	// allowlisted target. The header map must not be able to move it: this is the
	// assertion that fails if someone ever "helpfully" wires a `Host` header into
	// Request.Host.
	if want := "100.101.102.103:3080"; request.Host != want {
		t.Errorf("Request.Host = %q, want the allowlisted target's authority %q left alone", request.Host, want)
	}
	if got := request.Header.Get("Host"); got != "" {
		t.Errorf("a Host header survived: %q", got)
	}
	if _, ok := request.Header["Host:"]; ok {
		t.Error("the `Host:` spelling survived; it reaches the wire as a literal Host:: line")
	}
	// The reverse proxy has stripped this whole family since it was written with
	// an explicit list; Fetch had its own header path and stripped none of it.
	// That drift is why both now read forwardedHeaders.
	for _, header := range forwardedHeaders {
		if got := request.Header.Get(header); got != "" {
			t.Errorf("%s = %q survived; Fetch must drop the same headers the proxy does", header, got)
		}
	}
	// Everything else is forwarded, Cookie included: the only hosts reachable
	// from Fetch are allowlisted ones, and the jar that completes a 303 +
	// Set-Cookie handshake is host-scoped, so this carries exactly the
	// credential the shell obtained from a host it was allowed to talk to.
	if got := request.Header.Get("X-Test"); got != "kept" {
		t.Errorf("X-Test = %q, want ordinary headers forwarded", got)
	}
	if got := request.Header.Get("Cookie"); got != "dsh_proxy=abc123; theme=dark" {
		t.Errorf("Cookie = %q, want it forwarded to an allowlisted target", got)
	}
	if got := request.Header.Get("Referer"); got != "http://127.0.0.1:1/" {
		t.Errorf("Referer = %q; Fetch forwards what the caller sent", got)
	}
}

// A redirect's destination is chosen by the host that answered, not by the
// caller, so the policy is applied again on every hop. Without this a single 302
// would turn Fetch -- and the netstack behind it -- into a request forwarder for
// any address the target names.
func TestFetchRedirectPolicyChecksEveryHop(t *testing.T) {
	if msg := SetSecurityConfig(`{"allowedTargets":["http://a.example:1","http://b.example:2"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}
	original := mustParseURL(t, "http://a.example:1/")

	newHop := func(t *testing.T, raw string, headers map[string]string) *http.Request {
		t.Helper()
		request, err := http.NewRequest(http.MethodGet, raw, nil)
		if err != nil {
			t.Fatalf("cannot build the hop: %v", err)
		}
		for name, value := range headers {
			request.Header.Set(name, value)
		}
		return request
	}

	headers := map[string]string{"Authorization": "Bearer secret", "X-Test": "1"}
	// `via` is the chain of requests already made, as net/http passes it to
	// CheckRedirect. Only its length and the original URL matter here.
	via := []*http.Request{newHop(t, "http://a.example:1/", nil)}

	// Off the allowlist: refused, and nothing about the request is touched on the
	// way out.
	refused := newHop(t, "http://elsewhere.example/steal", headers)
	err := fetchRedirectAllowed(refused, via, original, headers)
	if err == nil {
		t.Fatal("a redirect off the allowlist was allowed")
	}
	if !strings.Contains(err.Error(), "allowlist") {
		t.Errorf("the refusal does not name the rule: %q", err)
	}
	if refused.Header.Get("Authorization") != "Bearer secret" {
		t.Error("the refused hop was modified; a refusal should not touch it")
	}

	// A second allowlisted origin is reachable, but the caller's headers were
	// chosen for the host that was asked for, not the one that answered.
	other := newHop(t, "http://b.example:2/next", headers)
	if err := fetchRedirectAllowed(other, via, original, headers); err != nil {
		t.Fatalf("a hop to another allowlisted origin was refused: %v", err)
	}
	for _, header := range []string{"Authorization", "X-Test"} {
		if got := other.Header.Get(header); got != "" {
			t.Errorf("%s = %q survived a cross-host hop", header, got)
		}
	}

	// Same host, so nothing is dropped.
	same := newHop(t, "http://a.example:1/next", headers)
	if err := fetchRedirectAllowed(same, via, original, headers); err != nil {
		t.Fatalf("a same-host hop was refused: %v", err)
	}
	if got := same.Header.Get("Authorization"); got != "Bearer secret" {
		t.Errorf("Authorization = %q; a same-host hop keeps the caller's headers", got)
	}

	// The hop cap is unchanged and still applies.
	longChain := make([]*http.Request, maxRedirects)
	if err := fetchRedirectAllowed(same, longChain, original, headers); err == nil {
		t.Error("the redirect cap was not enforced")
	}
}

// originOf is the string both entry points compare against the allowlist, so it
// has to be the same string for both; this is the cheap lock on that.
func TestOriginOfMatchesTheAllowlistSpelling(t *testing.T) {
	if msg := SetSecurityConfig(`{"allowedTargets":["http://100.101.102.103:3080"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}
	parsed, err := url.Parse("http://100.101.102.103:3080/api/x?y=1")
	if err != nil {
		t.Fatalf("cannot parse the target: %v", err)
	}
	if origin := originOf(parsed); !targetAllowed(origin) {
		t.Errorf("originOf produced %q, which the allowlist does not recognise", origin)
	}
	if originOf(nil) != "" {
		t.Errorf("originOf(nil) = %q, want the empty string", originOf(nil))
	}
}
