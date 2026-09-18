package mobile

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The scratch-directory setup runs before every node start, and its whole job is
// to answer questions Tailscale's own code asks with desktop defaults (/tmp,
// ~/.cache) that do not exist on Android.
//
// The assertions are on the environment variables we set and on the directories
// actually existing, not on os.TempDir() or os.UserCacheDir(): on Windows those
// two resolve through %TMP%/%TEMP% and %LocalAppData% and ignore exactly the
// variables under test, so asserting the resolved value would fail on this host
// while proving nothing about Android. That the variables are what the Unix
// implementations read is Go's documented behaviour, and the point here is that
// we set them and create the targets.
func TestPrepareScratchDirsPointsGoScratchAtOurOwnDirectory(t *testing.T) {
	dir := t.TempDir()

	// t.Setenv restores these when the test ends, so nothing leaks into the rest
	// of the package's tests.
	t.Setenv("TMPDIR", "/nonexistent-on-purpose")
	t.Setenv("XDG_CACHE_HOME", "/nonexistent-on-purpose")
	t.Setenv("TS_LOGS_DIR", "/nonexistent-on-purpose")

	prepareScratchDirs(dir)

	for _, name := range []string{"TMPDIR", "XDG_CACHE_HOME", "TS_LOGS_DIR"} {
		got := os.Getenv(name)
		if !strings.HasPrefix(got, dir) {
			t.Fatalf("%s = %q, want a path inside %q", name, got, dir)
		}
	}

	// The directories have to exist, not merely be named: the failure this guards
	// against is a later os.MkdirTemp or log write that cannot be served.
	for _, sub := range []string{"tmp", "cache", "logs"} {
		p := filepath.Join(dir, "scratch", sub)
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatalf("scratch/%s does not exist: %v", sub, err)
		}
		if !fi.IsDir() {
			t.Fatalf("scratch/%s is not a directory", sub)
		}
	}

	// A directory that cannot be created must not panic or half-apply: the node
	// still has to be able to start. An unwritable path under a file is the
	// cheapest stand-in for "this device said no".
	blocker := filepath.Join(dir, "afile")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("setup: %v", err)
	}
	prepareScratchDirs(filepath.Join(blocker, "cannot-be-a-directory"))
}
