package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/connlist"
)

// CUSTOM (CUSTOM.md #8): active connections for the app's connections page.

//export queryCustomConnections
func queryCustomConnections() *C.char {
	return marshalJson(connlist.Query())
}

//export closeCustomConnection
func closeCustomConnection(id C.c_string) C.int {
	if connlist.Close(C.GoString(id)) {
		return 1
	}

	return 0
}
