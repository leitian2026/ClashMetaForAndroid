package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/config"
	"cfa/native/conftest"
	"cfa/native/connlist"
	"cfa/native/errlog"
	"cfa/native/ipdelay"
)

// CUSTOM (CUSTOM.md #8): active connections for the app's connections page.

//export queryCustomConnections
func queryCustomConnections(sortMode C.int, filter C.c_string) *C.char {
	if int(sortMode) == connlist.SortMux { // CUSTOM.md #20: the per-node socket summary, not the list
		return marshalJson(connlist.QueryMux())
	}

	if int(sortMode) == errlog.SortErrors { // CUSTOM.md #21: recent warnings and errors, not connections
		return marshalJson(errlog.Query())
	}

	if int(sortMode) == conftest.SortCheck { // CUSTOM.md #22 #25: runs the config check (ECH or DNS), the filter is its request
		return marshalJson(conftest.RunAny(C.GoString(filter)))
	}

	if int(sortMode) == config.SortValidate { // CUSTOM.md #27: check a profile folder (the filter), nothing is loaded
		return marshalJson(config.Validate(C.GoString(filter)))
	}

	if int(sortMode) == ipdelay.SortNodeInfo { // CUSTOM.md #23 #24: IPv6-only nodes, last test time, last network change
		return marshalJson(ipdelay.NodeInfo())
	}

	if int(sortMode) == ipdelay.SortRetest { // CUSTOM.md #24: the network changed / run a latency test
		return marshalJson(retest(C.GoString(filter)))
	}

	return marshalJson(connlist.Query(int(sortMode), C.GoString(filter)))
}

//export closeCustomConnection
func closeCustomConnection(id C.c_string) C.int {
	if C.GoString(id) == errlog.ClearID { // CUSTOM.md #21: forget the recorded errors
		errlog.Clear()
		return 1
	}

	if C.GoString(id) == conftest.FlushDNSID { // CUSTOM.md #26: clear the core's DNS cache
		if conftest.FlushDNS() {
			return 1
		}

		return 0
	}

	if C.GoString(id) == conftest.FlushFakeIPID { // CUSTOM.md #26: forget the fake-ip map and close every connection
		if conftest.FlushFakeIP() {
			connlist.Close(connlist.CloseAll)

			return 1
		}

		return 0
	}

	if connlist.Close(C.GoString(id)) {
		return 1
	}

	return 0
}
