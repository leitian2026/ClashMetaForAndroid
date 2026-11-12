package main

//#include "bridge.h"
import "C"

import (
	"unsafe"

	"cfa/native/app"
	"cfa/native/ipdelay"
	"cfa/native/tunnel"
)

//export queryTunnelState
func queryTunnelState() *C.char {
	mode := tunnel.QueryMode()

	node, ips, more := ipdelay.CurrentSet() // CUSTOM: CUSTOM.md #7

	var ip, country string

	if len(ips) > 0 {
		ip, country = ips[0].IP, ips[0].Country
	}

	response := &struct {
		Mode string `json:"mode"`

		CurrentNode    string           `json:"currentNode,omitempty"`    // CUSTOM
		CurrentIP      string           `json:"currentIp,omitempty"`      // CUSTOM: the most recently used one
		CurrentCountry string           `json:"currentCountry,omitempty"` // CUSTOM
		CurrentIPs     []ipdelay.NodeIP `json:"currentIps,omitempty"`     // CUSTOM: every address in use (CUSTOM.md #7)
		CurrentMore    int              `json:"currentMore,omitempty"`    // CUSTOM: how many more were left out
	}{Mode: mode, CurrentNode: node, CurrentIP: ip, CurrentCountry: country, CurrentIPs: ips, CurrentMore: more}

	return marshalJson(response)
}

//export queryNow
func queryNow(upload, download *C.uint64_t) {
	up, down := tunnel.Now()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryTotal
func queryTotal(upload, download *C.uint64_t) {
	up, down := tunnel.Total()

	*upload = C.uint64_t(up)
	*download = C.uint64_t(down)
}

//export queryGroupNames
func queryGroupNames(excludeNotSelectable C.int) *C.char {
	return marshalJson(tunnel.QueryProxyGroupNames(excludeNotSelectable != 0))
}

//export queryGroup
func queryGroup(name C.c_string, sortMode C.c_string) *C.char {
	n := C.GoString(name)
	s := C.GoString(sortMode)

	mode := tunnel.Default

	switch s {
	case "Title":
		mode = tunnel.Title
	case "Delay":
		mode = tunnel.Delay
	}

	response := tunnel.QueryProxyGroup(n, mode, app.SubtitlePattern())

	if response == nil {
		return nil
	}

	return marshalJson(response)
}

//export healthCheck
func healthCheck(completable unsafe.Pointer, name C.c_string) {
	go func(name string) {
		tunnel.HealthCheck(name)
		ipdelay.TestGroup(name) // CUSTOM: per-IP delay, only on user-triggered test

		C.complete(completable, nil)
	}(C.GoString(name))
}

//export healthCheckAll
func healthCheckAll() {
	tunnel.HealthCheckAll()
}

//export patchSelector
func patchSelector(selector, name C.c_string) C.int {
	s := C.GoString(selector)
	n := C.GoString(name)

	if tunnel.PatchSelector(s, n) {
		return 1
	}

	return 0
}

//export queryProviders
func queryProviders() *C.char {
	return marshalJson(tunnel.QueryProviders())
}

//export updateProvider
func updateProvider(completable unsafe.Pointer, pType C.c_string, name C.c_string) {
	go func(pType, name string) {
		C.complete(completable, marshalString(tunnel.UpdateProvider(pType, name)))

		C.release_object(completable)
	}(C.GoString(pType), C.GoString(name))
}

//export suspend
func suspend(suspended C.int) {
	tunnel.Suspend(suspended != 0)
}
