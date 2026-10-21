# Core patch series

The core is [Citrus](https://github.com/getmikan/Citrus), Mikan's mihomo fork, checked out unmodified
at `core/src/foss/golang/clash` (pinned to `dev` commit `ca8204d95c32664303a00ba917a45df8254adb1f` (2026-10-22)). It keeps the module path
`github.com/metacubex/mihomo`, so the bridge imports stay as they are. Citrus' own `replace`
directives (utls, http, tls, smux forks) must be repeated in `core/src/main/golang/go.mod` and
`core/src/foss/golang/go.mod`: Go applies `replace` only in the main module. The subscription
User-Agent advertises the upstream mihomo release Citrus is based on (`v1.19.x` tags), never
Citrus' own `v1.0.0-beta.N` tags, because panels gate protocols on the mihomo version. The few behaviours ClashFest needs on top of it live as a patch series in
`core/patches/mihomo/` and are applied to the submodule working tree at build time. There is no
ClashFest fork of mihomo, and there must not be one: a patch that stops being small enough to
rebase in minutes is a sign to upstream it or drop it.

## How it is applied

`:core:applyCorePatches` runs before every Go build (`externalGolangBuild*`) and before the Go
test task. It treats the submodule tree as a state machine:

| Tree state | Action |
|---|---|
| every patch reverse-applies cleanly | series already applied, nothing to do |
| tree clean, every patch applies | apply the series in name order |
| anything else | fail and name the offending patch |

So a core bump that moves the patched lines stops the build at this task instead of shipping an
unpatched `libclash.so`. Local edits in the files a patch touches also stop it; stash them or
`git -C core/src/foss/golang/clash checkout -- <file>`. Other files in the submodule may be
modified freely: CI and the F-Droid recipe overwrite the embedded Root CA bundle before building.

`:core:revertCorePatches` undoes the series and runs on `clean`. The submodule therefore shows as
modified while a build tree is set up; never commit the applied patches into the submodule.

Tests that ship inside a patch run with the Go test task (`go test github.com/metacubex/mihomo/adapter/outbound github.com/metacubex/mihomo/adapter/provider`
from the `cfa` module), against the patched tree. The same task also runs
`github.com/metacubex/mihomo/transport/trusttunnel`: the pinned Citrus already includes
[getmikan/Citrus#54](https://github.com/getmikan/Citrus/pull/54), including its connection,
handshake timeout and HTTP/2 ping tests. Do not reapply the superseded TrustTunnel patches.

## Rules for a patch

- One concern per patch, numbered, with a header comment in the code explaining why it exists.
- Keep it configurable from the bridge where that is cheap: the Kotlin side sets package-level
  state at core start instead of baking policy into the patch.
- Ship a test with it when the change is logic rather than a constant.
- Record the upstream issue or PR in this file, and the condition under which the patch is deleted.

## When a core bump breaks a patch

1. The build fails in `applyCorePatches` naming the patch.
2. `git -C core/src/foss/golang/clash apply --3way core/patches/mihomo/<patch>` and fix the
   conflict; regenerate the patch with `git diff` from a tree that has only the earlier patches
   applied (stage them, then diff the working tree against the index).
3. If upstream fixed the problem, delete the patch and its entry below.
4. If the area was rewritten, keep the submodule on the previous tag until the patch is redone.
   The tag is pinned; nothing forces a bump.

## Current series

Citrus already carries what patches 0001-0004 used to add on top of stock mihomo: a configurable
REALITY client version (per proxy, `client-version`), the X25519MLKEM768 key share (on by default,
`support-x25519mlkem768: false` per proxy for old servers), and the TrustTunnel fixes (one TLS
connection per pooled client, bounded dial and handshake, HTTP/2 ping of silent sessions). The
remaining patches are app concerns; upstream them to Citrus when it makes sense there.

### 0001 REALITY: app-wide defaults

Citrus configures REALITY per proxy. The app's settings (client version, ML-KEM policy) are
app-wide, so the patch adds `tlsC.RealityClientVersionOverride` and
`tlsC.RealityDisableX25519MLKEM768ByDefault`, set by the bridge's `setRealityPolicy` before a
profile loads. They apply only to proxies that set nothing themselves: a subscription's own
`client-version` / `support-x25519mlkem768` still wins. The app's "Always" policy additionally
rewrites the nodes at compose time (`realityComposeRewrite`), which is what overrides a
subscription that turned the key share off.

Test: `adapter/outbound/reality_defaults_test.go`. Delete when Citrus grows global REALITY
defaults of its own.

### 0002 Health checks pause while the screen is off

Every proxy provider and url-test group runs its own health-check ticker. With several
subscriptions merged into one config the tickers kept dialing every node every few minutes with
the screen off, and the drain grew with the number of subscriptions. The bridge's `suspend`
(driven by `ACTION_SCREEN_OFF` / `ACTION_SCREEN_ON` in `SuspendModule`) was a no-op. The patch
adds `provider.SetHealthChecksSuspended`: while set, a ticker fire skips the check. On-demand
checks (a tap on a latency capsule, the network-switch probe) still run, and the timers resume on
the next tick after the screen comes back. The tunnel itself keeps accepting connections.

Test: `adapter/provider/healthcheck_suspend_test.go`. Not a server concern (no screen); keep it
as long as the app suspends on screen off.

### 0003 XDrive local storage on Android

Adapted from ClashFest commits 79dd99ef and 6162bfd7. Android builds accept a local-storage
XDrive node but replace its storage with an inert implementation: dialing fails and no device
files are accessed. The engine guard also covers nodes supplied by external proxy providers.
Cloud storage remains available. The patch includes storage-gate tests, and snapshot engine
oracles cover top-level, inline and file-provider nodes. Keep this platform-specific guard
until Citrus has an equivalent Android policy.
