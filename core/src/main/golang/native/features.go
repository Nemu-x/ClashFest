package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/features"
)

// coreFeatures returns the optional core capabilities compiled into this
// libclash.so as a comma-separated list (e.g. "xdrive"), for the subscription
// User-Agent. Each entry is tied to its core patch at compile time.
//
//export coreFeatures
func coreFeatures() *C.char {
	defer guard("coreFeatures")()

	return C.CString(features.String())
}
