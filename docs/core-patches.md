# Core patch series

The mihomo core stays an unmodified upstream checkout (`core/src/foss/golang/clash`, pinned to a
release tag). The few behaviours ClashFest needs on top of it live as a patch series in
`core/patches/mihomo/` and are applied to the submodule working tree at build time. There is no
ClashFest fork of mihomo, and there must not be one: a patch that stops being small enough to
rebase in minutes is a sign to upstream it or drop it.

## How it is applied

`:core:applyCorePatches` runs before every Go build (`externalGolangBuild*`) and before the Go
test task. It treats the submodule tree as a state machine:

| Tree state | Action |
|---|---|
| the series reverse-applies, last patch first | series already applied, nothing to do |
| tree clean, the series applies in name order | apply the series in name order |
| anything else | fail and name the offending patch |

The series is a stack: a patch may build on lines an earlier one changed (0004 on 0001), so the
checks replay it in order on a scratch index (`GIT_INDEX_FILE`) rather than checking each patch
alone against the tree.

So a core bump that moves the patched lines stops the build at this task instead of shipping an
unpatched `libclash.so`. Local edits in the files a patch touches also stop it; stash them or
`git -C core/src/foss/golang/clash checkout -- <file>`. Other files in the submodule may be
modified freely: CI and the F-Droid recipe overwrite the embedded Root CA bundle before building.

`:core:revertCorePatches` undoes the series and runs on `clean`. The submodule therefore shows as
modified while a build tree is set up; never commit the applied patches into the submodule.

Tests that ship inside a patch run with the Go test task (`:core:goTestNativeSnapshot`, which runs
`go test` on `component/tls`, `component/ca`, `component/sniffer`, `dns`, `tunnel`,
`transport/openvpn`, `transport/xdrive`, `transport/vless/muxcool`, `adapter`, `adapter/outbound`,
`adapter/outboundgroup`, `config` and `hub/route` from the `cfa` module, plus the desync library's
`presets` catalog), against the patched tree. The `config` tests use testify, so `cfa`'s `go.sum` carries its checksums although
`go.mod` does not require it; `go mod tidy` may drop them again, and
`go mod download github.com/stretchr/testify` in `core/src/main/golang` restores them. Engine-oracle tests for
the config surface a patch adds live in `cfa/native/snapshot` (`core_patch_oracle_test.go`).

Optional capabilities a patch adds are advertised to Kotlin through `Clash.coreFeatures()`
(`cfa/native/features`). Each entry there imports the package that implements it, so building
without the patch fails instead of advertising a feature that is not compiled in.

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

### 0001 REALITY: configurable client version

mihomo sends the constant `1.8.2` as the client version in the REALITY session id. Xray
servers configured with `minClientVer` reject it and silently fall back to the cover site. The
patch turns the constant into `tlsC.RealityClientVersion`, default = the Xray release the core
was last validated against. Only `minClientVer` / `maxClientVer` depend on this value.

Lab (Xray 24.12.31, 25.7.25, 26.4.13, 26.9.9 in Docker): servers with `minClientVer: 25.1.1`
fail on stock, pass with the patch; servers without a bound are unaffected.

Upstream: MetaCubeX considers this out of scope. Delete when mihomo makes the version
configurable or tracks Xray releases.

### 0002 REALITY: adaptive X25519MLKEM768

Xray generations disagree about the post-quantum key share: 24.x servers silently drop a
hybrid ClientHello (5 s timeout), 26.9.8+ servers reject a classic one, 25.x and 26.4 accept
both, and a server never tells which it is. The patch adds `tlsC.SetRealityMLKEMPolicy`
(Auto / On / Off) and, in Auto, remembers the outcome per server address and public key:
classic first, flip after one failure, pin after one success, re-flip after three failures of a
pinned verdict (servers get upgraded). A per-proxy `support-x25519mlkem768: true` still forces
the hybrid share.

Only the chrome specs in `metacubex/utls` 1.8.8 carry the hybrid share (`HelloChrome_131`,
`HelloChrome_133`); firefox, safari, ios and edge cannot offer it. When a hybrid handshake is
wanted and the configured fingerprint cannot produce one, the patch uses chrome for that
handshake, because the alternative is a node that can never connect to a 26.9.8+ server.

Lab: stock core fails `26.9.9 classic` and `24.12.31 hybrid`; with the patch in Auto every
server passes, the one wrong guess against 26.9.9 costs ~200 ms (the classic attempt fails
fast, the retry succeeds in the same request). XHTTP + REALITY goes through the same
handshake and behaves the same. A real 32-node subscription (tcp+vision and xhttp, chrome and
firefox fingerprints, older Xray) passes 32/32 on both the stock and the patched core.

Upstream: mihomo PR #2983 proposed always sending the hybrid share and was closed. Delete when
mihomo negotiates this itself.

### 0003 VLESS over XDrive (client)

Client side of the XDrive transport from Citrus (getmikan/Citrus PR #139, commit 8874a50c): VLESS
streams are carried through files in a remote storage folder (Google Drive or a generic HTTP
storage described by a `template`), multiplexed with Mux.Cool. Config surface: `network: xdrive`
plus `xdrive-opts` (`service`, `remote-folder`, `secrets`, `template`, `fronting`, timing knobs).
XDrive requires VLESS `encryption` (not `none`), rejects `flow`, outer TLS security modes
(REALITY / ShadowTLS / ...), `smux`, and any UDP packet encoding other than `xudp`; all of it fails
at config validation, not at dial time.

Brought: `transport/xdrive` (MPL-2.0 code adapted from Xray-core, SPDX headers and `LICENSE`
kept verbatim), `transport/vless/muxcool`, `adapter/outbound/vless_xdrive.go` and the hooks in
`adapter/outbound/vless.go` / `adapter/parser.go`, with their unit tests. Left out: the
inbound/listener side. Adapted to vanilla: TLS comes from `transport/vmess` (Citrus moved it to
`transport/v2ray`), and the go1.21 built-in `min` is replaced because mihomo's `go.mod` is still
`go 1.20`. No new module requirements.

`service: local` (a folder on the device, meant for tests) is defused by the Android build:
`xdrive.AllowLocalService` is `runtime.GOOS != "android"`, and while it is off such a node still
passes config validation (one bad node must not fail a whole subscription, and the engine
validates a fetched subscription before the app's hardener runs), but its storage refuses every
operation. Building the proxy creates no folder or file, and every TCP or UDP dial through it fails
with `xdrive service local (a folder on the device) is disabled in this build`. Because the check
sits in the storage the proxy is built with, it covers every source of proxies, including remote
and file proxy-providers (`type: http` / `file`) that the engine fetches at runtime and that never
pass through the app's hardener. Host builds (the Go tests) keep local storage on; the oracle tests
switch it off and check that the config is accepted, that dials fail with that error, and that the
`remote-folder` is never created (`core_patch_xdrive_local_oracle_test.go`). On top of that,
ClashFest's `YamlHardener` turns such nodes (in `proxies:` and inline `proxy-providers` payloads,
under any key spelling the decoder accepts) into REJECT and drops `xdrive-config` listeners in
Strict and Compat, so a node from the subscription itself shows up as disabled rather than as a
proxy that fails on every dial.

Delete when mihomo ships XDrive itself, or when ClashFest stops advertising `xdrive` in
`coreFeatures` (the bridge's `cfa/native/features` imports this package, so dropping the patch
breaks the build until the feature is removed there too).

### 0004 REALITY: per-proxy client version

`reality-opts.client-version: "26.9.9"` overrides the global client version from 0001 for that
proxy only; empty keeps the global one, which is still read at handshake time so the app's
`setRealityPolicy` reaches every proxy without an override. A malformed value (anything but
`x.y.z` with parts 0..255) fails config validation. The idea is from Citrus commit 9c9b0856, the
implementation is ours on top of 0001/0002: no ML-KEM-by-default (`mldsa65-verify` of the same
commit is 0006), and the adaptive ML-KEM policy from 0002 is untouched.

Upstream: same status as 0001. Delete together with 0001.

### 0005 Proxy groups of type chain

`type: chain` lists the hops of a cascade in order (`proxies: [entry, ..., exit]`). Ported from Citrus
commit 8d97eb8c. At load time
`config/chain.go` (`expandChains`, called at the top of `parseProxies`) wires every hop after
the first to the hop before it with `dialer-proxy`: a proxy itself, every member of a group and
every provider in its `use`, the first hop of a nested chain. Configs without a chain group are
not touched. The config is rejected on a missing or repeated hop, a loop (also one the wiring
would create), a `dialer-proxy` (or provider `override.dialer-proxy`) already set to something
else, two chains that disagree, a built-in or an `include-all` group after the first hop.

At runtime `adapter/outboundgroup/chain.go` dials the last hop. Its health check tests the whole
chain and, when that fails, tests hops 1..k to name the first broken hop (a provider health check
can take a `Checker` for this, `adapter/provider/healthcheck.go`). `/proxies` shows `hops`
(`ChainHop`: name, position, alive), `chainAlive`, `failedHop`, `failedHopPosition` and
`chainError`; the group type is `Chain` (`constant.ChainGroup`), which Kotlin decodes as
`Proxy.Type.Chain`.

Left out from Citrus: the Prometheus metrics, the `chain.health` event-bus event, the relay
error message rewording and the listener changes of the same commit. Citrus' `config/chain_test.go`
comes along minus the metrics and event assertions (it runs real SOCKS5 / Shadowsocks inbounds on
loopback). Engine-oracle tests in `cfa/native/snapshot`: `core_patch_chain_oracle_test.go`
(3-hop chain, group and provider hops wired, loops and conflicts rejected) and
`chain_user_layer_oracle_test.go`.

Interaction with the user layer: ClashFest's proxy-chain edit (`UserLayer.proxyChain`) also writes
`dialer-proxy`. If it sets another dialer on a proxy that a subscription chain must wire, the
composed config is rejected by the engine, and the update-path gate (`MergeEngineVerdict.gate`)
writes the clean subscription and logs the engine error, which names the chain, the proxy and
both dialers (`ChainUserLayerGateTest` + the Go fixture oracle). The user sees only the generic
"update engine warning" flag, not which edit was dropped.

Upstream: mihomo removed `relay` groups in favour of `dialer-proxy`; nothing equivalent exists.
Delete when mihomo ships a chain group, or when ClashFest stops advertising `chain` in
`coreFeatures` (`cfa/native/features` references `outboundgroup.Chain`).

### 0006 REALITY: mldsa65-verify

`reality-opts.mldsa65-verify` is the client side of Xray's `mldsa65Verify` (share link `pqv`): an
Xray server with `mldsa65Seed` signs HMAC-SHA512(authKey, public key || ClientHello || ServerHello)
with ML-DSA-65 and puts the signature in the first extension of its temporary certificate. With the
key set, a certificate without a valid signature fails REALITY authentication. Without the patch the
key is accepted and ignored, so the client believes it pins the server while it does not. A malformed
key (not unpadded base64url, not 1952 bytes) fails config validation.

From Citrus commit 9c9b0856, without the X25519MLKEM768-by-default part of that commit. Brings one
module, `github.com/metacubex/mldsa` v0.1.1 (pure Go, BSD, ~2.1k lines, a backport of Go's
`crypto/mldsa`, needs only `golang.org/x/crypto` which the core already has); it is in `go.mod` /
`go.sum` of both `cfa` and `foss`. The submodule's own `go.mod` is not patched: the `replace` makes the
main modules' requirement the one that counts. `component/tls/reality_mldsa65_test.go` checks the
key and signature against circl's vectors (what Xray uses) and the certificate check with and without
the key. Advertised as `mldsa65` in `coreFeatures`.

Upstream: mihomo has no ML-DSA verification for REALITY. Delete when it does.

### 0007 TLS: Xray client-fingerprint names

`client-fingerprint` accepts the names Xray-core uses (`hellofirefox_148`, `hellochrome_131`,
`helloios_14`, `randomizednoalpn`, ..., case-insensitive; `unsafe` and `hellogolang` mean no uTLS).
Vanilla logs an unknown name and dials with Go's own ClientHello, the one hello a censor can single
out. The names map to the nearest parrot of `metacubex/utls` v1.8.8 (the table of Citrus commit
9c9b0856, which was written for that version): `hellofirefox_148` is Firefox 120 and
`hellosafari_26_3` is Safari 16.0 until utls ships those parrots. Aliases only, no utls fork (Citrus
main moved to the getmikan/utls fork for real Firefox 148 / Safari 26.3 parrots; not taken). The
table lives in `component/tls/utls_xray_names.go` and never shadows a mihomo name. No
`coreFeatures` token: there is no exported symbol to tie it to, and vanilla accepts the config.

Upstream: none. Delete when mihomo accepts Xray's names.

### 0008 TLS: comma-separated pins and names

`fingerprint` (certificate pin) and `name-cert-verify` take comma-separated lists, any match is
enough, like Xray's `pinnedPeerCertSha256` and `verifyPeerCertByName`. Vanilla rejects a list at
validation. Cherry-picked from Citrus commit a93079f5 (the files were identical to v1.19.32), with
its `component/ca/list_test.go`. Advertised as `pin-list`.

Upstream: none. Delete when mihomo accepts lists.

### 0009 ECH: Xray echConfigList forms

`ech-opts.config` also takes the `ECH CONFIGS` PEM of `xray tls ech --pem` and a base64 list wrapped
over several lines; `ech-opts.query-server` names the DNS server (nameserver syntax, `h2c://` taken as
`https://`) to fetch the HTTPS record from, with a cache per proxy, instead of the proxy-server
resolver. Vanilla rejects the PEM and ignores `query-server`. Citrus commit c6fabc31, file
copied whole (identical base), with `adapter/outbound/ech_test.go` (loopback DoH / UDP servers only).
Advertised as `ech-xray`.

Upstream: none. Delete when mihomo reads these forms.

### 0010 OpenVPN: server identity and tls-version-min

`verify-x509-name` / `verify-x509-type` (`subject`, the default, `name`, `name-prefix`) and
`verify-server-name` check the server certificate like OpenVPN's `--verify-x509-name` and an HTTPS
client; `tls-version-min` (`1.2` / `1.3`) bounds the control channel. Vanilla ignores all four, so a
`.ovpn` that pins the server name is accepted while any certificate of the same CA is trusted: another
client of the same VPN can impersonate the server. A proxy without either check logs a warning. From
Citrus commits dff9504c and 23772468 (identical base); the tests come from Citrus with a CA added to
the config-error cases (without it they failed for the missing CA, not for the value) plus a
`tls-version-min` case. This is the largest of the small patches (207 lines of code, 160 of tests);
it is kept because ignoring the identity keys is a silent security downgrade.

Upstream: none. Delete when mihomo verifies the server identity.

### 0011 QUIC: disable-mtu-discovery for hysteria2 and masque

Citrus commit 993e6429, 10 lines: the option already exists on tuic / shadowquic upstream. hysteria2
applies it in the dial callback because sing-quic overwrites the field per OS.

Upstream: none for these two types. Delete when mihomo adds it.

### 0012 tcp-connect-timeout

Top-level `tcp-connect-timeout` (milliseconds, 100..600000, default 5000 = mihomo's constant) is the
budget of a new tunnel TCP connection, handshake and retries included; slow chains and lossy mobile
links need more. Citrus PR #29 (through 0906f57e) minus its controller `PATCH /configs` support.

Upstream: none. Delete when mihomo makes the dial budget configurable.

### 0013 sniffer.skip-threshold

How many failed sniffs a destination may collect before it is not sniffed for 10 minutes (was the
constant 5; `-1` never skips, max 254). Citrus commit 9710dee3, adapted to vanilla's dispatcher, with
its test.

Upstream: none. Delete when mihomo makes it configurable.

### 0014 dns.serve-expired

`dns.serve-expired` (default `true`), `serve-expired-ttl` (seconds, 0 = no limit, at most 30 days) and
`serve-expired-client-timeout` (milliseconds, at most the DNS query timeout): `false` resolves an
expired entry before answering; a TTL stops serving entries expired longer ago; a client timeout waits
that long for a fresh answer and answers stale only when none comes (RFC 8767, unbound style). The
defaults are mihomo's behaviour, and the zero value of `dns.Config` / `Resolver` keeps it too, so the
other users of `dns.NewResolver` (ECH query resolver, WireGuard, Tailscale) are unchanged; the
vanilla code path runs untouched unless an option is set. Ours on the idea of Citrus PR #31
(`DisableServeExpired` instead of Citrus' `ServeExpired` for that reason), with our own test.

Upstream: none. Delete when mihomo has an equivalent.

### 0015-0021 Desync (from SlothClash)

`github.com/Nemu-x/desync` reshapes how the client's own first TLS ClientHello leaves its socket
(TLS record and TCP write splits, low-TTL fakes, disorder, out-of-band byte, a low-TTL UDP burst). The
seven patches are SlothClash's, copied byte for byte and renumbered: source Nemu-x/SlothClash branch
`feat/desync-outbound-patch` (PR #116) at commit `675b36fb`, `core/patches/mihomo/`.

| ClashFest | SlothClash | What |
|---|---|---|
| 0015 | 0017 `direct-desync` | optional `desync` mapping on a named `direct` outbound |
| 0016 | 0018 `probe-error-details` | URL-test errors kept in controller responses and logged |
| 0017 | 0019 `desync-dns-udp` | `dns-fallback` to the profile resolver, UDP burst with counters |
| 0018 | 0020 `desync-auto-diagnostics` | authenticated UDP probe, activity counters (`hub/route`) |
| 0019 | 0021 `desync-stable-udp-binding` | `udp.stable-binding` for Discord voice |
| 0020 | 0022 `desync-profile-reserve` | reserve probes, `reject-quic` |
| 0021 | 0023 `desync-outbound` | `type: desync`, the first-class outbound |

The comments inside the code still say "SlothClash core patch 00NN" with SlothClash's numbers; the
table maps them. The controller endpoints 0016/0018/0020 add to `hub/route` are compiled in but not
reachable on Android (the external controller is stripped, `native/config/process.go`); their tests run
in `goTestNativeSnapshot`. Status for the app comes through the bridge instead (below).

`type: desync` is a DIRECT connection whose first TLS ClientHello is transformed; keys, units and the
`desync` object of its JSON are Citrus' `type: desync` (getmikan/Citrus#172), plus SlothClash's
`dns-fallback`, `reject-quic` and `udp.stable-binding`. What a device can do is decided by the library
at config validation: an option the platform lacks fails the proxy instead of falling back to plain
DIRECT. Kotlin decodes the adapter type as `Proxy.Type.Desync`; the feature token is `desync`
(`cfa/native/features` references `outbound.NewDesync` and `C.Desync`).

**Library version.** The patches and `core/src/main/golang/go.mod` pin desync main at 673cbef
(`v0.2.1-0.20261029232908-673cbefddb80`, Nemu-x/desync#11; it becomes the `v0.2.1` tag once that is
published). It carries the `presets` catalog the bridge serves (v0.2.0) and the Android fix: apps may
not read `SIOCOUTQNSD`, so `split-wait: sent`, `disorder` and `fake` read unsent bytes from `TCP_INFO`
(Linux 4.6+) and are decided per socket. `PlatformCapabilities()` lists them in `Conditional`; the
outbound (0021) accepts them and logs once per proxy that they are verified per connection, and a socket
without the field fails its connection before anything is sent. ClashFest builds with the version that
`core/src/main/golang/go.mod` requires (the `replace` of mihomo makes the main module's requirement
win). To bump:
edit that require line, then sync both modules with the `go list -mod=mod` commands of
docs/core-update.md (step 3), which also update `core/src/foss/golang/go.mod` / `go.sum`; never
`go mod tidy`. `Clash.desyncCapabilities().libraryVersion` reports the linked version at runtime.

**Bridge** (`cfa/native/desync`, exports in `native/desync.go`, Kotlin models in
`core/.../model/Desync.kt`):

| Kotlin | Go export | Returns |
|---|---|---|
| `Clash.desyncCapabilities()` | `desyncCapabilities` | `desync.PlatformCapabilities()` with `conditional`, plus `probed`: `desync.ProbeCapabilities` of a loopback connection, once per process |
| `Clash.desyncPresets()` | `desyncPresets` | the `presets` catalog usable here (filtered by the probe, else the platform report), options as the outbound's YAML keys |
| `Clash.desyncServices()` | `desyncServices` | services a desync-only profile can route (`native/desync/services.go`) |
| `Clash.queryDesyncStatus()`, `IClashManager.queryDesyncStatus()` | `queryDesyncStatus` | the `desync` object of every desync proxy of the running config |
| `Clash.generateDesyncOnlyConfig(request)` | `generateDesyncOnlyConfig` | a profile: one desync proxy per service, `MATCH,DIRECT`, engine-validated |

Upstream: none; mihomo has no desync outbound. Delete 0015-0020 when ClashFest generates only
`type: desync` and SlothClash drops the named `direct.desync` outbounds; delete 0021 when mihomo
carries an equivalent outbound (the bridge and `coreFeatures` then need the matching change).

### 0022 Desync: loop test under CMFA

`component/loopback` disables the loopback detector in CMFA builds, and ClashFest's Go tests compile
with the `cmfa` tag, so 0021's `TestDesyncRejectsLoop` (written for desktop) skips there. Test-only.
Delete when the test in 0021 handles CMFA itself.

## Citrus compatibility suite

Panels that build configs for Citrus (getmikan/Citrus, a mihomo fork) may ship keys vanilla mihomo
does not know. `cfa/native/snapshot/citrus_compat_test.go` runs Citrus' own client-side examples
through `ValidateBytes` offline and reports, per case, whether the engine accepts it, rejects it, or
accepts it while silently ignoring a key Citrus reads. It runs in `:core:goTestNativeSnapshot`; for
the per-case log:

```bash
cd core/src/main/golang
go test -tags foss,with_gvisor,cmfa ./native/snapshot/ -run TestCitrusCompat -v
```

Fixtures are in `cfa/native/snapshot/testdata/citrus/` (GPL-3.0 like ClashFest; every file names its
Citrus commit, source path and lines, and lists what ClashFest changed, such as placeholders replaced
or geo rules dropped because they would download geodata):

- `config-*.yaml`: the client-side sections of Citrus `docs/config.yaml`. Listeners, inbound users,
  limits, metrics, controller, `allow-lan`/`authentication`/`tls` are server or controller material
  and left out. `config-proxies.yaml` and `config-rules.yaml` are split by the test into one case per
  proxy / rule, so one gap cannot hide another.
- `citrus-*.yaml`: Citrus-only options that `docs/config.yaml` shows commented out, uncommented into a
  minimal profile; `citrus-xray-fingerprints.yaml` holds every Xray fingerprint name of Citrus
  `component/tls/utls.go`, `citrus-ech-xray.yaml` the ECH forms of commit c6fabc31.
- `site-*.yaml`: the YAML blocks of `site/src/content/docs/**/*.mdx` (RU and EN pages hold the same
  blocks, deduplicated; the metrics, user-limits and user-stats blocks are server-only and skipped).
- `interop-citrus-clients.yaml`: the client objects of the Citrus-client cases in
  `test/interop/interop_test.go`, with the generated keys replaced by dummies.

`manifest.json` gives each case an outcome and the Citrus-only keys it declares (`citrus-only`, paths
like `dns.serve-expired` or, for split proxies, relative to the proxy). A key counts as read when
validation fails after the value is replaced by a mapping or by a sequence; one the engine skips
passes both (`TestCitrusCompatProbe` checks the probe on a known and an unknown key). The
`client-fingerprint` of every proxy is also looked up, because vanilla falls back to Go's TLS on an
unknown name instead of failing. The test fails on a case without a manifest entry, on an `accept`
case that is rejected or has an ignored key not listed as `harmless`, and on an `unsupported` /
`deferred` case that no longer shows a gap (so the manifest cannot go stale).

### Refreshing from a newer Citrus commit

1. In a Citrus clone, `git diff <recorded>..<new> -- docs/config.yaml site/src/content/docs
   test/interop component/tls/utls.go` (the recorded commit is in `manifest.json` and in each header).
2. Copy changed client-side sections and newly documented client options into the fixtures, keeping
   the header format (commit, path and lines, ClashFest changes); new commented-out options get a
   `citrus-*.yaml` of their own. Never copy listeners, users or controller material.
3. Bump `citrus` in `manifest.json`, run the test, and give every new or changed case an outcome. A gap
   is ported as a small patch of its own only within the rules above; otherwise it is `deferred:`
   with the reason and size, or `unsupported:` when the owner decided against it.

### Outcomes at Citrus ecaa9466

145 cases: 140 `accept`, 4 `unsupported`, 1 `deferred`. Every `accept` holds with the series up to 0014;
with only 0001-0005 the suite fails on the 14 cases that 0006-0014 fixed and on the 52 Xray fingerprint
names.

| Fixture | Outcome | Notes |
|---|---|---|
| `config-general`, `-tun`, `-sniffer`, `-tunnels`, `-groups` | accept | |
| `config-dns` | accept | `serve-expired*` read (0014) |
| `config-proxies` (48 proxies) | accept | placeholders of the example replaced |
| `config-rules` (9 rules) | accept | |
| `config-rules#PROTOCOL,bittorrent,REJECT` | unsupported | PROTOCOL rule comes with a mihomo release |
| `site-chains`, `site-getting-started` | accept | chain group is 0005 |
| `citrus-reality-mldsa65-verify`, `interop-citrus-clients` (11) | accept | 0006 |
| `citrus-xray-fingerprints` (52 names) | accept | 0007 |
| `citrus-tls-pin-lists` | accept | 0008 |
| `citrus-ech-xray` (PEM, query-server) | accept | 0009 |
| `citrus-openvpn-identity` | accept | 0010 |
| `citrus-quic-disable-mtu-discovery` | accept | 0011 |
| `citrus-tcp-connect-timeout` | accept | 0012 |
| `citrus-sniffer-skip-threshold` | accept | 0013 |
| `citrus-dns-serve-expired` | accept | 0014 |
| `citrus-ws-early-data`, `citrus-xhttp-uplink-get` | accept | vanilla reads these keys |
| `citrus-ssh-host-key` | accept | `skip-host-key-check` ignored, harmless: vanilla never checks or warns without `host-key` |
| `citrus-dns-sdns` | unsupported | DNSCrypt `sdns://`, not wanted |
| `citrus-sniffer-bittorrent` | unsupported | feeds only the PROTOCOL rule |
| `citrus-log-format` | unsupported | server-only (stdout log format); ignored |
| `citrus-trusttunnel-timers` | deferred | needs Citrus' trusttunnel client rework (64970360, eb219944), ~110 lines in a transport ~3000 lines apart from vanilla; HTTP/2 ping / handshake timers only; ignored |

Silently ignored by this core: `skip-host-key-check` (harmless), `log-format`,
`log-timestamp-format`, trusttunnel `ping-interval` / `ping-timeout` / `handshake-timeout`.

Not testable by validation, so not in the suite, and not taken by decision: Citrus' X25519MLKEM768 on
by default, its XHTTP defaults (`x-padding-header`, XMUX `reuse-settings`) and the removed protocols
(vmess, SSR, hysteria v1, TUIC v4, stream ciphers, which ClashFest keeps).
