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

	// "ech-xray": ech-opts in the Xray forms (ECH CONFIGS PEM, query-server), core patch 0009.
	// "desync": outbounds of type desync (github.com/Nemu-x/desync), core patch 0021.
	"github.com/metacubex/mihomo/adapter/outbound"
	// "chain": proxy groups of type chain, core patch 0005.
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	// "pin-list": comma-separated fingerprint pins and name-cert-verify names, core patch 0008.
	"github.com/metacubex/mihomo/component/ca"
	// "mldsa65": reality-opts.mldsa65-verify, core patch 0006.
	tlsC "github.com/metacubex/mihomo/component/tls"
	// "desync": the adapter type the outbound registers, core patch 0021.
	C "github.com/metacubex/mihomo/constant"
	// "xdrive": VLESS over XDrive, core patch 0003.
	"github.com/metacubex/mihomo/transport/xdrive"
)

var (
	_ *outboundgroup.Chain
	_ xdrive.Config
	_ = tlsC.ParseRealityMldsa65Verify
	_ = ca.NewFingerprintNameVerifier
	_ = outbound.ECHOptions{QueryServer: ""}
	_ = outbound.NewDesync
	_ = C.Desync
)

// List returns the capability tokens in a stable order.
func List() []string {
	return []string{"xdrive", "chain", "mldsa65", "pin-list", "ech-xray", "desync"}
}

// String is List joined with commas, the form the bridge hands to Kotlin.
func String() string {
	return strings.Join(List(), ",")
}
