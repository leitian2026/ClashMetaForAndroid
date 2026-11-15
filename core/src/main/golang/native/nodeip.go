package main

//#include "bridge.h"
import "C"

import (
	"sync"
	"unsafe"

	"cfa/native/errlog"
	"cfa/native/ipdelay"
	"cfa/native/issuecheck"
	"cfa/native/issues"

	mc "github.com/metacubex/mihomo/constant"
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

	// CUSTOM (CUSTOM.md #53): problems of the running config reach the app the same way, with another word
	issues.SetDir(mc.Path.HomeDir())
	errlog.Hook = issues.FromLog
	issues.OnDNSFailed = issuecheck.DNSFailed // a connection failed because no DNS server answered: ask them once
	issues.SetOnChange(func() {
		nodeIPLock.Lock()
		r := nodeIPRemote
		nodeIPLock.Unlock()

		if r != nil {
			C.fetch_report(r, marshalString("issues"))
		}
	})

	ipdelay.SetOnChange(func() {
		nodeIPLock.Lock()
		r := nodeIPRemote
		nodeIPLock.Unlock()

		if r != nil {
			C.fetch_report(r, marshalString("changed"))
		}
	})
}
