// Package features lists the optional core capabilities this libclash.so was
// built with, for the subscription User-Agent: panels can then serve
// transports that vanilla mihomo would reject.
//
// Every entry is tied to the code that implements it at compile time, so a
// build without the matching core patch fails here instead of advertising a
// feature that is not there (see docs/core-patches.md).
package features

import (
	"strings"

	// "chain": proxy groups of type chain, core patch 0005.
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	// "xdrive": VLESS over XDrive, core patch 0003.
	"github.com/metacubex/mihomo/transport/xdrive"
)

var (
	_ *outboundgroup.Chain
	_ xdrive.Config
)

// List returns the capability tokens in a stable order.
func List() []string {
	return []string{"xdrive", "chain"}
}

// String is List joined with commas, the form the bridge hands to Kotlin.
func String() string {
	return strings.Join(List(), ",")
}
