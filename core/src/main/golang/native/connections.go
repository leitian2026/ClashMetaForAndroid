package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/connlist"
)

// CUSTOM (CUSTOM.md #8): active connections for the app's connections page.

//export queryCustomConnections
func queryCustomConnections(sortMode C.int, filter C.c_string) *C.char {
	if int(sortMode) == connlist.SortMux { // CUSTOM.md #20: the per-node socket summary, not the list
		return marshalJson(connlist.QueryMux())
	}

	return marshalJson(connlist.Query(int(sortMode), C.GoString(filter)))
}

//export closeCustomConnection
func closeCustomConnection(id C.c_string) C.int {
	if connlist.Close(C.GoString(id)) {
		return 1
	}

	return 0
}
