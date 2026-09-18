//go:build ignore

// This file is never compiled. It documents the gomobile entry points in Go
// syntax so that a reader can see the contract without reading tailnet.go, and
// so that `gofmt -l` and linters have something anchored.

package main

// The exported surface, and nothing else. Every argument and every return value
// is a String, Int, Bool or error — see the binding rules in tailnet.go.
//
//	func Start(stateDir, authKey, controlURL, hostname string,
//	           ephemeral, forceLogin bool, timeoutMs int) error
//	func Stop() error
//	func ClearState(stateDir string) error
//	func Status() string          // JSON
//	func Logs() string            // JSON array of already-redacted lines
//	func Probe(targetAddr string, timeoutMs int) string   // JSON
//	func Fetch(targetURL, method, headersJSON, bodyBase64 string,
//	           timeoutMs, maxBodyBytes int) string        // JSON
//	func Redact(value string) string
//	func Version() string
//
// Android-only, from netif_android.go (a host build does not have it):
//
//	func SetInterfaceSnapshot(jsonDoc string) error   // call this before Start

func main() {}
