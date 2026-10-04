package main

//#include "bridge.h"
import "C"

import (
	"cfa/native/reality"

	tlsC "github.com/metacubex/mihomo/component/tls"
	"github.com/metacubex/mihomo/log"
)

// setRealityPolicy pushes the user-facing REALITY settings into the core:
// the Xray-style client version carried in the ClientHello (core patch 0001;
// empty or malformed keeps the core default) and the X25519MLKEM768 policy
// (core patch 0002: 0 auto, 1 always, 2 never). Process-global, like the age
// key: the service calls it right before loading a profile.
//
//export setRealityPolicy
func setRealityPolicy(version C.c_string, mlkem C.int) {
	defer guard("setRealityPolicy")()

	if v, ok := reality.ParseVersion(C.GoString(version)); ok {
		tlsC.RealityClientVersion = v
	}
	policy := tlsC.RealityMLKEMPolicy(mlkem)
	if policy < tlsC.RealityMLKEMAuto || policy > tlsC.RealityMLKEMOff {
		policy = tlsC.RealityMLKEMAuto
	}
	tlsC.SetRealityMLKEMPolicy(policy)

	log.Infoln("REALITY policy: client version %s, X25519MLKEM768 %s",
		reality.FormatVersion(tlsC.RealityClientVersion), reality.MLKEMPolicyName(int(policy)))
}
