// This file exists solely to pull in gomobile's binding runtime.
//
// `gomobile bind` scans the target package for the bind package's side effects;
// without this import the generated Java surface is incomplete in ways that only
// show up at runtime, as a LinkageError on the first call. Every project that
// ships a gomobile AAR carries a file like this one, and it is easier to keep
// than to rediscover.
//
// Do not "clean up" this file because it looks unused.
package mobile

import (
	// Required for gomobile bind.
	_ "golang.org/x/mobile/bind"
)
