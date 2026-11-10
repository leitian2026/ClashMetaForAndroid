package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/conftest"
	"cfa/native/connlist"
	"cfa/native/errlog"
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

	if int(sortMode) == conftest.SortCheck { // CUSTOM.md #22: runs the config check, the filter is its request
		return marshalJson(conftest.Run(C.GoString(filter)))
	}

	return marshalJson(connlist.Query(int(sortMode), C.GoString(filter)))
}

//export closeCustomConnection
func closeCustomConnection(id C.c_string) C.int {
	if C.GoString(id) == errlog.ClearID { // CUSTOM.md #21: forget the recorded errors
		errlog.Clear()
		return 1
	}

	if connlist.Close(C.GoString(id)) {
		return 1
	}

	return 0
}
