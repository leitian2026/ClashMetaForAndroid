// Package screenstate shares the service-reported screen state with native custom packages.
package screenstate

import (
	"sync"
	"sync/atomic"
)

var (
	interactive int32 = 1 // default to screen on until the service reports otherwise
	listenersMu sync.Mutex
	listeners   []func(bool)
)

// SetInteractive stores the current screen state and notifies registered listeners.
func SetInteractive(value bool) {
	if value {
		atomic.StoreInt32(&interactive, 1)
	} else {
		atomic.StoreInt32(&interactive, 0)
	}

	listenersMu.Lock()
	current := append([]func(bool){}, listeners...)
	listenersMu.Unlock()

	for _, listener := range current {
		listener(value)
	}
}

// Interactive reports whether the screen is currently on.
func Interactive() bool {
	return atomic.LoadInt32(&interactive) != 0
}

// RegisterListener registers a callback for subsequent screen-state updates.
func RegisterListener(listener func(bool)) {
	if listener == nil {
		return
	}

	listenersMu.Lock()
	listeners = append(listeners, listener)
	listenersMu.Unlock()
}
