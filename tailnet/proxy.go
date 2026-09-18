package mobile

// proxy.go: the loopback reverse proxy that lets the app render the target's
// own web UI inside a WebView.
//
// # The problem it solves
//
// The point of this app is to reach one host inside the user's tailnet. Until
// now that meant "test connection" and a JSON health check: the app could prove
// the socket worked, but it could not show the user the thing they actually
// wanted to look at. The reason is not laziness. The socket to the target is
// created inside the tsnet netstack, and a Go `net.Conn` cannot cross the
// gomobile boundary -- so Android's own HTTP stack, and therefore a WebView,
// has no route to the target at all.
//
// A reverse proxy on loopback is the way around that. The proxy listens on
// 127.0.0.1, the WebView talks to it over ordinary HTTP, and the proxy makes
// the outbound connection through this package, where the tailnet dialer
// lives. The WebView does not need Tailscale, does not need a VPN slot, and
// does not need to know that any of this is happening.
//
// # The four things that make it work, and why each is load-bearing
//
//  1. Host and Origin are rewritten to the target's authority. A DSH host
//     behind its auth proxy only mints the session cookie when the request
//     carries the authority it expects; anything else gets a 401. From the
//     WebView's side the origin is `127.0.0.1:<port>`, so both headers have to
//     be restated. This is the single requirement that makes the difference
//     between a login page and a working UI.
//  2. The response stream is never buffered (`FlushInterval: -1`). The DSH UI
//     holds an event stream open; a proxy that buffers turns a live page into
//     one that looks frozen.
//  3. `Content-Encoding` is left exactly as the target sent it. Go's transport
//     would otherwise add its own `Accept-Encoding` and transparently gunzip,
//     which desynchronises the header from the body. In a browser that is a
//     white page.
//  4. The session cookie is handed to the WebView, not kept here. The login
//     handshake is `303 + Set-Cookie` followed by a request that must carry
//     that cookie; a browser does this natively, and its jar is the one place
//     the credential belongs. The proxy drops the cookie's `Domain` attribute
//     on the way past, because a cookie scoped to the target's hostname is
//     rejected outright by a page served from 127.0.0.1.
//
// # What it deliberately does not do
//
// The proxy does not validate the target against the tailnet address policy.
// That policy lives in `TailnetAddressPolicy` on the Kotlin side and is applied
// before a target can ever be stored or dialled, and a second copy here would
// be a second source of truth -- the failure mode this project has already been
// bitten by once, where two code paths classified the same address differently.
// The Go side checks only what it can check honestly: that the target is an
// absolute http(s) URL with a host.
//
// # Security
//
// The listener is bound to 127.0.0.1, never to a routable address, and it
// requires a per-session token. Loopback is not a security boundary on Android:
// any other app on the device can connect to it. Without the token that would
// hand a stranger an authenticated path into the user's server, so the token is
// not optional, and requests that do not present it are refused before any
// upstream connection is made.
//
// The port itself is remembered between runs rather than drawn fresh each time
// (see listenLoopback). This is a cold-start performance fix and it is worth
// being explicit that it does not move the boundary: a local port scanner finds
// an ephemeral port as easily as a remembered one, so nothing that was secret
// becomes less secret. What it does buy is a stable origin, and therefore a
// WebView cache that survives a restart.
//
// The token is carried in the URL the WebView loads and in an HttpOnly cookie
// planted on the first request. It is never logged, never persisted, and never
// leaves the device: it is a capability to reach one loopback port.
//
// One credential does cross the gomobile boundary, and it is worth naming: the
// status document handed to the shell carries the standing session cookie (see
// document). That is not a leak -- the shell is the same process, and it needs
// the value to authenticate its own calls to this listener -- but it does mean
// the document must never be logged, and the redactor knows the cookie's name
// for the day someone does.

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	// proxyTokenParam is the query parameter that carries the capability token
	// on the very first navigation.
	proxyTokenParam = "dshproxy"
	// proxyTokenCookie is where the token is parked afterwards, so that the
	// absolute paths in the page's own requests (`/api/...`, `/plugins/...`)
	// are authorised without every one of them carrying a query string.
	proxyTokenCookie = "dsh_proxy"
	// proxyTokenBytes is the entropy behind the token. 32 bytes is the same
	// size as the session tokens this app already handles, and there is no
	// reason for the weaker of the two to be the one guarding the socket.
	proxyTokenBytes = 32
	// proxyPortFile is the file, inside the node's state directory, that
	// remembers which loopback port this app bound last time.
	proxyPortFile = "proxy.port"
	// proxyPortFloor is the lowest port worth trying to re-bind. Ports under it
	// are privileged or in ranges the platform hands out, and a remembered
	// value must never be able to send the proxy there.
	proxyPortFloor = 1024
)

// forwardedHeaders are the hop-describing headers neither dial path may send
// upstream.
//
// Nothing upstream needs to know about the loopback hop, and a forwarded client
// address is one more thing that could be logged somewhere it does not belong.
// The list is the complete X-Forwarded-* family plus the two odd spellings
// (Forwarded, X-Real-IP) that proxies and frameworks actually read: a header
// that leaks only the port or the original server name is still a header that
// describes this device.
//
// One list, used by both the reverse proxy's Rewrite and Fetch's header filter,
// because the failure mode is a missing entry in one of them -- which is exactly
// what happened: Rewrite stripped all eight while Fetch stripped none.
var forwardedHeaders = []string{
	"X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto",
	"X-Forwarded-Port", "X-Forwarded-Server", "X-Forwarded-Prefix",
	"X-Real-IP", "Forwarded",
}

// isForwardedHeaderName reports whether a caller-supplied header name is one of
// the hop-describing headers, compared the way HTTP compares field names.
func isForwardedHeaderName(name string) bool {
	trimmed := strings.TrimSpace(name)
	for _, header := range forwardedHeaders {
		if strings.EqualFold(trimmed, header) {
			return true
		}
	}
	return false
}

// proxyStateDir is the directory the proxy remembers its port in, set once per
// node start by setProxyStateDir. Guarded by proxyStateMu, which is a leaf lock:
// nothing is acquired while it is held, so it cannot participate in a cycle
// with mu (held across node teardown, which stops the proxy) or proxyMu.
var (
	proxyStateMu  sync.Mutex
	proxyStateDir string
)

// setProxyStateDir records where the proxy may persist its port. Called from
// Start, before anything can start a proxy.
func setProxyStateDir(dir string) {
	proxyStateMu.Lock()
	proxyStateDir = dir
	proxyStateMu.Unlock()
}

// proxyPortPath is where the remembered port lives, or "" when there is nowhere
// to remember it (which simply means every run draws a fresh port).
func proxyPortPath() string {
	proxyStateMu.Lock()
	defer proxyStateMu.Unlock()
	if proxyStateDir == "" {
		return ""
	}
	return filepath.Join(proxyStateDir, proxyPortFile)
}

// proxyRuntime is the live proxy, or nil. Guarded by proxyMu.
type proxyRuntime struct {
	server   *http.Server
	listener net.Listener
	target   *url.URL
	// token is the one-shot bootstrap credential carried in the URL the WebView
	// first loads. It is consumed on first use and never accepted again.
	token string
	// cookie is the standing session credential minted when the token is
	// consumed. It is a fresh random value, distinct from the token, and it is
	// what the page's own absolute-path requests present from then on.
	cookie string
	// url is the exact address the WebView must load, token included.
	url string

	// authMu guards token consumption: the token is one-shot, and concurrent
	// first requests must agree on who got to use it.
	authMu sync.Mutex

	limiter      *rateLimiter
	conns        chan struct{} // semaphore bounding in-flight requests
	cookieMaxAge int
	forwardRefer bool
}

// rateLimiter is a token bucket shared by all requests to one proxy. The
// listener is loopback-only, so every client is the same local host; a single
// bucket is therefore the honest shape of "don't let one app run this hot".
type rateLimiter struct {
	mu     sync.Mutex
	rate   float64 // tokens per second
	burst  float64
	tokens float64
	last   time.Time
}

func newRateLimiter(rate, burst int) *rateLimiter {
	return &rateLimiter{
		rate:   float64(rate),
		burst:  float64(burst),
		tokens: float64(burst),
		last:   time.Now(),
	}
}

func (l *rateLimiter) allow() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := time.Now()
	l.tokens += now.Sub(l.last).Seconds() * l.rate
	if l.tokens > l.burst {
		l.tokens = l.burst
	}
	l.last = now
	if l.tokens < 1 {
		return false
	}
	l.tokens--
	return true
}

var (
	proxyMu sync.Mutex
	proxy   *proxyRuntime
)

// StartProxy begins forwarding loopback HTTP to targetURL and returns a JSON
// document:
//
//	{
//	  "running": true,
//	  "url":     "http://127.0.0.1:41235/?dshproxy=<token>",  // load this
//	  "cookie":  "<session-cookie>",                           // shell's own calls
//	  "target":  "http://100.101.102.103:3080",
//	  "error":   ""
//	}
//
// targetURL must be an absolute http(s) URL whose origin is on the allowlist
// installed by SetSecurityConfig; anything else is refused before a connection
// is made. Calling StartProxy while a proxy is already running returns the
// running one rather than rebinding, so the URL the WebView already holds stays
// valid.
//
// The connection to the target is made through the embedded tailnet node when
// one is up, and through the platform's own network when one is not -- which is
// the correct behaviour for the "system network" connection method, where the
// phone already has a Tailscale tunnel of its own and this app must not compete
// with it.
func StartProxy(targetURL string) string {
	proxyMu.Lock()
	defer proxyMu.Unlock()

	if proxy != nil {
		return mustJSON(proxy.document(""))
	}

	trimmed := strings.TrimSpace(targetURL)
	target, err := url.Parse(trimmed)
	if err != nil {
		return mustJSON(proxyRefusal("target is not a URL: " + redact(err.Error())))
	}
	if target.Host == "" || (target.Scheme != "http" && target.Scheme != "https") {
		return mustJSON(proxyRefusal("target must be an absolute http(s) URL"))
	}

	// Fixed-target rule: the proxy forwards only to origins the caller has
	// allowlisted, never to an arbitrary URL. With no security config installed
	// the allowlist is empty and this refuses everything -- fail closed.
	origin := originOf(target)
	if !targetAllowed(origin) {
		return mustJSON(proxyRefusal("target " + origin + " is not allowed by the security config"))
	}

	token, err := newProxyToken()
	if err != nil {
		return mustJSON(proxyRefusal("cannot generate a session token: " + err.Error()))
	}
	// The session cookie is a second, independent credential: it is what the
	// token is exchanged for, so a leaked token URL cannot be replayed past the
	// first use and a leaked cookie never reveals the token.
	cookie, err := newProxyToken()
	if err != nil {
		return mustJSON(proxyRefusal("cannot generate a session cookie: " + err.Error()))
	}

	cfg := currentSecurityConfig()

	// Loopback only. Binding 0.0.0.0 here would expose the user's server to
	// their Wi-Fi network, which is the opposite of the point. The port is
	// remembered between runs; see listenLoopback for why that matters.
	listener, err := listenLoopback(cfg.RandomPort)
	if err != nil {
		return mustJSON(proxyRefusal("cannot listen on loopback: " + err.Error()))
	}
	rememberProxyPort(listener.Addr())

	runtime := &proxyRuntime{
		listener:     listener,
		target:       target,
		token:        token,
		cookie:       cookie,
		limiter:      newRateLimiter(cfg.RateLimitPerSecond, cfg.RateBurst),
		conns:        make(chan struct{}, cfg.MaxConnections),
		cookieMaxAge: cfg.CookieMaxAgeSeconds,
		forwardRefer: cfg.ForwardReferer,
	}
	runtime.url = fmt.Sprintf("http://%s/?%s=%s", listener.Addr().String(), proxyTokenParam, token)

	server := &http.Server{
		Handler: runtime.handler(),
		// A browser starts with a request it can send immediately; anything that
		// opens a connection and then dribbles headers is not a WebView.
		ReadHeaderTimeout: 15 * time.Second,
		// No WriteTimeout, deliberately, and this is not an oversight: the DSH UI
		// holds an event stream open indefinitely, and a write deadline would cut
		// it off mid-session on a timer. ReadHeaderTimeout bounds the part that
		// needs bounding.
	}
	runtime.server = server
	proxy = runtime

	go func() {
		// Serve returns ErrServerClosed on a normal shutdown; there is nothing
		// useful to do with any other error either, and the UI learns about a
		// dead port from the failed request rather than from here.
		_ = server.Serve(listener)
	}()

	return mustJSON(runtime.document(""))
}

// StopProxy tears the proxy down and returns the terminal status document.
// Safe to call when nothing is running.
func StopProxy() string {
	stopProxy()
	return mustJSON(proxyRefusal(""))
}

// ProxyStatus reports whether the proxy is up and, if it is, the URL the
// WebView should be pointed at.
func ProxyStatus() string {
	proxyMu.Lock()
	defer proxyMu.Unlock()
	if proxy == nil {
		return mustJSON(proxyRefusal(""))
	}
	return mustJSON(proxy.document(""))
}

// stopProxy is the unlocked body of StopProxy, shared with the node teardown.
func stopProxy() {
	proxyMu.Lock()
	runtime := proxy
	proxy = nil
	proxyMu.Unlock()

	if runtime == nil {
		return
	}
	// Give in-flight responses a moment to finish, then stop waiting: an open
	// event stream has no natural end, and Shutdown alone would block until its
	// context expired on every single teardown.
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = runtime.server.Shutdown(ctx)
	_ = runtime.server.Close()
}

// ---------------------------------------------------------------------------
// Request path
// ---------------------------------------------------------------------------

func (p *proxyRuntime) handler() http.Handler {
	reverse := p.reverseProxy()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Local DoS guard, before any work: a misbehaving app on this device
		// must not be able to keep the listener (and with it the node's dial
		// path) busy for everyone else.
		//
		// `Retry-After: 1` is not decoration. A single page load fans out into
		// dozens of requests, so a 429 or 503 here arrives in the middle of a
		// burst; without a hint the browser backs off on its own schedule, which
		// is how a momentarily busy proxy turns into a page that spins. One
		// second is chosen because the limiter refills continuously and the
		// saturation it reports is transient by construction.
		if !p.limiter.allow() {
			w.Header().Set("Retry-After", "1")
			http.Error(w, "tailnet-byok: too many requests", http.StatusTooManyRequests)
			return
		}
		// The in-flight cap is a fixed number of slots, and a slot is held for
		// the whole life of the response -- including an event stream, which by
		// design never ends (there is no WriteTimeout, see StartProxy). So a
		// handful of open SSE connections permanently consume that many slots,
		// and the pressure that produces a 503 is normally "the UI is holding
		// streams open", not "someone is flooding". That is a real trade-off and
		// not a bug to be tuned away here: raising the cap raises the number of
		// goroutines and netstack connections the phone carries, lowering it
		// makes a normal load more likely to be refused. The cap is
		// configurable (SecurityConfig.MaxConnections, bounds checked) so a
		// deployment can move it without recompiling, and the SSE count itself
		// is not separately capped -- the UI opens one or two, and a per-stream
		// limit on top of the total would only add a second way to 503.
		select {
		case p.conns <- struct{}{}:
			defer func() { <-p.conns }()
		default:
			w.Header().Set("Retry-After", "1")
			http.Error(w, "tailnet-byok: too many connections", http.StatusServiceUnavailable)
			return
		}

		authed, fromQuery := p.authorize(w, r)
		if !authed {
			// Deliberately does not say what was expected. The only caller that
			// sees this is something that was not given the credential.
			http.Error(w, "tailnet-byok: this local port needs its session token", http.StatusForbidden)
			return
		}
		if fromQuery {
			// Keep the token out of the target's access log, and out of the
			// page's own view of its URL. The cookie is already doing the work
			// from here on.
			query := r.URL.Query()
			query.Del(proxyTokenParam)
			r.URL.RawQuery = query.Encode()
		}
		reverse.ServeHTTP(w, r)
	})
}

// authorize checks the request against the proxy's two-stage credentials: the
// one-shot bootstrap token, then the session cookie it mints. It reports
// whether the request is authorised and whether the credential came from the
// query string (the signal the handler uses to strip it before forwarding).
func (p *proxyRuntime) authorize(w http.ResponseWriter, r *http.Request) (bool, bool) {
	queryToken := r.URL.Query().Get(proxyTokenParam)
	if queryToken != "" {
		p.authMu.Lock()
		valid := p.token != "" && subtle.ConstantTimeCompare([]byte(queryToken), []byte(p.token)) == 1
		if valid {
			// One-shot: the token is consumed on first use, so replaying the
			// same URL fails from here on. The cookie is the standing credential.
			p.token = ""
		}
		cookieVal := p.cookie
		p.authMu.Unlock()

		// A token that does not match -- including one already consumed -- must
		// not fall through to the cookie path: "same token, second visit" is the
		// exact case that has to fail.
		if !valid {
			return false, true
		}
		http.SetCookie(w, &http.Cookie{
			Name:     proxyTokenCookie,
			Value:    cookieVal,
			Path:     "/",
			HttpOnly: true,
			SameSite: http.SameSiteStrictMode,
			MaxAge:   p.cookieMaxAge,
		})
		return true, true
	}

	if cookie, err := r.Cookie(proxyTokenCookie); err == nil && cookie.Value != "" {
		p.authMu.Lock()
		ok := subtle.ConstantTimeCompare([]byte(cookie.Value), []byte(p.cookie)) == 1
		p.authMu.Unlock()
		if ok {
			return true, false
		}
	}
	return false, false
}

func (p *proxyRuntime) reverseProxy() *httputil.ReverseProxy {
	transport := &http.Transport{
		DialContext: proxyDialContext,
		// The WebView sends its own Accept-Encoding and will decode what it
		// gets. Leaving Go's transparent compression on would mean Go asked for
		// gzip on a request the browser did not, then stripped Content-Encoding
		// while handing back the still-compressed body.
		DisableCompression: true,
		ForceAttemptHTTP2:  false,
		MaxIdleConns:       16,
		// Perf, not security: Go's default of 2 idle connections per host means
		// a page that loads its assets in parallel keeps re-dialling through the
		// userspace netstack -- a fresh handshake for every third request, on a
		// link whose whole cost is latency. Kept equal to MaxIdleConns so the
		// total budget is the only number in play. This does not widen anything:
		// every one of these connections goes to p.target and nowhere else.
		MaxIdleConnsPerHost: 16,
		IdleConnTimeout:     90 * time.Second,
	}

	return &httputil.ReverseProxy{
		Transport: transport,
		// Flush after every write. Not a tuning knob: see the package comment.
		FlushInterval: -1,
		Rewrite: func(pr *httputil.ProxyRequest) {
			// SetURL also clears Out.Host, which is what makes the outbound
			// request carry the target's authority in its Host header rather
			// than the loopback one the WebView actually connected to.
			pr.SetURL(p.target)

			authority := p.target.Scheme + "://" + p.target.Host
			pr.Out.Header.Set("Origin", authority)
			// Referer is the one of the three that the target does not need.
			// Host and Origin are restated because the target's auth and its
			// /api browser-trust fence depend on them; Referer only tells the
			// target where the user came from, and the loopback hop is nobody's
			// business. The client may opt back in via the security config;
			// absent that it is dropped.
			if p.forwardRefer {
				if pr.Out.Header.Get("Referer") != "" {
					pr.Out.Header.Set("Referer", authority+"/")
				}
			} else {
				pr.Out.Header.Del("Referer")
			}

			// See forwardedHeaders: this is the reverse-proxy half of the rule,
			// and Fetch's header filter is the other half.
			for _, header := range forwardedHeaders {
				pr.Out.Header.Del(header)
			}
		},
		ModifyResponse: func(response *http.Response) error {
			// A cheap mitigation for the one navigation path the shell's own
			// Java-side filter cannot see. WebViewClient.shouldOverrideUrlLoading
			// is not consulted for a subframe load, so an
			// `<iframe src="http://elsewhere/">` -- in the target's own HTML, or
			// injected by a plugin -- can pull an off-origin document into the
			// page even though top-level navigation is gated. `frame-src 'self'`
			// confines frames to the loopback origin and `object-src 'none'`
			// removes the plugin path entirely; nothing else is restricted, so
			// scripts, styles, images, fonts and the event stream are untouched.
			//
			// Added, never Set: a second Content-Security-Policy header narrows
			// the first (browsers intersect them), while replacing the target's
			// own policy could only ever widen it.
			response.Header.Add("Content-Security-Policy", "frame-src 'self'; object-src 'none'")

			// The session cookie is the WebView's credential; a cookie scoped
			// to the target's hostname would be rejected outright by a page
			// served from 127.0.0.1, so its Domain attribute is dropped on the
			// way past. Everything else the target sends is left alone except
			// Location, which is the one response header that can move the
			// browser off loopback entirely -- see rewriteLocation.
			if cookies := response.Header.Values("Set-Cookie"); len(cookies) > 0 {
				response.Header.Del("Set-Cookie")
				for _, cookie := range cookies {
					response.Header.Add("Set-Cookie", stripCookieDomain(cookie))
				}
			}
			return p.rewriteLocation(response)
		},
		ErrorHandler: func(w http.ResponseWriter, r *http.Request, err error) {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.WriteHeader(http.StatusBadGateway)
			fmt.Fprintf(w, "tailnet-byok: could not reach %s\n\n%s\n",
				p.target.Host, redact(err.Error(), currentKey()))
		},
	}
}

// ---------------------------------------------------------------------------
// Response headers that can move the browser
// ---------------------------------------------------------------------------

// offTargetRedirectBody is the entire body of the 502 this proxy substitutes for
// a redirect that points somewhere other than the target.
//
// It deliberately does not name the host it refused. This text is rendered in a
// page whose origin the user has every reason to trust, and the host in an
// upstream `Location` is chosen by whoever controls that upstream -- so echoing
// it would put an attacker-chosen string, as a visible URL, in front of the user
// inside the app's own chrome.
const offTargetRedirectBody = "tailnet-byok: upstream tried to redirect off-target (host redacted); refused\n"

// rewriteLocation keeps every navigation the WebView can make on the loopback
// origin, or refuses the response that tried to send it elsewhere.
//
// # Why this is a security control and not a convenience
//
// The proxy exists so the WebView never needs a route to the target: the browser
// talks to 127.0.0.1 and this process -- the only thing holding the tailnet
// dialer -- talks to the target. An upstream `Location` is the one place the
// target can hand the browser a destination directly, and the WebView client has
// no navigation filter of its own (no `shouldOverrideUrlLoading`), so a
// `Location: http://elsewhere/` moves the page off loopback: every rewrite in
// `Rewrite` stops applying, the session credential stays behind, and the user is
// looking at someone else's page in a window whose chrome still says this app.
//
// The rules, in order:
//
//   - A relative reference (no scheme, no authority) is forwarded byte for byte.
//     The browser resolves it against the URL it loaded, which is this proxy's,
//     so it cannot leave the origin and re-encoding it could only break the
//     target's own routing.
//   - An absolute reference that resolves to the target's own origin is reduced
//     to path-and-query. Same destination, but stated in a form the browser can
//     only resolve back into the loopback origin: the WebView never sees the
//     target's authority at all.
//   - Anything else -- another host, another scheme, an opaque scheme like
//     `javascript:` or `data:`, or a value that will not parse -- is refused
//     outright. The response becomes a 502 with a fixed plain-text body.
//
// # What decides this is the browser's parser, not Go's
//
// Go's `url.Parse` and a WHATWG URL parser do not agree about what a relative
// reference is, and the disagreement is exactly one authority wide. Go treats a
// backslash as an ordinary path character and `///` as an empty authority
// followed by a path; a browser resolving against a special (http/https) base
// normalises `\` to `/` and skips *any number* of leading `/` and `\` before
// reading the authority. So `////evil.com/x`, `///evil.com/x`, `\\evil.com/x`,
// `\/\/evil.com/x` and `/\evil.com/x` all leave the origin even though
// `Scheme == "" && Host == ""` is true for every one of them.
//
// The rule that closes it: whatever string is about to be handed over is
// normalised the way a browser normalises it, and refused if that leaves an
// authority at the front. That check runs on the final origin-form value, not on
// the input, because the rewrite itself can produce the same shape -- a
// same-origin `Location: http://host//evil.com/` reduces to `//evil.com/`.
func (p *proxyRuntime) rewriteLocation(response *http.Response) error {
	location := response.Header.Get("Location")
	if location == "" {
		return nil
	}

	// Resolve against the URL that was actually requested upstream, so a
	// relative reference is judged by where the target believes it is.
	base := p.target
	if response.Request != nil && response.Request.URL != nil {
		base = response.Request.URL
	}

	reference, err := url.Parse(location)
	if err != nil {
		// A value no browser would accept as a URL is still not something to
		// pass through unexamined: refuse it rather than guess.
		return refuseOffTargetRedirect(response)
	}

	originForm := location
	if !(reference.Scheme == "" && reference.Host == "") {
		// An absolute reference (or a protocol-relative one, which has a host
		// and no scheme) is only usable if it names the target's own origin.
		resolved := base.ResolveReference(reference)
		if !sameOrigin(resolved, p.target) {
			return refuseOffTargetRedirect(response)
		}
		// RequestURI renders an empty path as "/", so this is always a valid
		// origin-form request target.
		originForm = resolved.RequestURI()
	}

	if !staysOnOrigin(originForm) {
		return refuseOffTargetRedirect(response)
	}
	// A relative reference is written back exactly as the target wrote it:
	// re-encoding could only disturb the target's own routing, and the check
	// above has already established that the browser cannot read an authority
	// out of it.
	response.Header.Set("Location", originForm)
	return nil
}

// staysOnOrigin reports whether an origin-form Location value can be resolved by
// a WHATWG URL parser without leaving the origin the page was loaded from.
//
// It is the normalisation that does the work. WHATWG removes ASCII tab and
// newline from the input wherever they appear, and for a special scheme
// (http, https) treats a backslash as a path separator; after that, a value
// beginning with `//` is read as a network-path reference and the next component
// is an authority. Go's own parser sees none of that, which is why the check
// cannot be expressed as a property of the parsed URL.
//
// A value that does not begin with `//` after normalisation has no way to reach
// an authority: it is either an absolute path (resolved against the loopback
// origin), or a relative path, query or fragment (resolved against the current
// loopback path). Both stay where they are.
func staysOnOrigin(originForm string) bool {
	normalized := strings.NewReplacer(
		"\t", "", "\n", "", "\r", "",
		"\\", "/",
	).Replace(originForm)
	return !strings.HasPrefix(normalized, "//")
}

// refuseOffTargetRedirect replaces a response that tried to send the WebView
// somewhere else with a self-contained 502.
//
// Every header that described the discarded body has to go with it. Leaving
// `Content-Encoding` behind is the sharp one: it tells the browser to gunzip a
// plain-text body, and the result is a blank page with no error the user can act
// on. `Content-Length` is restated for the body actually being sent, because
// this proxy flushes on every write (FlushInterval: -1) and so never gets a
// second chance to let the server compute it.
func refuseOffTargetRedirect(response *http.Response) error {
	// The upstream body is going to be discarded, so its connection is released
	// here rather than left for a reader that will never exist.
	if response.Body != nil {
		_ = response.Body.Close()
	}
	response.Header.Del("Location")
	response.Header.Del("Content-Encoding")
	response.Header.Set("Content-Type", "text/plain; charset=utf-8")
	response.Header.Set("Content-Length", strconv.Itoa(len(offTargetRedirectBody)))
	response.Body = io.NopCloser(strings.NewReader(offTargetRedirectBody))
	response.ContentLength = int64(len(offTargetRedirectBody))
	response.StatusCode = http.StatusBadGateway
	response.Status = "502 Bad Gateway"
	// A nil error: the refusal is a complete response, not a transport failure,
	// so it must not be routed through the proxy's ErrorHandler.
	return nil
}

// sameOrigin reports whether two URLs share a scheme, host and port, compared
// the way a browser compares them: scheme and host case-insensitively, with the
// scheme's default port made explicit so that `http://host` and `http://host:80`
// are recognised as the same origin rather than as two.
func sameOrigin(a, b *url.URL) bool {
	if a == nil || b == nil {
		return false
	}
	if !strings.EqualFold(a.Scheme, b.Scheme) {
		return false
	}
	return strings.EqualFold(hostWithEffectivePort(a), hostWithEffectivePort(b))
}

// hostWithEffectivePort is a URL's authority with its default port filled in.
func hostWithEffectivePort(u *url.URL) string {
	host := u.Hostname()
	port := u.Port()
	if port == "" {
		switch strings.ToLower(u.Scheme) {
		case "http":
			port = "80"
		case "https":
			port = "443"
		}
	}
	if port == "" {
		return host
	}
	return net.JoinHostPort(host, port)
}

// proxyDialContext opens the socket the proxy forwards over.
//
// With an embedded node up, the connection is made inside its netstack: that is
// the whole reason this proxy exists. With no node, it falls back to the
// platform's own network, which is right for the case where the phone already
// has a tunnel.
func proxyDialContext(ctx context.Context, network, address string) (net.Conn, error) {
	mu.Lock()
	server := node
	mu.Unlock()

	if server != nil {
		return server.Dial(ctx, network, address)
	}
	return (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, address)
}

// stripCookieDomain removes the `Domain` attribute from one Set-Cookie header,
// leaving every other attribute byte-for-byte as the server wrote it.
//
// The cookie that matters here is issued by the target and consumed by a page
// served from 127.0.0.1. A server that scopes its cookie with an explicit
// `Domain=<its own hostname>` would have that cookie rejected outright by the
// browser as belonging to a different origin, and the login handshake would
// never complete -- so if the attribute is there, it has to go. Without it the
// cookie is host-only, which for a loopback origin means exactly this proxy and
// nothing else.
//
// The DSH auth proxy this was written against does not set `Domain` at all, so
// on that target this is a no-op. It is kept as insurance: the failure it
// prevents is a blank login page with no error anywhere, which is an expensive
// bug to rediscover from a bug report.
func stripCookieDomain(setCookie string) string {
	parts := strings.Split(setCookie, ";")
	kept := make([]string, 0, len(parts))
	for index, part := range parts {
		// Only attributes are dropped, and the first part is never an
		// attribute: it is the name=value pair itself.
		if index > 0 && strings.HasPrefix(strings.ToLower(strings.TrimSpace(part)), "domain=") {
			continue
		}
		kept = append(kept, part)
	}
	return strings.Join(kept, ";")
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

// document is the status document every proxy entry point returns as JSON. Its
// key names are a contract with the shell (Tailnet.java reads `running`, `url`,
// `cookie` and `error`) and must not be renamed or dropped.
//
// On the `cookie` field, which is the only secret in here:
//
//   - It is the same-process shell's credential, not an externally visible
//     surface. The shell authenticates its own HTTP calls to this listener with
//     `Cookie: dsh_proxy=<value>` -- the listener is loopback-only and requires
//     that cookie on every request the one-shot token did not authorise -- so
//     removing the field breaks the embedded mode rather than hardening it.
//   - The value is the *standing* credential minted when the token was
//     consumed, which is what the WebView receives as an HttpOnly cookie. The
//     one-shot token itself also appears here, inside `url`, because the shell
//     has to load that URL to bootstrap the session.
//   - Therefore this function's return value must never be logged, put on the
//     diagnostics page, or attached to a bug report. Nothing in this package
//     does any of those, and `redact` masks both the `dshproxy=` query value and
//     the `dsh_proxy=` cookie value as a second line of defence for the day
//     something does.
func (p *proxyRuntime) document(errMessage string) map[string]any {
	return map[string]any{
		"running": true,
		"url":     p.url,
		// The session cookie value, so the shell's own HTTP calls (upload,
		// self-update, self-test) can present the same standing credential the
		// WebView will receive after it consumes the one-shot token.
		"cookie": p.cookie,
		"target": originOf(p.target),
		"error":  errMessage,
	}
}

func proxyRefusal(errMessage string) map[string]any {
	return map[string]any{
		"running": false,
		"url":     "",
		"target":  "",
		"error":   errMessage,
	}
}

// ---------------------------------------------------------------------------
// Remembered port
// ---------------------------------------------------------------------------

// listenLoopback binds the loopback listener the WebView talks to, preferring
// the port this app bound last time.
//
// The preference is a performance fix, not a cosmetic one. A WebView's HTTP
// cache is keyed by the full URL, port included, so a freshly drawn ephemeral
// port on every launch means every cold start is a cold cache: the app
// re-downloads its whole UI over a tailnet link, and the `immutable` cache
// headers the host sends can only be honoured inside one session. Reusing the
// port turns every launch after the first into a handful of kilobytes.
//
// Falling back to an ephemeral port is what keeps this safe: the remembered
// port may be held by something else, and that must never stop the proxy from
// coming up. It also closes the one way a remembered port could have been
// worse than a random one -- an app that squats the port gets nothing, because
// the bind fails, we fall back, and the WebView is handed the URL of the port
// this app actually owns. The token is therefore never delivered to a port we
// do not control.
func listenLoopback(randomPort bool) (net.Listener, error) {
	if !randomPort {
		if preferred := rememberedProxyPort(); preferred > 0 {
			if listener, err := net.Listen("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(preferred))); err == nil {
				return listener, nil
			}
		}
	}
	return net.Listen("tcp", "127.0.0.1:0")
}

// rememberedProxyPort is the port the last successful bind recorded, or 0 when
// there is no usable record. Every failure here means "no preference" and never
// an error: a missing, unreadable or nonsense file must not keep the proxy down.
func rememberedProxyPort() int {
	path := proxyPortPath()
	if path == "" {
		return 0
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(raw)))
	if err != nil || port < proxyPortFloor || port > 65535 {
		return 0
	}
	return port
}

// rememberProxyPort records the port that was actually bound -- including the
// ephemeral one on a first run or after a collision -- so the next run reuses
// it.
//
// Best effort by design: a state directory that cannot be written costs the
// next launch its warm cache, and that is the only thing it costs, so there is
// nothing here worth failing a start over.
func rememberProxyPort(addr net.Addr) {
	path := proxyPortPath()
	if path == "" || addr == nil {
		return
	}
	tcpAddr, ok := addr.(*net.TCPAddr)
	if !ok {
		return
	}
	_ = os.WriteFile(path, []byte(strconv.Itoa(tcpAddr.Port)), 0o600)
}

func newProxyToken() (string, error) {
	buf := make([]byte, proxyTokenBytes)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	// URL-safe and unpadded, so the token survives a round trip through a query
	// string and a cookie value without escaping.
	return base64.RawURLEncoding.EncodeToString(buf), nil
}
