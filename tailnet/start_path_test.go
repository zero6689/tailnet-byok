package mobile

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"
)

// startBounded calls Start on its own goroutine and gives up waiting after
// timeout, reporting whether it came back at all.
//
// This is not test hygiene for its own sake. The first version of this test called
// Start directly, and when Start did not return the test binary hung -- which took
// down the whole build lane behind it (measured 2026-09-15 01:26: a go process
// still running minutes later, the lane lock held, and every later lane in that
// tick blocked). A hang has to be reported, not endured.
func startBounded(stateDir, authKey, controlURL, hostname string, timeoutMs int, wait time.Duration) (error, bool) {
	done := make(chan error, 1)
	go func() {
		done <- Start(stateDir, authKey, controlURL, hostname, false, false, timeoutMs)
	}()
	select {
	case err := <-done:
		return err, true
	case <-time.After(wait):
		return nil, false
	}
}

// The shell calls start -> (maybe) stop in several states: first run without a
// key, a rebuild after a network change, and every onDestroy. The path that
// matters most and is easiest to get wrong is the one where starting FAILS,
// because the node object is deliberately left alive (so an interactive login URL
// stays retrievable) and every later call has to cope with it.
//
// A control server that cannot exist (127.0.0.1:1) drives exactly that path: no
// tailnet is touched, nothing is registered, and the failure is the real one from
// tsnet rather than a mock.
func TestStartFailsCleanlyAgainstAnUnreachableControlServer(t *testing.T) {
	stateDir := t.TempDir()
	const fakeKey = "tskey-auth-THISMUSTNOTAPPEARINANYOUTPUT" // not-a-secret: fixture

	err, returned := startBounded(stateDir, fakeKey, "https://127.0.0.1:1", "byok-test", 5000, 30*time.Second)
	if !returned {
		t.Fatal("Start did not return within 30s against an unreachable control server; " +
			"a phone would sit on a blank screen with no error and no fallback")
	}
	if err == nil {
		t.Fatal("Start reported success against a control server that cannot exist")
	}

	// The rule this project holds itself to: an auth key never reaches a string
	// the user can read, share or screenshot. The error is redacted at the source,
	// and status()/logs() are what the diagnostics page prints verbatim.
	if strings.Contains(err.Error(), fakeKey) {
		t.Fatalf("the auth key leaked into the error: %v", err)
	}
	if s := Status(); strings.Contains(s, fakeKey) {
		t.Fatalf("the auth key leaked into status(): %s", s)
	}
	if l := Logs(); strings.Contains(l, fakeKey) {
		t.Fatalf("the auth key leaked into logs(): %s", l)
	}

	// Diagnostics have to say something useful about the failure, or the page is
	// decoration.
	if s := Status(); !strings.Contains(s, `"state"`) || !strings.Contains(s, `"error"`) {
		t.Fatalf("status() does not report a state and an error: %s", s)
	}

	// The literal key prefix is the other half of the rule above, and it used to
	// be a hang rather than a leak: redact() found `tskey-`, put the prefix back
	// into the output and then searched from the start again, so any string
	// containing it grew by three bytes per pass and never returned. This test
	// completing at all was evidence that nothing in the start path carried the
	// bare prefix -- an unreachable control server produces no line quoting the
	// key, and the full key is removed by the explicit-secret pass before the
	// prefix scan runs -- but that was evidence by absence. With the scan made
	// monotonic, what is left to assert is the outcome: the mask always consumes
	// every key character that follows the prefix, so a fragment like
	// `tskey-auth-` surviving means the redactor is broken again, whatever the
	// input was.
	unmaskedKey := regexp.MustCompile(`tskey-[A-Za-z0-9_-]+`)
	for name, document := range map[string]string{"error": err.Error(), "status()": Status(), "logs()": Logs()} {
		if found := unmaskedKey.FindString(document); found != "" {
			t.Fatalf("an unmasked key fragment reached %s: %q", name, found)
		}
	}

	// The scratch directories belong to starting, not to succeeding: a later
	// attempt must not be the first time they are created.
	if _, statErr := os.Stat(filepath.Join(stateDir, "scratch", "logs")); statErr != nil {
		t.Fatalf("prepareScratchDirs did not run on the failing path: %v", statErr)
	}

	// Stop after a failed start must be safe and must not hang: the shell calls it
	// unconditionally on destroy and before every rebuild. An error here is not
	// itself a failure (the backend was never up), so it is reported, not asserted.
	if stopErr := Stop(); stopErr != nil {
		t.Logf("Stop after a failed start returned: %v", stopErr)
	}

	// And the wrapper must be reusable afterwards: a failed attempt cannot poison
	// the next one, or "保存并连接" would work exactly once per process.
	if _, returned := startBounded(stateDir, "", "https://127.0.0.1:1", "byok-test", 3000, 30*time.Second); !returned {
		t.Fatal("the second Start did not return either")
	}
	_ = Stop()
}
