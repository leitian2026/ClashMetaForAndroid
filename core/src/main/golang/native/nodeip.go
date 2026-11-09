package main

//#include "bridge.h"
import "C"

import (
	"sync"
	"unsafe"

	"cfa/native/ipdelay"
)

// CUSTOM (CUSTOM.md #7): tells the app when the node IP in use changes. Reuses the FetchCallback
// JNI plumbing (fetch_report) of the bridge, so no new C glue besides the subscribe entry point.
var (
	nodeIPLock   sync.Mutex
	nodeIPRemote unsafe.Pointer
)

//export subscribeNodeIp
func subscribeNodeIp(remote unsafe.Pointer) {
	nodeIPLock.Lock()
	old := nodeIPRemote
	nodeIPRemote = remote
	nodeIPLock.Unlock()

	if old != nil {
		C.release_object(old)
	}

	ipdelay.SetOnChange(func() {
		nodeIPLock.Lock()
		r := nodeIPRemote
		nodeIPLock.Unlock()

		if r != nil {
			C.fetch_report(r, marshalString("changed"))
		}
	})
}
