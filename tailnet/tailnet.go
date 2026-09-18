// Package mobile is the gomobile-bound bridge between the Android app and an
// embedded Tailscale node.
//
// # Why this package exists
//
// The app's whole reason to exist is to accept a Tailscale auth key from the
// user and then reach one host inside that user's own tailnet. The obvious
// shortcut -- depend on the Tailscale Android app -- cannot do that: there is no
// API, intent or content provider through which one app can hand an auth key to
// `com.tailscale.ipn` (see docs/TSNET.md for the evidence). So the app embeds
// its own node.
//
// The second shortcut -- reuse the `.aar` that tailscale-android builds -- is also
// wrong here. That binding exposes no `Dial` and no `GetIP` to Java; all control
// flows through the local API, and using it commits you to implementing
// `IPNService` + `AppContext` in Kotlin, i.e. to owning the system VPN slot for
// the whole device. This app needs one socket to one destination, not a tunnel
// for every app on the phone.
//
// So this package wraps `tailscale.com/tsnet` directly and exposes the smallest
// possible surface to Java.
//
// # Binding rules this file follows on purpose
//
// gomobile cannot express a Go `net.Conn` in Java, and multi-value returns bind
// differently across gomobile revisions. Both traps are avoided by a rule:
//
//	every exported function takes and returns only String, Int, Bool or error.
//
// Anything structured crosses the boundary as a JSON document, and any byte
// payload is base64 inside that document. That keeps the generated Java surface
// stable, reviewable, and independent of the binding generator's mood.
//
// # Security rules
//
//   - The auth key is a parameter. It is never written to a log, never returned
//     in a status document, and never placed in the node's state directory in
//     cleartext by this package (tsnet stores the *resulting* node key, which is
//     a different and revocable credential).
//   - Log output is captured into a bounded in-memory ring and redacted before
//     it leaves this package. Nothing is written to disk.
package mobile

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/netip"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/tsnet"
	tsversion "tailscale.com/version"
)

// maxLogLines bounds the in-memory log ring. The Android side polls this for
// diagnostics; an unbounded buffer in a long-lived process is a leak.
const maxLogLines = 400

// maxRedirects bounds a single Fetch call's redirect chain. It matches Go's own
// default (10) rather than the tighter 3 this package used before: a hosted DSH
// host answers the first request with a 303 to "/" and expects the follow-up to
// carry the session cookie it just set, so a small cap turns a normal login
// handshake into a hard failure.
const maxRedirects = 10

// Node is the process-wide embedded tailnet node.
//
// A single global is deliberate: a phone has one tailnet identity in this app,
// and making the lifetime explicit removes a class of "two nodes, two state
// directories, two sets of keys" bugs. Start and Stop are idempotent.
var (
	mu      sync.Mutex
	node    *tsnet.Server
	nodeCfg config
	logRing []string
	lastURL string
	started bool
	lastErr string

	// upInFlight counts the startup goroutines Start leaves behind when tsnet's Up
	// outlives the deadline. Stop waits on it before closing the server, because
	// closing one that is still starting panics inside tailscale.
	upInFlight sync.WaitGroup

	// upCtxCancel cancels the startup context of a node that is still coming up.
	// Stop calls it first: a node that is waiting for an interactive login has a
	// goroutine parked in Up, and that goroutine must be released before the server
	// is closed underneath it.
	upCtxCancel context.CancelFunc

	// httpJar holds cookies for requests issued through this node. tsnet's
	// HTTPClient() returns a client with no jar at all, so a Set-Cookie followed
	// by a redirect is a dead end: see Fetch. The jar is created by Start and
	// dropped by Stop, so a session cookie obtained from one host can never
	// outlive the node session that earned it. It is memory only, by design --
	// that cookie is a credential.
	httpJar *cookiejar.Jar
)

type config struct {
	StateDir   string
	AuthKey    string
	ControlURL string
	Hostname   string
	Ephemeral  bool
	ForceLogin bool
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

// Start brings the embedded node up and blocks until it is Running with an IP,
// or until timeoutMs elapses.
//
// Parameters mirror what the user fills into the app:
//
//	stateDir    writable directory for node state (the app's noBackupFilesDir,
//	            so it is never swept into a cloud backup)
//	authKey     the user's pre-auth key; empty is allowed and means interactive
//	            login, in which case Status().loginURL carries the URL to open
//	controlURL  "" for Tailscale's SaaS control plane, or the base URL of a
//	            self-hosted headscale
//	hostname    the name this node takes in the tailnet
//	ephemeral   true to have the node deregister itself when it disconnects;
//	            also the correct choice on Android < 12 where persistent key
//	            custody cannot be guaranteed (see docs/TSNET.md)
//	forceLogin  re-authenticate even when usable state already exists on disk
//	timeoutMs   how long to wait for Running; <=0 selects 30000
//
// On failure the error message is safe to show to the user: it is redacted at
// the source, in this package, before it crosses the boundary.
func Start(
	stateDir string,
	authKey string,
	controlURL string,
	hostname string,
	ephemeral bool,
	forceLogin bool,
	timeoutMs int,
) error {
	mu.Lock()
	defer mu.Unlock()

	if started && node != nil {
		return nil
	}

	if strings.TrimSpace(stateDir) == "" {
		return fmt.Errorf("stateDir must not be empty")
	}
	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return fmt.Errorf("cannot create state directory: %w", err)
	}

	// Point Go's scratch locations at a directory this app owns, before the node
	// starts. Go's defaults are a desktop's: os.TempDir() is /tmp and
	// UserCacheDir() is ~/.cache. An Android app has neither -- there is no /tmp,
	// and HOME is "/" or unset -- and Tailscale's own code asks for both.
	//
	// logpolicy's LogsDir is the sharp edge, and it is in this start path
	// (tsnet's startLogger): it checks $TS_LOGS_DIR, then Windows and linux state
	// directories, then the system state path, then the cache dir, then the
	// working directory, and finally calls os.MkdirTemp("") -- and on failure
	// PANICS with "no safe place found to store log state". That panic happens
	// inside a JNI call, so it takes the whole app process down rather than
	// failing a request. Answering all three questions up front removes the class
	// of problem, and it also lets logpolicy persist its log ID, which it cannot
	// do when the only answer it can find is a directory it cannot write to.
	prepareScratchDirs(stateDir)

	// Tell the loopback proxy where it may remember the port it binds, so that
	// the WebView keeps the same origin -- and therefore its HTTP cache --
	// across restarts. Must happen before any proxy can be started.
	setProxyStateDir(stateDir)

	if timeoutMs <= 0 {
		timeoutMs = 30_000
	}

	// tsnet ignores the supplied auth key when usable state already exists,
	// unless this variable is set. Without it, "the user pasted a new key and
	// nothing changed" is the single most likely support ticket, so the switch
	// is wired to an explicit parameter rather than left implicit.
	if forceLogin {
		_ = os.Setenv("TSNET_FORCE_LOGIN", "1")
	} else {
		_ = os.Unsetenv("TSNET_FORCE_LOGIN")
	}

	logRing = logRing[:0]
	lastURL = ""
	lastErr = ""

	srv := &tsnet.Server{
		Dir:        filepath.Join(stateDir, "node"),
		Hostname:   hostname,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Ephemeral:  ephemeral,
		Logf:       captureLog,
		UserLogf:   captureUserLog,
	}

	node = srv
	nodeCfg = config{
		StateDir:   stateDir,
		AuthKey:    authKey,
		ControlURL: controlURL,
		Hostname:   hostname,
		Ephemeral:  ephemeral,
		ForceLogin: forceLogin,
	}
	// Fresh jar per node session. cookiejar.New only fails on invalid options,
	// and nil options are always valid, so the error is not actionable here.
	httpJar, _ = cookiejar.New(nil)

	// The context handed to Up has NO deadline of its own, and this function's
	// deadline is enforced by the select below instead.
	//
	// That distinction is what makes a browser login possible at all. An interactive
	// login takes as long as the user takes, and a context that expires would make
	// Up give up on the very login the app is about to offer -- the deadline would
	// cancel the work it was only supposed to stop waiting for. The caller still
	// gets its answer on time; the node keeps working in the background, and
	// Status() is how the app finds out that it came up.
	upCtx, upCancel := context.WithCancel(context.Background())
	upCtxCancel = upCancel // guarded by mu, which this function holds

	// Up runs on its own goroutine, and this function returns when the deadline
	// passes rather than trusting Up to honour anything.
	//
	// Measured 2026-09-15: with a control server that cannot be reached, tsnet's Up
	// did NOT come back when the context expired -- it sat there, and the test that
	// called it blocked an entire build lane until the process was killed. On a
	// phone the same call parks the app's bootstrap thread forever: no error
	// message, no fallback to the system path, just a blank screen. That is the
	// worst possible shape of failure, so the deadline is enforced here.
	upDone := make(chan error, 1)
	upInFlight.Add(1)
	go func() {
		defer upInFlight.Done()
		// A startup that outlives this call must not be able to take the process
		// with it.
		//
		// Measured 2026-09-15: after a timeout return, the shell (or a test) calls
		// Stop, which closes the server and its event bus, and the abandoned Up
		// then panics inside tailscale -- set.Set.Add on a nil map, reached through
		// magicsock.NewConn <- NewUserspaceEngine <- tsnet.start <- Up. A panic in
		// this goroutine is not recoverable by any caller, so it is caught here.
		defer func() {
			if r := recover(); r != nil {
				upDone <- fmt.Errorf("%s", redact(fmt.Sprintf("node startup failed: %v", r)))
			}
		}()
		_, upErr := srv.Up(upCtx)
		if upErr == nil {
			// Record the success even when this call already gave up waiting: that
			// is exactly the interactive-login case, and Status() is the channel the
			// app polls to notice.
			mu.Lock()
			if node == srv {
				started = true
				lastErr = ""
			}
			mu.Unlock()
		}
		upDone <- upErr
	}()

	timer := time.NewTimer(time.Duration(timeoutMs) * time.Millisecond)
	defer timer.Stop()
	select {
	case upErr := <-upDone:
		if upErr != nil {
			lastErr = redact(upErr.Error(), authKey)
			return fmt.Errorf("%s", lastErr)
		}
	case <-timer.C:
		// Stop waiting, leave the node running: the caller is told, not blocked, and
		// a login the user is about to complete still has somewhere to land.
		lastErr = fmt.Sprintf("still starting after %dms; the node is still trying", timeoutMs)
		return fmt.Errorf("%s", lastErr)
	}

	started = true
	return nil
}

// prepareScratchDirs points Go's scratch locations inside stateDir.
//
// Why this is not optional on Android: Go's defaults are a desktop's.
// os.TempDir() is /tmp and os.UserCacheDir() is ~/.cache, and an Android app has
// neither -- there is no /tmp, and HOME is "/" or unset. Tailscale's own code
// asks for both, and one of those places is in this start path:
// net/logpolicy.LogsDir, reached from tsnet's startLogger, walks
// $TS_LOGS_DIR, the OS state directories, the system state path, the cache dir
// and the working directory, and then calls os.MkdirTemp(""). When that last
// attempt fails it PANICS with "no safe place found to store log state" -- from
// inside a JNI call, which takes the whole app process down instead of failing a
// request. Answering first removes that class of problem.
//
// Setting $TS_LOGS_DIR alone already short-circuits that walk (it is checked
// first), and it has a second, quieter benefit: logpolicy can now persist its log
// ID, which it cannot do when the only directory it can name is one it cannot
// write to.
//
// Best effort by design. A failure here is not worth refusing to start a node
// over; it only means the old defaults apply.
func prepareScratchDirs(stateDir string) {
	scratch := filepath.Join(stateDir, "scratch")
	if err := os.MkdirAll(scratch, 0o700); err != nil {
		return
	}
	if tmp := filepath.Join(scratch, "tmp"); os.MkdirAll(tmp, 0o700) == nil {
		_ = os.Setenv("TMPDIR", tmp)
	}
	if cache := filepath.Join(scratch, "cache"); os.MkdirAll(cache, 0o700) == nil {
		_ = os.Setenv("XDG_CACHE_HOME", cache)
	}
	if logs := filepath.Join(scratch, "logs"); os.MkdirAll(logs, 0o700) == nil {
		_ = os.Setenv("TS_LOGS_DIR", logs)
	}
}

// Stop tears the node down. Safe to call when nothing is running.
//
// It also stops the loopback proxy (see proxy.go): that proxy is an
// authenticated path to the user's server, and leaving it listening after the
// credential behind it has been released would be a hole rather than a
// convenience.
func Stop() (err error) {
	mu.Lock()
	server := node
	node = nil
	started = false
	// Clear the key from our own copy of the config before releasing the node.
	nodeCfg.AuthKey = ""
	// Taken together so the cancel below cannot race a new Start installing one.
	cancelUp := upCtxCancel
	upCtxCancel = nil
	// The jar holds the session cookie handed out by whatever host we talked to;
	// it is a credential tied to this node session, so it goes with the node.
	httpJar = nil
	// Released before any teardown: a graceful HTTP shutdown waits for in-flight
	// handlers, and those handlers take this mutex to dial.
	mu.Unlock()

	// Release a startup that is still parked in Up -- an interactive login waiting
	// for a browser, or a control server that is not answering -- before closing
	// anything it is still using. (Also why this is read under mu above.)
	if cancelUp != nil {
		cancelUp()
	}

	// Tearing down a node that never came up must not be able to take the process
	// with it: the shell calls this on every destroy and before every rebuild,
	// whether or not the previous start succeeded.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("%s", redact(fmt.Sprintf("stop failed unexpectedly: %v", r)))
		}
	}()

	stopProxy()

	if server == nil {
		return nil
	}

	// Let a startup that is still in flight finish before pulling the server out
	// from under it.
	//
	// Start returns on its deadline even when tsnet's Up has not, leaving an Up
	// running in the background. Closing the server while it is still reaching
	// into the event bus panics inside tailscale (see the recover in Start), and
	// the shell's own sequence -- stop, then start again -- hits exactly that
	// window. Bounded, because "still stuck" is the very reason the deadline
	// exists: after ten seconds the server is closed anyway, and the recover in
	// Start is what keeps that from becoming a crash.
	waited := make(chan struct{})
	go func() {
		upInFlight.Wait()
		close(waited)
	}()
	select {
	case <-waited:
	case <-time.After(10 * time.Second):
	}

	if closeErr := server.Close(); closeErr != nil {
		return fmt.Errorf("%s", redact(closeErr.Error()))
	}
	return nil
}

// ClearState removes the persisted node state.
//
// Call this when the user changes the auth key or the control URL: the old
// node identity is meaningless against a new control plane, and leaving it in
// place makes tsnet reuse it and silently ignore the new key.
func ClearState(stateDir string) error {
	if err := Stop(); err != nil {
		return err
	}
	if strings.TrimSpace(stateDir) == "" {
		return fmt.Errorf("stateDir must not be empty")
	}
	// Only ever remove the node subdirectory this package created.
	target := filepath.Join(stateDir, "node")
	if err := os.RemoveAll(target); err != nil {
		return fmt.Errorf("cannot clear node state: %w", err)
	}
	return nil
}

// ---------------------------------------------------------------------------
// Status
// ---------------------------------------------------------------------------

// Status reports the node's current state as a JSON document:
//
//	{
//	  "state":      "running" | "stopped" | "error",
//	  "ip4":        "100.x.y.z" | "",
//	  "ip6":        "fd7a:..." | "",
//	  "hostname":   "...",
//	  "controlURL": "..." | "",
//	  "ephemeral":  true|false,
//	  "loginURL":   "https://..." | "",
//	  "error":      "" | "<redacted message>",
//	  "tailnet":    "..."  // the DNS suffix reported by the control plane, if any
//	}
//
// The auth key is never included, in any form, not even a hash of it.
func Status() (out string) {
	mu.Lock()
	defer mu.Unlock()

	doc := map[string]any{
		"state":      "stopped",
		"ip4":        "",
		"ip6":        "",
		"hostname":   nodeCfg.Hostname,
		"controlURL": nodeCfg.ControlURL,
		"ephemeral":  nodeCfg.Ephemeral,
		"loginURL":   lastURL,
		"error":      lastErr,
		"tailnet":    "",
	}

	// Answer, never panic.
	//
	// Measured 2026-09-15: a node that exists but never came up has a backend with
	// no netmap, and asking it for its addresses panics inside tailscale itself
	// (ipnlocal.LocalBackend.currentNode on a nil netmap, via tsnet's
	// TailscaleIPs). The diagnostics page calls this function precisely when a
	// start has FAILED, so an unguarded call would turn "the node did not come up"
	// into "the app died while I was looking at why" -- in a JNI call, so the whole
	// process goes. The recover below is what makes this function usable as a
	// diagnostic.
	defer func() {
		if r := recover(); r != nil {
			doc["state"] = "error"
			doc["error"] = fmt.Sprintf("status unavailable: %v", r)
			out = mustJSON(doc)
		}
	}()

	if node == nil {
		return mustJSON(doc)
	}

	// Only a node that finished coming up has a netmap to read.
	if !started {
		if lastErr != "" {
			doc["state"] = "error"
		} else {
			doc["state"] = "starting"
		}
		return mustJSON(doc)
	}

	ip4, ip6 := node.TailscaleIPs()
	// Report an address only when there is one. netip's zero value stringifies
	// as "invalid IP", which would show up verbatim in the app's diagnostics.
	if ip4.IsValid() {
		doc["ip4"] = ip4.String()
	}
	if ip6.IsValid() {
		doc["ip6"] = ip6.String()
	}

	// A node that has state but no address has not finished coming up.
	if started && ip4.IsValid() {
		doc["state"] = "running"
	} else if lastErr != "" {
		doc["state"] = "error"
	} else {
		doc["state"] = "starting"
	}

	// SelfDNSName carries the MagicDNS suffix the control plane assigned. It is
	// what turns a bare hostname into a dialable FQDN, so the app surfaces it.
	if lc, err := node.LocalClient(); err == nil {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if st, err := lc.Status(ctx); err == nil && st != nil && st.Self != nil {
			doc["tailnet"] = string(st.Self.DNSName)
		}
	}

	return mustJSON(doc)
}

// Logs returns the captured, already-redacted node log lines as a JSON array.
//
// This is a diagnostic aid, not a log sink: the ring is in memory only, so
// nothing survives the process and nothing is inheritable by another app.
func Logs() string {
	mu.Lock()
	defer mu.Unlock()
	if logRing == nil {
		logRing = []string{}
	}
	return mustJSON(logRing)
}

// ---------------------------------------------------------------------------
// Reaching the target
// ---------------------------------------------------------------------------

// Probe tests whether a TCP connection to targetAddr succeeds through the
// tailnet, and reports how long it took.
//
// targetAddr is `host:port`, where host is a `100.x.y.z` literal or a MagicDNS
// name. Probing by IP is recommended: it removes DNS from the path, so a
// failure means the network is wrong rather than the resolver.
//
// Returns JSON:
//
//	{"ok":true,"ms":142,"resolved":"100.101.102.103","error":""}
//
// A false `ok` is not an exception: a connection that is refused is a normal,
// informative outcome for a "test connection" button, so it is reported in the
// document rather than thrown.
func Probe(targetAddr string, timeoutMs int) (out string) {
	result := map[string]any{
		"ok":       false,
		"ms":       0,
		"resolved": "",
		"error":    "",
	}

	// Same reasoning as Status: this is asked of a node that may not have come up,
	// by a diagnostics page whose whole job is the case where something failed.
	defer func() {
		if r := recover(); r != nil {
			result["error"] = fmt.Sprintf("probe failed unexpectedly: %v", r)
			out = mustJSON(result)
		}
	}()

	mu.Lock()
	srv := node
	key := nodeCfg.AuthKey
	mu.Unlock()

	if srv == nil {
		result["error"] = "tailnet node is not running"
		return mustJSON(result)
	}
	if strings.TrimSpace(targetAddr) == "" {
		result["error"] = "no target address"
		return mustJSON(result)
	}
	if timeoutMs <= 0 {
		timeoutMs = 8_000
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	start := time.Now()
	conn, err := srv.Dial(ctx, "tcp", targetAddr)
	elapsed := time.Since(start).Milliseconds()
	result["ms"] = elapsed

	if err != nil {
		result["error"] = redact(err.Error(), key)
		return mustJSON(result)
	}
	defer conn.Close()

	result["ok"] = true
	if addr := conn.RemoteAddr(); addr != nil {
		if host, _, splitErr := net.SplitHostPort(addr.String()); splitErr == nil {
			result["resolved"] = host
		}
	}
	return mustJSON(result)
}

// fetchTarget validates a Fetch destination against the same allowlist the
// reverse proxy enforces, and returns the parsed URL.
//
// This is the whole anti-SSRF rule for Fetch: scheme, authority and membership
// of the installed policy, in that order, with no path that skips it. It calls
// targetAllowed and originOf -- the same predicate and the same origin spelling
// the proxy's StartProxy uses -- rather than repeating the comparison, because
// two implementations of "is this host allowed" is exactly how one of them ends
// up disagreeing with the other.
//
// key is the auth key to redact from any parse error; an error string from
// url.Parse echoes the input, and the input here is caller-supplied.
func fetchTarget(raw string, key string) (*url.URL, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, fmt.Errorf("no target URL")
	}
	u, err := url.Parse(trimmed)
	if err != nil {
		return nil, fmt.Errorf("malformed URL: %s", redact(err.Error(), key))
	}
	// Only http and https. Anything else (file, ftp, or a bare "host:port" that
	// parses as an opaque scheme) is refused by shape, before the allowlist is
	// even consulted: the allowlist is not asked to enumerate bad schemes.
	if u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return nil, fmt.Errorf("target must be an absolute http(s) URL")
	}
	origin := originOf(u)
	if !targetAllowed(origin) {
		return nil, fmt.Errorf("target %s is not allowed by the security config", redact(origin, key))
	}
	return u, nil
}

// applyFetchHeaders copies the caller's headers onto the request, minus the ones
// this package decides for itself.
//
// `Host` is dropped. Go writes the authority from Request.Host/URL, so the
// header name itself is ignored by the transport, but a key spelled `Host:`
// reaches the wire as a literal `Host::` line and some servers and proxies read
// that as an authority -- and either spelling is a request to address a host the
// caller was not granted. The allowlisted origin is the only authority this
// request may carry, so both spellings go.
//
// The hop-describing headers (forwardedHeaders, shared with the reverse proxy's
// Rewrite) are dropped for the same reason the proxy drops them: they describe
// this device, not the target, and a target's own ingress commonly trusts a
// forwarded authority it can see.
func applyFetchHeaders(req *http.Request, headers map[string]string) {
	for name, value := range headers {
		if isHostHeaderName(name) || isForwardedHeaderName(name) {
			continue
		}
		req.Header.Set(name, value)
	}
}

// isHostHeaderName reports whether a caller-supplied header name is an attempt
// to restate the request's authority, in either spelling.
func isHostHeaderName(name string) bool {
	trimmed := strings.TrimSpace(name)
	return strings.EqualFold(trimmed, "Host") || strings.EqualFold(trimmed, "Host:")
}

// fetchRedirectAllowed is Fetch's redirect policy, factored out of the closure
// so the decision can be tested for what it decides rather than for where it is
// wired.
//
// Two rules, in this order:
//
//   - The hop's destination must be on the allowlist. A redirect target is
//     chosen by the host we were allowed to reach, not by the user, so it is
//     subject to the same policy as the first request. Without this a single
//     302 would turn Fetch -- and the netstack behind it -- into a request
//     forwarder for any address the target names.
//   - A hop that lands somewhere else (reachable only when the allowlist itself
//     has more than one entry) does not carry the caller's headers: they were
//     chosen for the host that was asked for, not for the one that answered.
//     Cookies need no equivalent rule, because the jar is host-scoped and
//     simply has nothing for the new host.
func fetchRedirectAllowed(next *http.Request, via []*http.Request, original *url.URL, headers map[string]string) error {
	if len(via) >= maxRedirects {
		return fmt.Errorf("stopped after %d redirects", maxRedirects)
	}
	if !targetAllowed(originOf(next.URL)) {
		// Deliberately does not name the destination: the host is chosen by the
		// upstream, and this text is handed back to the caller.
		return fmt.Errorf("refusing to follow a redirect off the allowlist")
	}
	if original != nil && !strings.EqualFold(next.URL.Host, original.Host) {
		for name := range headers {
			next.Header.Del(name)
		}
	}
	return nil
}

// Fetch performs one HTTP request through the tailnet and returns the response
// as JSON:
//
//	{
//	  "status":   200,
//	  "headers":  {"content-type": ["text/plain"]},
//	  "bodyB64":  "...",           // base64 of the raw body, at most maxBodyBytes
//	  "bodyText": "...",           // lossy UTF-8 view, for showing a health check
//	  "truncated": false,
//	  "ms":       187,
//	  "error":    ""
//	}
//
// headersJSON is a JSON object of string->string. bodyBase64 may be empty.
//
// This is how the app talks to the target service. Keeping HTTP inside Go is
// not an accident: the socket must be created by the tsnet netstack, and Go
// cannot hand a `net.Conn` to Java. Returning the bytes is the only honest way
// to cross that boundary.
//
// # The target must be on the proxy's allowlist
//
// Fetch dials, which makes it a second way out of this process next to the
// loopback proxy, so it is bound by the same policy: the destination's origin
// has to be listed in SecurityConfig.AllowedTargets, checked with the same
// predicate the proxy uses (targetAllowed), and every redirect hop is checked
// again. A target that is not on the list is refused before the node is even
// consulted, so "refused" and "no connection was attempted" are one event
// rather than two things that happen to agree.
//
// The caller's headers are forwarded with one exception: `Host` is dropped, in
// every spelling. The request's authority is the allowlisted origin's and
// nothing else -- restating it would let one allowlisted address be reached as
// any virtual host on it. `Cookie`, by contrast, is forwarded, and deliberately:
// the only hosts reachable from here passed the allowlist, and the cookie jar is
// host-scoped, so what travels is exactly the credential the shell obtained from
// a host it was allowed to talk to -- which is what a hosted DSH host's
// 303 + Set-Cookie handshake requires.
func Fetch(
	targetURL string,
	method string,
	headersJSON string,
	bodyBase64 string,
	timeoutMs int,
	maxBodyBytes int,
) (out string) {
	const defaultMaxBody = 256 * 1024

	result := map[string]any{
		"status":    0,
		"headers":   map[string][]string{},
		"bodyB64":   "",
		"bodyText":  "",
		"truncated": false,
		"ms":        0,
		"error":     "",
	}

	// Same reasoning as Status and Probe: never take the process down instead of
	// reporting. A nil netmap is reachable from here too, through the node's own
	// HTTP plumbing.
	defer func() {
		if r := recover(); r != nil {
			result["error"] = fmt.Sprintf("request failed unexpectedly: %v", r)
			out = mustJSON(result)
		}
	}()

	mu.Lock()
	srv := node
	key := nodeCfg.AuthKey
	mu.Unlock()

	// The allowlist gate, and deliberately the first thing in this function that
	// can refuse. Fetch dials, so the only honest place to enforce the policy is
	// here: before the node is consulted, before a request is built, and before
	// any dial path exists at all. That ordering is what makes "refused" and "no
	// connection was attempted" the same event rather than two things that
	// happen to agree -- an off-list target returns an allowlist error even when
	// no node is running, which is only possible if the check came first.
	target, err := fetchTarget(targetURL, key)
	if err != nil {
		result["error"] = err.Error()
		return mustJSON(result)
	}

	if srv == nil {
		result["error"] = "tailnet node is not running"
		return mustJSON(result)
	}
	if method == "" {
		method = http.MethodGet
	}
	if timeoutMs <= 0 {
		timeoutMs = 15_000
	}
	if maxBodyBytes <= 0 {
		maxBodyBytes = defaultMaxBody
	}

	headers := map[string]string{}
	if strings.TrimSpace(headersJSON) != "" {
		if err := json.Unmarshal([]byte(headersJSON), &headers); err != nil {
			result["error"] = "malformed headers: " + err.Error()
			return mustJSON(result)
		}
	}

	var body []byte
	if bodyBase64 != "" {
		decoded, err := base64.StdEncoding.DecodeString(bodyBase64)
		if err != nil {
			result["error"] = "malformed body encoding"
			return mustJSON(result)
		}
		body = decoded
	}

	req, err := http.NewRequest(method, target.String(), strings.NewReader(string(body)))
	if err != nil {
		result["error"] = redact(err.Error(), key)
		return mustJSON(result)
	}
	applyFetchHeaders(req, headers)
	// tsnet's HTTPClient() hands back a client with a tailnet transport and
	// nothing else -- notably no cookie jar. A hosted DSH host sits behind an
	// auth proxy that replies 303 + Set-Cookie and only serves the real page when
	// the follow-up carries that cookie, so without a jar every hop looks like a
	// brand-new client and the chain runs until the cap: exactly the
	// "Too many follow-up requests" / "stopped after N redirects" failure seen on
	// the OkHttp side before it grew a jar of its own.
	client := srv.HTTPClient()
	mu.Lock()
	client.Jar = httpJar
	mu.Unlock()
	client.Timeout = time.Duration(timeoutMs) * time.Millisecond
	// Redirects are followed, but never as a licence to replay the caller's
	// credentials against a host we were not asked to reach, and never off the
	// allowlist. A redirect's destination is chosen by the host we were allowed
	// to reach, not by the user, so each hop is put through the same policy as
	// the first request: without that check a single 302 would turn this
	// function -- and the netstack behind it -- into a request forwarder for any
	// address the target names, which is exactly what the allowlist exists to
	// prevent.
	//
	// Cookies are resolved by the jar, which is host-scoped, so a hop to a
	// different host simply arrives without them; the headers the caller
	// supplied are dropped on those hops for the same reason. (That branch is
	// reachable when the allowlist has more than one entry, which an embedder
	// may well configure.)
	originalURL := req.URL
	client.CheckRedirect = func(next *http.Request, via []*http.Request) error {
		return fetchRedirectAllowed(next, via, originalURL, headers)
	}

	start := time.Now()
	resp, err := client.Do(req)
	result["ms"] = time.Since(start).Milliseconds()
	if err != nil {
		result["error"] = redact(err.Error(), key)
		return mustJSON(result)
	}
	defer resp.Body.Close()

	limited := make([]byte, 0, 4096)
	buf := make([]byte, 32*1024)
	truncated := false
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			remaining := maxBodyBytes - len(limited)
			if remaining <= 0 {
				truncated = true
				break
			}
			if n > remaining {
				limited = append(limited, buf[:remaining]...)
				truncated = true
				break
			}
			limited = append(limited, buf[:n]...)
		}
		if readErr != nil {
			break
		}
	}

	result["status"] = resp.StatusCode
	result["headers"] = resp.Header
	result["bodyB64"] = base64.StdEncoding.EncodeToString(limited)
	result["bodyText"] = redact(string(limited), key)
	result["truncated"] = truncated
	return mustJSON(result)
}

// Redact exposes the redactor to Kotlin so both sides of the JNI boundary apply
// the same rules. Kotlin has its own copy for log lines it generates itself;
// this entry point exists for strings this package produced.
func Redact(value string) string { return redact(value, currentKey()) }

// Version reports the Tailscale library version this bridge was built against,
// so a bug report can name it without the user digging through an AAR.
//
// It returns version.Long() verbatim: the same string this node reports to the
// control plane as HostInfo.IPNVersion, and the same one it prints in its own
// startup log. Reporting anything else here would mean the app and the tailnet
// admin console could disagree about what is running.
//
// The value is stamped into the library at link time by
// scripts/build-bridge.mjs, because gomobile compiles a copy of the module in a
// temp directory where the Go tool finds no VCS data and version.Long() falls
// back to "1.102.4-ERR-BuildInfo". A wrong or missing stamp therefore shows up
// here on purpose: this function is how that failure becomes visible instead of
// staying in a log ring nobody reads.
func Version() string { return tsversion.Long() }

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

func captureLog(format string, args ...any) {
	appendLog(fmt.Sprintf(format, args...))
}

// captureUserLog records lines tsnet marks as user-facing. The interactive login
// URL arrives this way, and it is the only way to obtain it -- Status() surfaces
// it as `loginURL`.
func captureUserLog(format string, args ...any) {
	line := fmt.Sprintf(format, args...)
	appendLog(line)
	if u := firstHTTPURL(line); u != "" {
		mu.Lock()
		lastURL = u
		mu.Unlock()
	}
}

func appendLog(line string) {
	line = redact(strings.TrimSpace(line))
	if line == "" {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	if len(logRing) >= maxLogLines {
		copy(logRing, logRing[len(logRing)-maxLogLines/2:])
		logRing = logRing[:maxLogLines/2]
	}
	logRing = append(logRing, line)
}

// redact removes anything credential-shaped or personally identifying from a
// string before it is allowed to leave this package. It is intentionally
// coarse: a false positive costs a truncated diagnostic line, a false negative
// costs a leaked key or address.
func redact(value string, secrets ...string) string {
	out := value
	for _, secret := range secrets {
		if len(secret) >= 8 {
			out = strings.ReplaceAll(out, secret, "***")
		}
	}
	for _, prefix := range []string{"tskey-", "hskey-", "tsclientsecret-"} {
		// The scan offset only ever moves forward, and that is the whole fix
		// here. An earlier version searched from the start of the string after
		// every rewrite, and because the rewrite puts the prefix itself back
		// into the output, the same occurrence was found again on every pass:
		// the string grew by three bytes per iteration and the call never
		// returned. Any input containing the literal prefix was enough to hang
		// the caller -- and one of those callers is Fetch, which redacts the
		// target's response body, so a target could do it remotely.
		for offset := 0; ; {
			idx := strings.Index(out[offset:], prefix)
			if idx < 0 {
				break
			}
			idx += offset
			end := idx + len(prefix)
			for end < len(out) && isKeyChar(out[end]) {
				end++
			}
			// A prefix with no key characters after it is still masked: it is
			// the marker that identifies a credential, and a fragment of one is
			// not something to leave in a log line.
			out = out[:idx] + prefix + "***" + out[end:]
			offset = idx + len(prefix) + len("***")
		}
	}
	out = maskProxyCredentials(out)
	out = maskEmails(out)
	out = maskAddresses(out)
	out = maskIPv6Addresses(out)
	out = maskTailnetDNSNames(out)
	return out
}

// proxyCredentialPattern finds the loopback proxy's two credentials wherever
// they appear as a name=value pair: `dshproxy` in a query string (the one-shot
// bootstrap token, which is in the URL the WebView loads and therefore in any
// URL pasted into a bug report) and `dsh_proxy` in a Cookie header (the standing
// session credential the shell presents on its own requests).
//
// The names are interpolated from the constants the proxy actually uses, so a
// rename cannot leave the redactor watching for a name nothing sends. The
// character before the name must be a non-word character, which is what keeps
// `mydshproxy=` from being read as `dshproxy=`.
//
// The separator is matched in both its literal and its percent-encoded form:
// `?dshproxy%3D<token>` is the same capability as `?dshproxy=<token>`, and a URL
// that has been through one more encoding pass is exactly the shape that ends up
// pasted into a report.
var proxyCredentialPattern = regexp.MustCompile(
	`(?i)(^|[^A-Za-z0-9_])(` +
		regexp.QuoteMeta(proxyTokenParam) + `|` + regexp.QuoteMeta(proxyTokenCookie) +
		`)(=|%3D)[^&\s"';,<>)]+`)

// maskProxyCredentials replaces the value of either proxy credential, keeping
// the name and the separator's spelling so the line still says which one it was
// found in, and how. This is belt and braces: the token and the cookie are never
// logged by this package, but they do travel through the status document handed
// to the shell, and the redactor is what protects the day one of them reaches a
// log line.
func maskProxyCredentials(s string) string {
	return proxyCredentialPattern.ReplaceAllString(s, "${1}${2}${3}<redacted>")
}

// emailPattern matches an address well enough to scrub the local part. Keeping
// the domain (the provider) is the point: it lets a diagnostic still say "an
// address at this provider", while the individual part is what must never
// appear in a pasted log.
var emailPattern = regexp.MustCompile(`[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}`)

func maskEmails(s string) string {
	return emailPattern.ReplaceAllStringFunc(s, func(addr string) string {
		at := strings.IndexByte(addr, '@')
		if at <= 0 {
			return addr
		}
		return "***@" + addr[at+1:]
	})
}

// ipv4Pattern matches a dotted quad. A version string like "1.102.4" has only
// three parts and is deliberately not matched.
var ipv4Pattern = regexp.MustCompile(`\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b`)

// maskAddresses blanks the last octet of any IPv4 literal that is not this
// app's own working address space. Tailnet CGNAT (100.64.0.0/10) and loopback
// are the addresses a diagnostic actually needs to name -- the node's IP, the
// peer it dials -- and they identify nothing outside the tailnet, so they stay.
// Everything else (a LAN address, a public address) loses its host octet, which
// is the part that would otherwise identify a machine.
func maskAddresses(s string) string {
	return ipv4Pattern.ReplaceAllStringFunc(s, func(addr string) string {
		parts := strings.Split(addr, ".")
		if len(parts) != 4 {
			return addr
		}
		first, err1 := strconv.Atoi(parts[0])
		second, err2 := strconv.Atoi(parts[1])
		if err1 != nil || err2 != nil {
			return addr
		}
		isLoopback := first == 127
		isTailnet := first == 100 && second >= 64 && second <= 127
		if isLoopback || isTailnet {
			return addr
		}
		return parts[0] + "." + parts[1] + "." + parts[2] + ".*"
	})
}

// ipv6Pattern matches the shape of an IPv6 literal, compressed form included,
// with an optional zone and no brackets (so `[::1]:3080` yields `::1`).
//
// The shape is matched loosely on purpose -- a clock time like `12:30:45` has
// the same number of colons as a short address -- and every candidate is handed
// to netip before anything is masked, so a match that is not an address is left
// exactly as it was. Getting this backwards would mangle timestamps in every log
// line, which is a worse diagnostic tool than not masking at all.
var ipv6Pattern = regexp.MustCompile(`(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{0,4}(?:%[0-9A-Za-z._~\-]+)?`)

// tailnetULA is the IPv6 prefix Tailscale allocates inside a tailnet
// (fd7a:115c:a1e0::/48). An address inside it identifies a node on one user's
// private network and nothing outside it.
var tailnetULA = netip.MustParsePrefix("fd7a:115c:a1e0::/48")

// maskIPv6Addresses blanks the interface identifier of an IPv6 literal that is
// neither loopback nor inside the tailnet's own ULA range, keeping the /64
// prefix.
//
// The two exceptions follow the IPv4 rule for the same reason: `::1` is the
// proxy's own listener address and fd7a:115c:a1e0::/48 is the user's private
// address space, so naming either tells a reader what kind of address it was
// without identifying a machine to anyone outside the tailnet.
//
// The /64 is kept because it is the IPv6 analogue of the three octets
// maskAddresses keeps: the subnet, which is the part that makes a diagnostic
// line worth reading, while the interface identifier -- the half bound to a
// device -- is gone. Masking only the trailing hextet, which an earlier version
// did, left seven eighths of the identifier in place and was not a redaction at
// all.
//
// The value is rebuilt from the parsed address instead of being edited in place,
// because the textual form is compressed and there is no reliable colon to cut
// at: in `2001:db8::1` the host is the only hextet a naive tail cut would keep.
func maskIPv6Addresses(s string) string {
	return ipv6Pattern.ReplaceAllStringFunc(s, func(candidate string) string {
		addr, err := netip.ParseAddr(candidate)
		if err != nil {
			return candidate
		}
		if addr.IsLoopback() || tailnetULA.Contains(addr) {
			return candidate
		}
		groups := addr.As16()
		return fmt.Sprintf("%x:%x:%x:%x:*", groups[0:2], groups[2:4], groups[4:6], groups[6:8])
	})
}

// tailnetDNSPattern matches a MagicDNS name: one or more labels followed by
// `ts.net`, the suffix the control plane gives every tailnet, in the shape
// `<machine>.<tailnet>.ts.net`.
//
// It is written to over-match rather than under-match. Go's regexp has no
// lookahead, and the alternatives were "also mask a name that happens to be
// followed by more domain parts" or "leave such a name unmasked"; a false
// positive costs an odd-looking diagnostic line, and a false negative publishes
// the user's tailnet name.
var tailnetDNSPattern = regexp.MustCompile(`(?i)(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+ts\.net`)

// maskTailnetDNSNames replaces the identifying labels of a MagicDNS name,
// keeping the suffix. The suffix is what a reader needs -- "this is a tailnet
// name" -- and the labels before it are the machine name and the tailnet name,
// which are the two things a user asking for help should not have to publish.
// The whole name is also the node's dialable identity inside the tailnet, so it
// is a persistent identifier rather than a label.
func maskTailnetDNSNames(s string) string {
	return tailnetDNSPattern.ReplaceAllString(s, "***.ts.net")
}

func isKeyChar(c byte) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' ||
		c == '-' || c == '_'
}

func firstHTTPURL(line string) string {
	for _, scheme := range []string{"https://", "http://"} {
		idx := strings.Index(line, scheme)
		if idx < 0 {
			continue
		}
		rest := line[idx:]
		end := strings.IndexAny(rest, " \t\n\r\"'")
		if end > 0 {
			rest = rest[:end]
		}
		rest = strings.TrimRight(rest, ".,;:")
		if parsed, err := url.Parse(rest); err == nil && parsed.Host != "" {
			return rest
		}
	}
	return ""
}

func currentKey() string {
	mu.Lock()
	defer mu.Unlock()
	return nodeCfg.AuthKey
}

func mustJSON(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return `{"error":"failed to encode response"}`
	}
	return string(encoded)
}
