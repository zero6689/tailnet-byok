package mobile

// Tests for the security policy and the proxy behaviours it governs: the
// fixed-target allowlist, the one-shot bootstrap token, rate limiting, and the
// redaction of emails and addresses from log output.

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"
)

// allowTarget installs a security policy permitting exactly `target`, so a test
// can start a proxy without a pre-configured process.
func allowTarget(t *testing.T, target string) {
	t.Helper()
	if msg := SetSecurityConfig(`{"allowedTargets":["` + originOfTarget(t, target) + `"]}`); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}
}

func originOfTarget(t *testing.T, target string) string {
	t.Helper()
	u, err := url.Parse(target)
	if err != nil {
		t.Fatalf("test target is not a URL: %v", err)
	}
	return u.Scheme + "://" + u.Host
}

func TestStartProxyFailsClosedWithoutASecurityConfig(t *testing.T) {
	if msg := SetSecurityConfig(""); msg != "" {
		t.Fatalf("clearing the config failed: %s", msg)
	}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer upstream.Close()

	doc := decodeProxyDoc(t, StartProxy(upstream.URL))
	if doc.Running {
		t.Error("StartProxy started with no security config; it must fail closed")
	}
}

func TestStartProxyRefusesATargetOutsideTheAllowlist(t *testing.T) {
	allowed := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer allowed.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer other.Close()

	allowTarget(t, allowed.URL)
	doc := decodeProxyDoc(t, StartProxy(other.URL))
	if doc.Running {
		t.Error("StartProxy forwarded to a target outside the allowlist")
	}
	if !strings.Contains(doc.Error, "not allowed") {
		t.Errorf("the refusal did not name the fixed-target rule: %q", doc.Error)
	}
}

func TestProxyBootstrapTokenIsOneShot(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok"))
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	// First use consumes the token and plants the session cookie.
	first, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("first navigation failed: %v", err)
	}
	first.Body.Close()
	if first.StatusCode != http.StatusOK {
		t.Fatalf("first navigation got %d", first.StatusCode)
	}

	var cookieVal string
	for _, cookie := range first.Cookies() {
		if cookie.Name == proxyTokenCookie {
			cookieVal = cookie.Value
		}
	}
	if cookieVal == "" {
		t.Fatal("the first navigation did not plant the session cookie")
	}

	// The same token URL replayed by a stranger (no cookie jar) must fail:
	// "same token, second visit" is the exact case the one-shot rule exists for.
	second, err := http.Get(doc.URL)
	if err != nil {
		t.Fatalf("replayed token request failed: %v", err)
	}
	second.Body.Close()
	if second.StatusCode != http.StatusForbidden {
		t.Errorf("replayed bootstrap token got %d, want 403 (one-shot)", second.StatusCode)
	}

	// The cookie it minted is the standing credential, and still works alone.
	req, err := http.NewRequest(http.MethodGet, "http://"+loopbackAuthority(t, doc)+"/", nil)
	if err != nil {
		t.Fatalf("cannot build the cookie request: %v", err)
	}
	req.AddCookie(&http.Cookie{Name: proxyTokenCookie, Value: cookieVal})
	third, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("cookie request failed: %v", err)
	}
	third.Body.Close()
	if third.StatusCode != http.StatusOK {
		t.Errorf("cookie-authorised request got %d, want 200", third.StatusCode)
	}
}

func TestProxyRateLimitsRequests(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	defer upstream.Close()

	// Tight on purpose: one request per second, burst of one, so a flood is
	// guaranteed to trip it within a handful of requests.
	cfg := `{"allowedTargets":["` + originOfTarget(t, upstream.URL) + `"],"rateLimitPerSecond":1,"rateBurst":1}`
	if msg := SetSecurityConfig(cfg); msg != "" {
		t.Fatalf("SetSecurityConfig refused: %s", msg)
	}

	doc := decodeProxyDoc(t, StartProxy(upstream.URL))
	if !doc.Running {
		t.Fatalf("proxy refused to start: %s", doc.Error)
	}
	t.Cleanup(func() { StopProxy() })

	base := "http://" + loopbackAuthority(t, doc) + "/"
	saw429 := false
	for i := 0; i < 10; i++ {
		resp, err := http.Get(base)
		if err != nil {
			continue
		}
		resp.Body.Close()
		if resp.StatusCode == http.StatusTooManyRequests {
			saw429 = true
			// The limiter refills continuously, so the wait it is reporting is
			// transient by construction; without the hint the browser guesses.
			if retry := resp.Header.Get("Retry-After"); retry != "1" {
				t.Errorf("a 429 carried Retry-After %q, want \"1\"", retry)
			}
			break
		}
	}
	if !saw429 {
		t.Error("ten rapid requests were all accepted; the rate limiter is not limiting")
	}
}

func TestRedactMasksEmailsAndAddresses(t *testing.T) {
	in := "user someone@example.com from 192.0.2.20 to 100.101.102.103 via 127.0.0.1 node 100.101.102.104"
	out := redact(in)

	if strings.Contains(out, "someone@example.com") {
		t.Error("the email local part survived redaction")
	}
	if !strings.Contains(out, "***@example.com") {
		t.Errorf("the email domain should remain as context, got %q", out)
	}
	if strings.Contains(out, "192.0.2.20") {
		t.Error("a LAN address survived redaction")
	}
	if !strings.Contains(out, "192.0.2.*") {
		t.Errorf("the LAN address should lose its host octet, got %q", out)
	}
	// Tailnet CGNAT and loopback are the addresses a diagnostic needs to name;
	// they identify nothing outside the tailnet and must stay readable.
	for _, keep := range []string{"100.101.102.103", "100.101.102.104", "127.0.0.1"} {
		if !strings.Contains(out, keep) {
			t.Errorf("tailnet/loopback address %s was masked; it should stay for diagnostics", keep)
		}
	}
}

// ---------------------------------------------------------------------------
// Accepting a policy: whole or not at all
// ---------------------------------------------------------------------------
//
// A candidate policy can be wrong in three ways -- the wrong shape, the wrong
// value, the wrong allowlist -- and each of the tests below also asserts what a
// rejection must NOT do: change the policy that is already installed. The old
// behaviour for a value it disliked was to substitute the default, which for a
// negative cookieMaxAgeSeconds or maxConnections meant running *looser* than
// what the caller asked for.

func TestSetSecurityConfigRejectsUnknownMalformedAndTrailingDocuments(t *testing.T) {
	// Deliberately tighter than the defaults, so a silent substitution would be
	// visible in the assertion at the end.
	const baseline = `{"allowedTargets":["http://100.101.102.103:3080"],"cookieMaxAgeSeconds":3600,"maxConnections":8}`
	if msg := SetSecurityConfig(baseline); msg != "" {
		t.Fatalf("the baseline config was refused: %s", msg)
	}

	cases := []struct{ name, document string }{
		{"misspelled allowlist field", `{"allowedTarget":"http://100.101.102.103:3080"}`},
		{"unknown field", `{"allowedTargets":["http://100.101.102.103:3080"],"disableAuth":true}`},
		{"wrong type", `{"allowedTargets":["http://100.101.102.103:3080"],"maxConnections":"many"}`},
		{"not an object", `["http://100.101.102.103:3080"]`},
		{"truncated", `{"allowedTargets":[`},
		{"trailing document", `{"allowedTargets":["http://100.101.102.103:3080"]}{"maxConnections":1}`},
		{"not JSON at all", `allowedTargets=http://100.101.102.103:3080`},
	}
	for _, testCase := range cases {
		if msg := SetSecurityConfig(testCase.document); msg == "" {
			t.Errorf("%s: SetSecurityConfig accepted %s", testCase.name, testCase.document)
		}
	}

	// Whatever was refused, the baseline is still the policy in force.
	cfg := currentSecurityConfig()
	if cfg.CookieMaxAgeSeconds != 3600 || cfg.MaxConnections != 8 {
		t.Errorf("a rejected config changed the installed policy: %+v", cfg)
	}
	if !targetAllowed("http://100.101.102.103:3080") {
		t.Error("a rejected config dropped the installed allowlist")
	}
}

// Two shapes that a first pass at strict decoding let through: a field that is
// present and explicitly null, and a stray closing brace.
func TestSetSecurityConfigRejectsNullFieldsAndStrayBraces(t *testing.T) {
	const baseline = `{"allowedTargets":["http://100.101.102.103:3080"],"cookieMaxAgeSeconds":3600,"maxConnections":8}`
	if msg := SetSecurityConfig(baseline); msg != "" {
		t.Fatalf("the baseline config was refused: %s", msg)
	}

	cases := []struct{ name, document string }{
		// null is a caller declining to name a value it named, and the pointer
		// fields decode it to the same nil an absent field produces -- so it used
		// to slide through as "use the default", which is not what the contract
		// says and not what a caller that wrote null meant.
		{"null cookie lifetime", `{"allowedTargets":["http://100.101.102.103:3080"],"cookieMaxAgeSeconds":null}`},
		{"null rate limit", `{"allowedTargets":["http://100.101.102.103:3080"],"rateLimitPerSecond":null}`},
		{"null burst", `{"allowedTargets":["http://100.101.102.103:3080"],"rateBurst":null}`},
		{"null connection cap", `{"allowedTargets":["http://100.101.102.103:3080"],"maxConnections":null}`},
		{"null allowlist", `{"allowedTargets":null}`},
		{"a bare null document", `null`},
		// Decoder.More reported "no more elements" for a stray closing brace,
		// because `}` reads as the end of the object being parsed. Reading the
		// next token instead makes all of these a trailing-data error.
		{"a stray closing brace", `{"allowedTargets":["http://100.101.102.103:3080"]}}`},
		{"a stray closing bracket", `{"allowedTargets":["http://100.101.102.103:3080"]}]`},
		{"a second document", `{"allowedTargets":["http://100.101.102.103:3080"]}{"maxConnections":1}`},
		{"a trailing scalar", `{"allowedTargets":["http://100.101.102.103:3080"]} 5`},
	}
	for _, testCase := range cases {
		msg := SetSecurityConfig(testCase.document)
		if msg == "" {
			t.Errorf("%s: SetSecurityConfig accepted %s", testCase.name, testCase.document)
			continue
		}
		if !strings.Contains(msg, "invalid security config") {
			t.Errorf("%s: the refusal does not look like a config error: %q", testCase.name, msg)
		}
	}

	cfg := currentSecurityConfig()
	if cfg.CookieMaxAgeSeconds != 3600 || cfg.MaxConnections != 8 {
		t.Errorf("a rejected document changed the installed policy: %+v", cfg)
	}
}

func TestSetSecurityConfigRefusesOutOfRangeValuesWithoutWideningThePolicy(t *testing.T) {
	const baseline = `{"allowedTargets":["http://100.101.102.103:3080"],"cookieMaxAgeSeconds":3600,` +
		`"rateLimitPerSecond":10,"rateBurst":20,"maxConnections":8}`
	if msg := SetSecurityConfig(baseline); msg != "" {
		t.Fatalf("the baseline config was refused: %s", msg)
	}

	fields := []struct {
		name         string
		below, above int
	}{
		{"cookieMaxAgeSeconds", minCookieMaxAgeSeconds - 1, maxCookieMaxAgeSeconds + 1},
		{"rateLimitPerSecond", minRateLimitPerSecond - 1, maxRateLimitPerSecond + 1},
		{"rateBurst", minRateBurst - 1, maxRateBurst + 1},
		{"maxConnections", minMaxConnections - 1, maxMaxConnections + 1},
	}
	for _, field := range fields {
		// -1 and 0 are the shapes the old code rewrote into the default. A
		// negative lifetime is a request to tighten the policy, and answering it
		// with an 8h session is the widening this test exists to prevent.
		for _, value := range []int{field.below, field.above, -1, 0} {
			document := `{"allowedTargets":["http://100.101.102.103:3080"],"` + field.name + `":` + strconv.Itoa(value) + `}`
			msg := SetSecurityConfig(document)
			if msg == "" {
				t.Errorf("SetSecurityConfig accepted %s = %d", field.name, value)
				continue
			}
			if !strings.Contains(msg, field.name) {
				t.Errorf("the refusal of %s = %d does not name the field: %q", field.name, value, msg)
			}
		}
	}

	cfg := currentSecurityConfig()
	if cfg.CookieMaxAgeSeconds != 3600 || cfg.RateLimitPerSecond != 10 || cfg.RateBurst != 20 || cfg.MaxConnections != 8 {
		t.Errorf("a rejected config replaced the installed policy with a looser one: %+v", cfg)
	}
}

func TestSetSecurityConfigAppliesDefaultsToAbsentFieldsAndAcceptsTheBounds(t *testing.T) {
	// The shell sends only the allowlist (Tailnet.java, securityConfigJson), so
	// "absent" has to keep meaning "the default" -- otherwise every existing
	// caller would be refused for omitting fields it never knew about.
	if msg := SetSecurityConfig(`{"allowedTargets":["http://100.101.102.103:3080"]}`); msg != "" {
		t.Fatalf("a config naming only the allowlist was refused: %s", msg)
	}
	cfg := currentSecurityConfig()
	if cfg.CookieMaxAgeSeconds != defaultCookieMaxAgeSeconds ||
		cfg.RateLimitPerSecond != defaultRateLimitPerSecond ||
		cfg.RateBurst != defaultRateBurst ||
		cfg.MaxConnections != defaultMaxConnections {
		t.Errorf("absent fields did not select the defaults: %+v", cfg)
	}

	// The bounds themselves are usable: a deployment tightening every knob to
	// its documented minimum must be accepted, not refused for being eager.
	tight := `{"allowedTargets":["http://100.101.102.103:3080"],` +
		`"cookieMaxAgeSeconds":60,"rateLimitPerSecond":1,"rateBurst":1,"maxConnections":1}`
	if msg := SetSecurityConfig(tight); msg != "" {
		t.Errorf("a config at every documented lower bound was refused: %s", msg)
	}

	// The empty document is the documented "no policy": everything defaulted and
	// an empty allowlist, which allows nothing.
	if msg := SetSecurityConfig(""); msg != "" {
		t.Fatalf("clearing the config failed: %s", msg)
	}
	if cfg := currentSecurityConfig(); len(cfg.AllowedTargets) != 0 {
		t.Errorf("an empty config left an allowlist behind: %v", cfg.AllowedTargets)
	}
	if targetAllowed("http://100.101.102.103:3080") {
		t.Error("an empty config still allows a target; it must fail closed")
	}
}

func TestSetSecurityConfigRefusesBadAllowlistEntriesWithoutEchoingCredentials(t *testing.T) {
	for _, entry := range []string{"ftp://100.101.102.103:3080", "not a url", "100.101.102.103:3080", ""} {
		document := `{"allowedTargets":["` + entry + `"]}`
		if msg := SetSecurityConfig(document); msg == "" {
			t.Errorf("SetSecurityConfig accepted the allowlist entry %q", entry)
		}
	}

	// The refusal names the entry so the user can see which line to fix, but a
	// credential in a URL's userinfo is not something to print: this message is
	// rendered in the app.
	msg := SetSecurityConfig(`{"allowedTargets":["ftp://user:hunter2@100.101.102.103:3080"]}`)
	if msg == "" {
		t.Fatal("SetSecurityConfig accepted a non-http allowlist entry")
	}
	if strings.Contains(msg, "hunter2") {
		t.Errorf("the refusal echoed a credential from the allowlist entry: %q", msg)
	}
	if !strings.Contains(msg, "100.101.102.103") {
		t.Errorf("the refusal should still name the entry, minus its credential: %q", msg)
	}
}

// ---------------------------------------------------------------------------
// What the shell is handed, and what the logs must never contain
// ---------------------------------------------------------------------------

// The status document is a contract with the shell: Tailnet.java reads
// `running`, `url`, `cookie` and `error`, and copies the cookie into
// `proxyToken` so that its own HTTP calls to the loopback listener can present
// `Cookie: dsh_proxy=...` -- the listener refuses everything else. So the field
// stays, and the key names stay. The protection is that the document is never
// logged; this test locks both halves of that.
func TestProxyStatusDocumentKeepsTheShellsCookieAndLeavesNoCredentialInTheLogs(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok"))
	}))
	defer upstream.Close()

	doc := startProxyFor(t, upstream.URL)

	var status map[string]any
	if err := json.Unmarshal([]byte(ProxyStatus()), &status); err != nil {
		t.Fatalf("ProxyStatus is not a JSON object: %v", err)
	}
	for _, key := range []string{"running", "url", "cookie", "target", "error"} {
		if _, ok := status[key]; !ok {
			t.Errorf("the status document lost the %q key; the shell reads it", key)
		}
	}
	session, _ := status["cookie"].(string)
	if session == "" {
		t.Fatal("the status document carries no cookie; the shell cannot authorise its own calls")
	}

	// It is genuinely the standing credential: presenting it authorises a
	// request that carries no query token at all.
	request, err := http.NewRequest(http.MethodGet, "http://"+loopbackAuthority(t, doc)+"/", nil)
	if err != nil {
		t.Fatalf("cannot build the cookie request: %v", err)
	}
	request.AddCookie(&http.Cookie{Name: proxyTokenCookie, Value: session})
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("cookie request failed: %v", err)
	}
	response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Errorf("a request carrying the document's cookie got %d, want 200", response.StatusCode)
	}

	// No logging exists on the proxy path at all, and this is the regression
	// lock for that: after a whole session, neither credential is in the ring
	// the diagnostics page prints verbatim.
	logs := Logs()
	if strings.Contains(logs, session) {
		t.Errorf("the session cookie reached the log ring: %s", logs)
	}
	token := mustParseURL(t, doc.URL).Query().Get(proxyTokenParam)
	if token == "" {
		t.Fatal("the proxy URL carries no bootstrap token; the test cannot check it")
	}
	if strings.Contains(logs, token) {
		t.Errorf("the bootstrap token reached the log ring: %s", logs)
	}

	// Second line of defence: the token is inside `url` as a `dshproxy=` pair,
	// so the name=value rule catches it if the document ever does reach the
	// redactor. (A bare JSON field value is not something such a rule can
	// recognise, which is another reason the document itself must never be
	// logged -- see document in proxy.go.)
	if masked := redact(ProxyStatus()); strings.Contains(masked, token) {
		t.Errorf("redact passes the bootstrap token through: %s", masked)
	}
}

func TestRedactMasksProxyCredentialValues(t *testing.T) {
	const secret = "SWd9kQ2mZx7vB4nP1sT6yU3rE8wA5cD0"
	in := "load http://127.0.0.1:41235/?dshproxy=" + secret +
		" | Cookie: dsh_proxy=" + secret + "; Path=/" +
		" | mydshproxy=" + secret
	out := redact(in)

	if !strings.Contains(out, "dshproxy=<redacted>") {
		t.Errorf("the one-shot query token was not masked: %q", out)
	}
	if !strings.Contains(out, "dsh_proxy=<redacted>") {
		t.Errorf("the session cookie value was not masked: %q", out)
	}
	// Exactly one copy of the value may remain, in the lookalike parameter: if
	// either real pair leaked, there would be two.
	if got := strings.Count(out, secret); got != 1 {
		t.Errorf("the credential value appears %d time(s) after redaction, want only the lookalike: %q", got, out)
	}
	if !strings.Contains(out, "mydshproxy="+secret) {
		t.Errorf("a parameter that merely ends in the same letters was masked: %q", out)
	}
}

// The percent-encoded separator is the same capability as the literal one: a URL
// that has been through one more encoding pass -- `?dshproxy%3D<token>` -- is
// exactly the shape that ends up pasted into a report or a log line.
func TestRedactMasksThePercentEncodedCredentialSeparator(t *testing.T) {
	const secret = "SWd9kQ2mZx7vB4nP1sT6yU3rE8wA5cD0"
	for _, in := range []string{
		"http://127.0.0.1:1/?dshproxy%3D" + secret,
		"http://127.0.0.1:1/?dshproxy%3d" + secret,
		"dsh_proxy%3D" + secret,
		"?dshproxy%3D" + secret + "&dshproxy=" + secret,
	} {
		out := redact(in)
		if strings.Contains(out, secret) {
			t.Errorf("redact(%q) = %q; the credential value survived", in, out)
		}
		if !strings.Contains(out, "<redacted>") {
			t.Errorf("redact(%q) = %q; nothing was masked", in, out)
		}
	}
}

// The smallest input that used to hang this function. The prefix loop rewrote
// `tskey-` back into the string and then searched from the beginning again, so
// the same occurrence was found forever: the value grew three bytes per pass and
// the call never returned. One of the callers is Fetch, which redacts the
// target's response body, so a target could trigger it remotely -- hence the
// goroutine and the timeout here, which turn a regression into a test failure
// instead of a hung lane.
func TestRedactReturnsForEveryKeyPrefix(t *testing.T) {
	cases := []struct{ name, in, want string }{
		{"tskey with no payload", "tskey-", "tskey-***"},
		{"tskey with a payload", "tskey-auth-abcdefgh", "tskey-***"},
		{"hskey with no payload", "hskey-", "hskey-***"},
		{"hskey with a payload", "hskey-0123456789", "hskey-***"},
		{"tsclientsecret with no payload", "tsclientsecret-", "tsclientsecret-***"},
		{"tsclientsecret with a payload", "tsclientsecret-abcd", "tsclientsecret-***"},
		{"mid-sentence", "load key tskey- from disk", "load key tskey-*** from disk"},
		{"two occurrences of one prefix", "a tskey- b tskey-xyz c", "a tskey-*** b tskey-*** c"},
		{"two different prefixes", "tskey- hskey-", "tskey-*** hskey-***"},
		{"a prefix-shaped fragment at the very end", "trying hskey-", "trying hskey-***"},
	}
	for _, testCase := range cases {
		done := make(chan string, 1)
		go func() { done <- redact(testCase.in) }()
		select {
		case got := <-done:
			if got != testCase.want {
				t.Errorf("%s: redact(%q) = %q, want %q", testCase.name, testCase.in, got, testCase.want)
			}
		case <-time.After(2 * time.Second):
			t.Fatalf("%s: redact(%q) did not return; the prefix scan is looping again", testCase.name, testCase.in)
		}
	}

	// The same rule one level up: a response body is what a target controls, and
	// it must not be able to make the redactor spin. The full key prefix is
	// enough on its own, so no tailnet is needed to prove it.
	body := strings.Repeat("tskey-", 64)
	done := make(chan string, 1)
	go func() { done <- redact(body, "") }()
	select {
	case got := <-done:
		if strings.Contains(got, "tskey-tskey-") {
			t.Errorf("the repeated prefixes were not masked: %q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("redact did not return on a body made of key prefixes")
	}
}

func TestRedactMasksIPv6ExceptLoopbackAndTheTailnetULA(t *testing.T) {
	in := "peer 2001:db8:1234:5678:9abc:def0:1234:5678 via [fd7a:115c:a1e0:0:1:2:3:4]:41641 and [::1]:3080; link fe80::1"
	out := redact(in)

	// The whole interface identifier has to go, not just its last hextet: the
	// earlier rule kept seven eighths of the part bound to the device.
	for _, identifier := range []string{"9abc", "def0", "1234:5678:9abc"} {
		if strings.Contains(out, identifier) {
			t.Errorf("the interface identifier %q survived: %q", identifier, out)
		}
	}
	// The /64 prefix is kept, which is the IPv6 analogue of the three octets the
	// IPv4 rule keeps.
	if !strings.Contains(out, "2001:0db8:1234:5678:*") {
		t.Errorf("the global address did not keep exactly its /64 prefix: %q", out)
	}
	// The IPv6 half of the rule the IPv4 half already had: the tailnet's own ULA
	// range and loopback are what a diagnostic needs to name, and they identify
	// nothing outside the tailnet.
	if !strings.Contains(out, "fd7a:115c:a1e0:0:1:2:3:4") {
		t.Errorf("the tailnet ULA address was masked; it should stay for diagnostics: %q", out)
	}
	if !strings.Contains(out, "[::1]:3080") {
		t.Errorf("loopback was masked; it should stay for diagnostics: %q", out)
	}
	if strings.Contains(out, "fe80::1") {
		t.Errorf("a link-local address kept its interface identifier: %q", out)
	}
	if !strings.Contains(out, "fe80:0000:0000:0000:*") {
		t.Errorf("a link-local address was not reduced to its /64 prefix form: %q", out)
	}

	// The shape match above is deliberately loose, so this is the half that
	// matters: a clock time has the same number of colons as a short address and
	// has to come out unmangled.
	if got := redact("started 12:30:45 and finished"); got != "started 12:30:45 and finished" {
		t.Errorf("a clock time was mangled by the IPv6 rule: %q", got)
	}
}

func TestRedactMasksTailnetDNSNames(t *testing.T) {
	in := "node byok-test.tail1a2b3c.ts.net. dialled 100.101.102.103"
	out := redact(in)

	if strings.Contains(out, "tail1a2b3c") || strings.Contains(out, "byok-test.") {
		t.Errorf("the tailnet name survived redaction: %q", out)
	}
	if !strings.Contains(out, "***.ts.net") {
		t.Errorf("the ts.net suffix should remain as context: %q", out)
	}
	// The existing intent still holds: a tailnet address stays readable.
	if !strings.Contains(out, "100.101.102.103") {
		t.Errorf("a tailnet address was masked; it should stay for diagnostics: %q", out)
	}
}
