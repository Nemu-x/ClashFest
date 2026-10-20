package tunnel

import "github.com/metacubex/mihomo/adapter/provider"

// Suspend is driven by ACTION_SCREEN_OFF / ACTION_SCREEN_ON. It only pauses the timed
// health checks (core patch 0005). Do not call the core's Tunnel.OnSuspend/OnRunning here:
// that stops the tunnel from accepting new connections while the screen is locked.
func Suspend(s bool) {
	provider.SetHealthChecksSuspended(s)
}
