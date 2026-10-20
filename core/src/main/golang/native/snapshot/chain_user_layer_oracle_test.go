package snapshot

import (
	"os"
	P "path"
	"strings"
	"testing"
)

// Engine half of the Kotlin ChainUserLayerGateTest: a subscription chain group
// (core patch 0005) and the user's proxy-chain edit both write dialer-proxy. The
// engine must accept the subscription, reject the composition in which the user
// set another dialer on a chain hop with exactly the message the Kotlin gate test
// reports (composed.engine-error.txt), and accept a user dialer on a non-hop.
func TestChainUserLayerFixtures(t *testing.T) {
	read := func(name string) []byte {
		data, err := os.ReadFile(P.Join("testdata", "chain", name))
		if err != nil {
			t.Skip("chain fixtures absent — run ChainUserLayerGateTest (Kotlin) first")
		}
		return data
	}
	subscription := read("subscription.yaml")
	composed := read("composed.yaml")
	unrelated := read("composed_unrelated.yaml")
	wantErr := strings.TrimSpace(string(read("composed.engine-error.txt")))

	if errMsg := ValidateBytes(subscription); errMsg != "" {
		t.Fatalf("engine rejected the subscription: %s", errMsg)
	}
	if errMsg := ValidateBytes(composed); errMsg != wantErr {
		t.Fatalf("composed config: engine said %q, the Kotlin gate test expects %q", errMsg, wantErr)
	}
	if errMsg := ValidateBytes(unrelated); errMsg != "" {
		t.Fatalf("engine rejected a user dialer on a proxy outside the chain: %s", errMsg)
	}
}
