package mobile

// security.go: the security policy the loopback proxy enforces, supplied by the
// caller rather than hardcoded here.
//
// # Why it exists
//
// The proxy is an authenticated path to a user's server, so every property that
// decides how open that path is -- what it may forward to, how long its session
// credential lives, how fast it will accept requests, which headers it forwards
// -- is a policy decision, not a constant. One config object read at proxy
// start keeps those decisions in a single place, reviewable as one unit, and
// lets the same binary serve a stricter or looser deployment without being
// recompiled.
//
// # Fail closed, and never fail open by accident
//
// This type is what stands between the caller and an authenticated path into a
// tailnet host, so two rules apply to how a candidate policy is accepted:
//
//  1. Nothing is installed unless the whole candidate is valid. Validation and
//     installation are separate steps, and the swap is a single struct
//     assignment under one lock, so a rejected policy can never be half-applied
//     over a working one.
//  2. A value the caller actually supplied is never quietly replaced by a
//     default. "0 means default" and "0 means an error" look the same in a Go
//     int, so the wire form uses pointers: an absent field selects the default,
//     and a present field outside its documented range is refused. The version
//     of this file that predated that rule rewrote a negative
//     cookieMaxAgeSeconds into the 8h default, which turned a request to
//     tighten the policy into a silent widening of it.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"strings"
	"sync"
)

const (
	defaultCookieMaxAgeSeconds = 8 * 60 * 60 // 8h: a session, not a day
	defaultRateLimitPerSecond  = 100
	defaultRateBurst           = 200
	defaultMaxConnections      = 64

	// The bounds every supplied value has to land inside.
	//
	// They are wide on purpose: the point is not to second-guess a deployment
	// but to refuse values that cannot mean what the caller wrote. A zero or
	// negative limit is the shape a fail-open bug takes (an empty semaphore
	// admits everything, a zero lifetime expires the session immediately and
	// silently), and an unbounded one is the shape a typo takes (6048000
	// seconds is seventeen weeks, not one).
	minCookieMaxAgeSeconds = 60
	maxCookieMaxAgeSeconds = 604800
	minRateLimitPerSecond  = 1
	maxRateLimitPerSecond  = 100000
	minRateBurst           = 1
	maxRateBurst           = 100000
	minMaxConnections      = 1
	maxMaxConnections      = 4096
)

// SecurityConfig is the policy the loopback proxy enforces. Every field is
// optional; a missing field selects the default -- except AllowedTargets, where
// empty is fail-closed: with no allowlist the proxy refuses to start at all.
//
// A field that is present but outside its documented range is not a default:
// SetSecurityConfig refuses the whole policy. See securityConfigJSON for how
// "absent" is told apart from "supplied".
type SecurityConfig struct {
	// AllowedTargets lists the origins (scheme://host, port included) the proxy
	// may forward to. A target not on the list is refused before any connection
	// is made. This is the fixed-target / anti-SSRF rule, and it is enforced by
	// the same predicate (targetAllowed) on every path that can dial: the
	// reverse proxy's start and Mobile.Fetch.
	AllowedTargets []string `json:"allowedTargets"`

	// CookieMaxAgeSeconds bounds the lifetime of the session cookie minted when
	// the one-shot bootstrap token is consumed. Absent selects the default;
	// supplied values must be within [60, 604800].
	CookieMaxAgeSeconds int `json:"cookieMaxAgeSeconds"`

	// RateLimitPerSecond and RateBurst bound how fast the proxy accepts
	// requests, so a misbehaving local app cannot keep the listener busy.
	// Absent selects the defaults; supplied values must be within
	// [1, 100000].
	RateLimitPerSecond int `json:"rateLimitPerSecond"`
	RateBurst          int `json:"rateBurst"`

	// MaxConnections caps in-flight requests. Absent selects the default;
	// supplied values must be within [1, 4096].
	MaxConnections int `json:"maxConnections"`

	// ForwardReferer, when true, forwards the client's Referer to the target.
	// The default is false: the loopback hop is this app's business, not the
	// target's, and Referer is the one of the three (Host, Origin, Referer) the
	// target does not need. Host and Origin are always restated as the target's
	// authority regardless of this flag -- the target's own auth and its /api
	// browser-trust fence depend on them.
	ForwardReferer bool `json:"forwardReferer"`

	// RandomPort, when true, draws a fresh loopback port on every start instead
	// of reusing the last one. The default keeps the remembered port, which is a
	// cold-start cache win; on loopback a fresh port is not itself a security
	// boundary (a local scanner finds either just as fast).
	RandomPort bool `json:"randomPort"`
}

// securityConfigJSON is the wire form of SecurityConfig.
//
// Every numeric field is a pointer, which is the whole point of having a
// separate type: it makes "the caller did not mention this field" and "the
// caller said zero" distinguishable. Decoding straight into SecurityConfig
// cannot tell them apart, and the only safe reading of the ambiguity -- treat
// zero as absent -- is also the one that lets `{"maxConnections":0}` pass as
// the default instead of being refused.
//
// The bools stay plain because for them absent and false are the same request:
// both mean "do not do the optional thing".
type securityConfigJSON struct {
	AllowedTargets      []string `json:"allowedTargets"`
	CookieMaxAgeSeconds *int     `json:"cookieMaxAgeSeconds"`
	RateLimitPerSecond  *int     `json:"rateLimitPerSecond"`
	RateBurst           *int     `json:"rateBurst"`
	MaxConnections      *int     `json:"maxConnections"`
	ForwardReferer      bool     `json:"forwardReferer"`
	RandomPort          bool     `json:"randomPort"`
}

// decodeSecurityConfig turns a JSON document into a fully-defaulted candidate
// policy. It rejects unknown fields, wrong types and trailing data; it does not
// range-check anything (normalized does that), so the two failures a caller can
// hit are reported with the step that found them.
//
// An empty or whitespace-only document is not an error: it is the documented
// "no policy" state, which every field defaulted and an empty allowlist -- and
// an empty allowlist allows nothing.
func decodeSecurityConfig(jsonValue string) (SecurityConfig, error) {
	cfg := SecurityConfig{
		CookieMaxAgeSeconds: defaultCookieMaxAgeSeconds,
		RateLimitPerSecond:  defaultRateLimitPerSecond,
		RateBurst:           defaultRateBurst,
		MaxConnections:      defaultMaxConnections,
	}
	if strings.TrimSpace(jsonValue) == "" {
		return cfg, nil
	}

	// The document has to be an object. `Decoder.Decode` accepts a bare `null`
	// without complaint, and every field then reads as absent -- i.e. "no
	// policy" -- which is not what a caller that sent the literal null meant.
	// Catching the shape here also gives a clearer message than the type error a
	// scalar or array would produce further down.
	if !strings.HasPrefix(strings.TrimSpace(jsonValue), "{") {
		return SecurityConfig{}, fmt.Errorf("invalid security config: expected a JSON object")
	}

	decoder := json.NewDecoder(strings.NewReader(jsonValue))
	// A misspelled field -- `allowedTarget`, `rateLimitPerSec` -- must be an
	// error rather than a field left at its default. Silently ignoring it is how
	// a caller ends up believing it tightened something it never touched.
	decoder.DisallowUnknownFields()

	var wire securityConfigJSON
	if err := decoder.Decode(&wire); err != nil {
		return SecurityConfig{}, fmt.Errorf("invalid security config: %w", err)
	}
	// A second JSON document after the first would be ignored by a plain
	// Unmarshal, so the remainder is read and has to be nothing at all.
	//
	// `Decoder.More` is not the test for it, and using it was a bug: More
	// reports whether another element follows inside the *current* array or
	// object, so a stray closing brace -- `{"a":1}}` -- reads as "the object
	// just ended" and was accepted. Reading the next token is unambiguous.
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		return SecurityConfig{}, fmt.Errorf("invalid security config: trailing data after the JSON object")
	}

	// A field that is present and explicitly null is not the same request as a
	// field that is absent, and for a policy that difference is the whole point:
	// absent means "use the default", while null is a caller declining to
	// specify a value it named -- indistinguishable, in practice, from a caller
	// that got the value wrong. The pointer fields cannot tell the two apart on
	// their own (both decode to nil), so the keys are read a second time and
	// checked for the literal null.
	var present map[string]json.RawMessage
	if err := json.Unmarshal([]byte(jsonValue), &present); err != nil {
		return SecurityConfig{}, fmt.Errorf("invalid security config: %w", err)
	}
	for name, raw := range present {
		if strings.TrimSpace(string(raw)) == "null" {
			return SecurityConfig{}, fmt.Errorf("invalid security config: %s must be a value, not null", name)
		}
	}

	cfg.AllowedTargets = wire.AllowedTargets
	if wire.CookieMaxAgeSeconds != nil {
		cfg.CookieMaxAgeSeconds = *wire.CookieMaxAgeSeconds
	}
	if wire.RateLimitPerSecond != nil {
		cfg.RateLimitPerSecond = *wire.RateLimitPerSecond
	}
	if wire.RateBurst != nil {
		cfg.RateBurst = *wire.RateBurst
	}
	if wire.MaxConnections != nil {
		cfg.MaxConnections = *wire.MaxConnections
	}
	cfg.ForwardReferer = wire.ForwardReferer
	cfg.RandomPort = wire.RandomPort
	return cfg, nil
}

// normalized validates a fully-defaulted candidate policy and reduces its
// allowlist to normalized origins, in place. It returns nil only when every
// field is usable.
//
// It is deliberately all-or-nothing. The allowlist is rebuilt into a fresh
// slice and only assigned once the whole list has been accepted, so a policy
// that fails on its fourth entry leaves the candidate exactly as it was -- there
// is no window in which half a list is live.
//
// Defaults are not applied here: that already happened in decodeSecurityConfig,
// and a value that arrived out of range is an error rather than a fallback.
func (c *SecurityConfig) normalized() error {
	for _, check := range []struct {
		name     string
		value    int
		min, max int
	}{
		{"cookieMaxAgeSeconds", c.CookieMaxAgeSeconds, minCookieMaxAgeSeconds, maxCookieMaxAgeSeconds},
		{"rateLimitPerSecond", c.RateLimitPerSecond, minRateLimitPerSecond, maxRateLimitPerSecond},
		{"rateBurst", c.RateBurst, minRateBurst, maxRateBurst},
		{"maxConnections", c.MaxConnections, minMaxConnections, maxMaxConnections},
	} {
		if check.value < check.min || check.value > check.max {
			return fmt.Errorf("%s must be between %d and %d, got %d",
				check.name, check.min, check.max, check.value)
		}
	}

	normalized := make([]string, 0, len(c.AllowedTargets))
	for _, entry := range c.AllowedTargets {
		origin, err := normalizeOrigin(entry)
		if err != nil {
			// The entry is echoed so the user can see which line to fix, but
			// never verbatim: a URL can carry a credential in its userinfo, and
			// this message is shown in the app.
			return fmt.Errorf("allowed target %q: %w", redactAllowlistEntry(entry), err)
		}
		normalized = append(normalized, origin)
	}
	c.AllowedTargets = normalized
	return nil
}

// normalizeOrigin reduces a URL to its origin (scheme://host), the form the
// proxy compares targets against. A path or query is not part of the decision:
// the proxy always dials the target's own root.
func normalizeOrigin(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	u, err := url.Parse(trimmed)
	if err != nil {
		return "", err
	}
	if u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return "", fmt.Errorf("not an absolute http(s) URL")
	}
	return originOf(u), nil
}

// redactAllowlistEntry is the form of an allowlist entry that is safe to put in
// a message the user will read. Credentials in the URL's userinfo are replaced
// before anything else, because the generic redactor knows about addresses and
// key shapes but not about `user:password@host`.
func redactAllowlistEntry(raw string) string {
	if u, err := url.Parse(strings.TrimSpace(raw)); err == nil && u.User != nil {
		u.User = url.User("***")
		return redact(u.String())
	}
	return redact(raw)
}

// originOf is the one place an origin string is built. StartProxy's
// fixed-target check, the allowlist entries and Mobile.Fetch's target check all
// compare strings produced here, so the three cannot drift into two spellings
// of "the same origin" -- the failure mode this project has already been bitten
// by once, where two code paths classified the same address differently.
func originOf(u *url.URL) string {
	if u == nil {
		return ""
	}
	return u.Scheme + "://" + u.Host
}

var (
	securityMu sync.Mutex
	// The zero value is fail-closed: no AllowedTargets means nothing is allowed.
	security = SecurityConfig{}
)

// SetSecurityConfig installs the proxy's security policy. It is process-wide,
// so it is called once before the proxy starts.
//
// Returns "" when the policy was installed, or a message safe to show to the
// user when it was not. The return value is not advisory: a caller that ignores
// it keeps running under the previously installed policy -- or, on a first call
// that fails, under no policy at all, which allows nothing. The Kotlin caller
// already treats a non-empty result as fatal for the start (Tailnet.java,
// startProxy), which is the behaviour this contract is written for.
//
// The candidate is decoded and fully validated before the installed policy is
// touched, and the replacement is one struct assignment under one lock, so a
// rejected policy cannot leave a half-configured process behind.
func SetSecurityConfig(jsonValue string) string {
	cfg, err := decodeSecurityConfig(jsonValue)
	if err != nil {
		return err.Error()
	}
	if err := cfg.normalized(); err != nil {
		return err.Error()
	}
	securityMu.Lock()
	security = cfg
	securityMu.Unlock()
	return ""
}

// currentSecurityConfig returns a copy of the installed policy.
func currentSecurityConfig() SecurityConfig {
	securityMu.Lock()
	defer securityMu.Unlock()
	return security
}

// targetAllowed reports whether origin is on the allowlist. An empty allowlist
// allows nothing (fail closed), and so does an empty origin.
//
// This is the single allowlist predicate. StartProxy calls it with the target it
// is about to dial, and Mobile.Fetch calls it -- directly and again on every
// redirect hop -- with the origin it is about to request. One function, one
// answer, so a host the proxy refuses to forward to is a host Fetch refuses to
// reach.
func targetAllowed(origin string) bool {
	if origin == "" {
		return false
	}
	for _, allowed := range currentSecurityConfig().AllowedTargets {
		if allowed == origin {
			return true
		}
	}
	return false
}
