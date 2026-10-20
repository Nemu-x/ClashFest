package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/reality"

	tlsC "github.com/metacubex/mihomo/component/tls"
	"github.com/metacubex/mihomo/log"
)

// setRealityPolicy pushes the user-facing REALITY settings into the core as defaults for
// proxies that set nothing themselves (core patch 0001): the Xray-style client version carried
// in the ClientHello (empty or malformed keeps Citrus' default) and the X25519MLKEM768 policy
// (0 auto, 1 always, 2 never). Citrus sends the hybrid key share by default, so auto and always
// both keep it; never drops it for servers older than Xray 25. Process-global, like the age key:
// the service calls it right before loading a profile.
//
//export setRealityPolicy
func setRealityPolicy(version C.c_string, mlkem C.int) {
	defer guard("setRealityPolicy")()

	tlsC.RealityClientVersionOverride = ""
	if v, ok := reality.ParseVersion(C.GoString(version)); ok {
		tlsC.RealityClientVersionOverride = reality.FormatVersion(v)
	}
	tlsC.RealityDisableX25519MLKEM768ByDefault = int(mlkem) == 2

	clientVersion := tlsC.RealityClientVersionOverride
	if clientVersion == "" {
		clientVersion = tlsC.RealityDefaultClientVersion
	}
	log.Infoln("REALITY policy: client version %s, X25519MLKEM768 %s",
		clientVersion, reality.MLKEMPolicyName(int(mlkem)))
}
