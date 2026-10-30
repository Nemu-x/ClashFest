package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/desync"
)

// The `type: desync` outbound (core patch 0021) for the Desync UI. Status
// comes through the bridge because Android serves no /proxies.

// desyncCapabilities returns desync.Capabilities as JSON: what the desync
// library implements on this device. Static for this library version.
//
//export desyncCapabilities
func desyncCapabilities() *C.char {
	defer guard("desyncCapabilities")()
	return marshalJson(desync.PlatformCapabilities())
}

// desyncPresets returns the strategy catalog usable on this device as JSON
// (desync.Catalog), options in the outbound's YAML keys.
//
//export desyncPresets
func desyncPresets() *C.char {
	defer guard("desyncPresets")()
	return marshalJson(desync.Presets())
}

// desyncServices returns the services a desync-only profile can route as
// JSON ([]desync.Service).
//
//export desyncServices
func desyncServices() *C.char {
	defer guard("desyncServices")()
	return marshalJson(desync.Services())
}

// queryDesyncStatus returns the desync proxies of the running configuration
// as JSON (desync.Status).
//
//export queryDesyncStatus
func queryDesyncStatus() *C.char {
	defer guard("queryDesyncStatus")()
	return marshalJson(desync.QueryStatus())
}

// generateDesyncOnlyConfig takes a JSON desync.Request and returns a JSON
// desync.Result: the engine-validated YAML or the reason there is none.
//
//export generateDesyncOnlyConfig
func generateDesyncOnlyConfig(request C.c_string) *C.char {
	defer guard("generateDesyncOnlyConfig")()
	return C.CString(desync.GenerateConfigJSON(C.GoString(request)))
}

// composeDesyncOverlay adds the desync routes of a JSON desync.Request to a
// subscription config and returns a JSON desync.Result: the engine-validated
// YAML or the reason there is none (desync/overlay.go).
//
//export composeDesyncOverlay
func composeDesyncOverlay(base, request C.c_string) *C.char {
	defer guard("composeDesyncOverlay")()
	return C.CString(desync.ComposeOverlayJSON(C.GoString(base), C.GoString(request)))
}
