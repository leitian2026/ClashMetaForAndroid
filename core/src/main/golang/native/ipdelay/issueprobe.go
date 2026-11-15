package ipdelay

// CUSTOM (CUSTOM.md #53, not part of upstream): what the DNS check of the runtime problem check (package issuecheck)
// needs from this package. Nothing here runs on its own.

import "sync/atomic"

// WithSelfDial runs f while our own sockets are being made, so that they are not taken for real traffic (the node IP
// shown on the home page, the "in use" marks).
func WithSelfDial(f func()) {
	atomic.AddInt32(&selfDialing, 1)
	defer atomic.AddInt32(&selfDialing, -1)

	f()
}
