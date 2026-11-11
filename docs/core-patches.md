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
`transport/openvpn`, `transport/xdrive`, `transport/vless/muxcool`, `transport/xhttp`, `transport/gun`, `transport/jls`, `common/convert`,
`adapter`, `adapter/outbound`,
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

In `metacubex/utls` 1.8.8 only the chrome specs carried the hybrid share (`HelloChrome_131`,
`HelloChrome_133`); since v1.9.0-alpha.1 (mihomo v1.19.34) `firefox` (Firefox 148) and `safari`
(Safari 26.3) carry it too, while ios, edge and the older pinned parrots still cannot offer it. When a hybrid handshake is
wanted and the configured fingerprint cannot produce one, the patch uses chrome for that
handshake, because the alternative is a node that can never connect to a 26.9.8+ server. 0023
narrows that: firefox and safari now switch to their own current browser hello instead; chrome
stays the fallback only for the families that have none.

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
out. The names map to the nearest parrot of `metacubex/utls` (the table of Citrus commit 9c9b0856,
written for v1.8.8). Since mihomo v1.19.34 (utls v1.9.0-alpha.1) `hellofirefox_148` and
`hellosafari_26_3` map to the real Firefox 148 and Safari 26.3 parrots; before, they fell back to
Firefox 120 and Safari 16.0. Aliases only, no utls fork. The
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

**Library version.** The patches and `core/src/main/golang/go.mod` pin desync `v0.2.1` (commit
673cbef, Nemu-x/desync#11). It carries the `presets` catalog the bridge serves (v0.2.0) and the Android fix: apps may
not read `SIOCOUTQNSD`, so `split-wait: sent`, `disorder` and `fake` read unsent bytes from `TCP_INFO`
(Linux 4.6+) and are decided per socket. `PlatformCapabilities()` lists them in `Conditional`; the
outbound (0021) accepts them and notes that they are verified per connection, and a socket
without the field fails its connection before anything is sent. That note and the auto-ttl notes are
logged once per process for each distinct text (`sync.Map`, `TestDesyncNoteLogsOnce`): the config is
parsed on every load, validation and snapshot, and an automatic desync-only profile has dozens of
desync proxies. ClashFest builds with the version that
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

### 0023 REALITY: current Firefox and Safari hellos for the hybrid share

Xray 26.9.8+ forwards a REALITY ClientHello without X25519MLKEM768 to the cover site. Before this
patch, 0002 sent chrome's hello whenever a hybrid handshake was wanted and the configured
fingerprint had no hybrid share, so a proxy set to firefox or safari silently changed its
fingerprint. With the patch a firefox-family fingerprint switches to Firefox 148 and a safari-family
one to Safari 26.3 for such a handshake; ios, edge and the randomized families have no
post-quantum parrot and still borrow chrome's. Only the handshakes that need the hybrid share are
affected (forced, policy On, or Auto after the classic hello failed). The default client version
moves from `26.9.9` to `26.10.29` (a per-proxy `client-version` still wins; 0028 moves it again).

Since mihomo v1.19.34 `metacubex/utls` v1.9.0-alpha.1 ships both parrots, and `firefox` / `safari`
themselves are Firefox 148 / Safari 26.3, so they carry the share and never switch. The patch now
matters only for a proxy pinned to an older parrot of those families (`firefox120`, `safari16`,
`hellofirefox_105`, ...), and `component/tls/reality_pq_specs.go` just names the library's
`HelloFirefox_148` / `HelloSafari_26_3`. Up to v1.19.33 it carried the two parrots as
`ClientHelloSpec` data from `metacubex/utls` branch `v1.9.0-mod-meta` (`a6a364a`), applied through
`HelloCustom` + `ApplyPreset` with Firefox's X25519 share rewritten to the hybrid's key; the released
parrots are the same specs (checked field by field, Firefox's key reuse now done by utls itself).

Tests in the patch: both hellos pass the XTLS/REALITY key-share gate (one hybrid share, X25519 only
after it) and the X25519 key the server will use is the client's auth key; Firefox reuses the key,
Safari does not; hellos are fresh per handshake; firefox120, firefox105 and safari16 have no share and
switch to a hello that has it; chrome / ios / edge / randomized get no switch. The same patch is
SlothClash's 0024.

Upstream: MetaCubeX/mihomo#3319 (closed: no unreleased utls). The data half is gone with v1.19.34;
delete the rest with 0002.

### 0024 Transfer test: GET /proxies/{name}/transfer

The delay test (`adapter.URLTest`) is a HEAD over HTTP/1.1 and passes once the response headers
arrive. Some networks let a TLS connection through and then freeze it after the first tens of
kilobytes: Discord on Beeline completes the desync handshake and stops after ~16 KB, while `/delay`
stays green. The transfer test sends GET with ALPN `h2,http/1.1` and `Accept-Encoding: identity`,
verifies the certificate against the system roots, follows no redirect, and reads the body.

Query: `url` (https only), `timeout` (1-60000 ms), `expected` (status filter, as for `/delay`),
`min-bytes` (default 0), `max-bytes` (1-1048576, default 1048576), `stall` (100 ms to the timeout,
default 3000). It passes when `max-bytes` arrived, or the body ended after at least `min-bytes`, with
no pause longer than `stall`. Success: `{"delay","elapsed","bytes","status","protocol"}`. A stall or
a short body is 503 `{"message","bytes"}`, the deadline 504. Tests: `hub/route/proxy_transfer_test.go`
(HTTP/2, freeze after 16 KB, short body, status, deadline, parameters).

The same file is SlothClash's 0025 (the comment in `proxy_transfer.go` names that number); our
0016/0018/0020 equal its 0018/0020/0022, so it applies unchanged. A freeze tied to the server's IP
(Cloudflare) defeats every strategy; the test then reports "not working" instead of a false
"working".

Upstream: none. Delete when the clients no longer need transfer-level probes.

### 0025 Transfer test for in-process callers

ClashFest only. `route.ProxyTransfer` exposes 0024's test (any status accepted, returns the body
bytes) to the bridge, which serves no external controller. `cfa/native/desync/transfer.go` uses it
for automatic strategy selection: the candidate a route's fallback group picked by the delay test
is also fetched with the transfer test (the service's large page, `discord.com/login` and
`www.youtube.com/` with `min-bytes` 32768, else the group's test URL; `max-bytes` 65536, `stall`
2000 ms). If it freezes, the other live candidates are tried in group order and the first that
passes is pinned in the group; when none passes the route reports `working: false` and the Home
card says the service needs a VPN. Checks run in the background when the status is read and are
reused for 10 minutes (the groups' interval).

Upstream: none. Delete with 0024.

### 0026 TLS: Chrome 155 fingerprint (`chrome155`)

`client-fingerprint: chrome155` (and Xray's `hellochrome_155`, any case) sends the Chrome 155
ClientHello, the fingerprint Xray-core 26.11.9 made its default (XTLS/Xray-core#7135) and Citrus
v1.0.0-beta.3 calls `chrome`. Against Chrome 133 it adds a GREASE value and the ML-DSA schemes
0x0904/0x0905/0x0906 to `signature_algorithms` (advertised only, nothing verifies an ML-DSA
certificate) and the `trust_anchors` extension (0xca34) with 28 Chrome Root Store IDs. The profile is
the one of refraction-networking/utls 6ebdceb2 (also getmikan/utls a6dee38a); the patch carries it as
a `ClientHelloSpec` in `component/tls/chrome155.go`, applied through `HelloCustom` + `ApplyPreset`, so
`metacubex/utls` stays the release mihomo pins (v1.9.0-alpha.1 since v1.19.34, which still stops at
Chrome 133) with no fork or `replace`. Adapted from
Rerowros/veles `component/tls/chrome155.go` (GPL-3.0). `chrome133` (Citrus' pinned name) is added
as an alias of mihomo's Chrome 133.

**`chrome` stays Chrome 133** (mihomo's meaning). Citrus and Veles map `chrome` to Chrome 155; in
ClashFest the non-vanilla hello is sent only when the config names it, no layer of ours owns
`chrome`, and a `chrome` node therefore behaves exactly as on a stock core. A panel that wants Chrome
155 sends `chrome155`; the core advertises the name as the `chrome155` token in `coreFeatures`
(subscription User-Agent), tied to `tlsC.HelloChrome155`. Revisit when mihomo moves `chrome` itself.

utls cannot build a hello for an ID it does not know, so every uTLS client must be created through
`tlsC.UClient`: the patch routes REALITY (`GetRealityConn`) and JLS (`transport/jls`, the only direct
`utls.UClient` caller outside `component/tls`) through it. A config with a session cache gets
`PreferSkipResumptionOnNilExtension` on a copy (utls panics on a cached TLS 1.3 session for a
`HelloCustom` spec without `pre_shared_key`), and a spec utls refuses falls back to Chrome 133 with
a warning.

With REALITY, chrome155 carries the X25519MLKEM768 share itself, so 0002's adaptive choice works
unchanged: Auto removes the hybrid share for a classic attempt (`BuildRemovedX25519MLKEM768HandshakeState`
works on the custom spec) and keeps it after a flip; no chrome swap happens. 0023 does not touch it
(its post-quantum hellos are for firefox and safari only).

Tests: `chrome155_test.go` compares 20 generated hellos per name with the Chrome 155.0.8059.40
capture in `testdata/chrome155_clienthello.hex` (from utls, SHA-256 of the handshake
`235618e3…439a`): version, session id, cipher suites, extension set, every extension body except
GREASE values, key-share shape, GREASE ECH length family and total size. Also: `chrome` and
`chrome133` stay Chrome 133, a session cache does not panic, REALITY with policy On passes the
XTLS/REALITY key-share gate as Chrome 155 and with Off has no hybrid share, and no package outside
`component/tls` calls `utls.UClient` directly (AST walk).

Upstream: none. Delete when mihomo moves to a utls release that ships `HelloChrome_155` (then map
the names to it).

### 0027 REALITY: the ML-KEM memo learns only from answers

0002's memo counted every failed handshake as a refusal. A timeout, an EOF or a cancelled request
(a phone changing networks, a health check cut short) then flipped a server to the other key share,
which it may not accept. Xray of either generation does not fail a hello it does not accept: it
relays the client to its cover site, whose certificate then fails REALITY authentication. The patch
classifies each handshake (`realityClassify`, adapted from Rerowros/veles, GPL-3.0, which itself
adapted 0002):

| Outcome | When | Memo |
|---|---|---|
| OK | authenticated | pin the variant (as before) |
| Refused | completed without REALITY authentication, or failed after a ServerHello (x509, alert) | count towards a flip (as before) |
| Silent | context cancelled or expired, timeout, EOF, closed socket, or any failure before a ServerHello | no flip, no unpin; counted |
| None | failed after authentication | ignored |

After 3 silent handshakes in a row (`realityMemoFlipAfter`) the next attempt probes the other key
share once: its success pins it, its failure is a stale attempt and changes nothing. That still finds
a server that drops the wrong hello instead of relaying it (the 24.x behaviour seen in the 0002
lab), which a rule of "only a relayed certificate counts" never would.

Compared with SlothClash #136 (same problem, merged 2026-11-02): SlothClash moves the memo only when
a certificate chain arrived and failed REALITY verification, and ignores everything else, including
silence; its own known limitation is a server that closes instead of relaying, which Auto then never
learns. 0027 keeps an equally strict "refused" signal (relay, then a certificate that is not
REALITY's, or an answer after a ServerHello) and adds the bounded probe for the silent case.
SlothClash also stopped swapping a fingerprint without the hybrid share for chrome; ClashFest keeps
0002's swap (a node that can never reach a 26.9.8+ server is worse than a borrowed hello), but now
compares the whole hello ID: `chrome120` and the other old chrome parrots share the `Chrome` client
name with `chrome`, so before 0027 they skipped the swap and sent a classic hello while the memo
believed it offered ML-KEM. The same rule covers `random`: its per-process pick may be ios, which has
no hybrid share and borrows chrome's.

`realityReportMLKEM(ok bool)` stays as a wrapper (OK / Refused), so 0002's tests are unchanged.
`reality_mlkem_outcome_test.go` (from Veles' tests): the classification table, silence keeps a
pinned and an unpinned verdict, one probe after three silent handshakes, a failed probe keeps the
pin, a successful probe pins, stale silence is ignored, answers reset the silent count, and loopback
handshakes against a server that closes and one that never answers until the deadline (memo
unchanged), and against a plain TLS site (refused, flip) after which the next hello passes the Xray
key-share gate for chrome, chrome155 (still Chrome 155), firefox, safari, edge, ios and chrome120.

Upstream: same as 0002. Delete with 0002.

### 0028 REALITY: default client version 26.11.9

`tlsC.RealityClientVersion` defaults to `26.11.9` (was `26.10.29` since 0023), the Xray release the
series was last checked against and the version Citrus v1.0.0-beta.3 reports. Only servers with
`minClientVer` / `maxClientVer` look at it: a `minClientVer: 26.11.9` server now passes, a
`maxClientVer` below 26.11.9 refuses (as it does any newer client). The setting in **Settings →
Network** and a per-proxy `reality-opts.client-version` (0004) still win. The bridge now returns to
the core default when the setting is cleared (`native/reality.ClientVersion`); before, clearing it
kept the last value set in that process.

Upstream: same as 0001. Delete with 0001.

Lab for 0026-0028 (2026-11-11, Docker, plain mihomo binaries for linux/amd64, policy Auto; three
requests per proxy through VLESS + REALITY + vision; a local TLS 1.3 site as the REALITY target):

| Server (Xray) | chrome, chrome155, hellochrome_155, firefox, safari, ios, chrome120 | chrome155 + `support-x25519mlkem768` |
|---|---|---|
| 26.11.9, and 26.11.9 with `minClientVer: 26.11.9` | patched: all OK on the first request; stock: all fail | patched OK, stock fails |
| 25.7.25 | patched: all OK; stock: OK except chrome155 / hellochrome_155 (unknown names, no uTLS) | patched OK, stock fails |
| 24.12.31 | patched: all OK; stock: OK except chrome155 / hellochrome_155 | both fail (forced hybrid; the server relays it to the cover site) |

Citrus `test/interop` (`TestMihomoClientCitrusServer`, Citrus 22da58f1 as server) with the patched core:
30 of 31 pass; the negative control `vless-reality-require-mlkem-client-classic` connects, because
Auto flips to the hybrid share after the refused classic hello (the stock core passes all 31). A
ClashFest-only set of 25 more cases (the fingerprints above in Auto against a Citrus server with and
without `require-x25519mlkem768`, forced chrome155, `min-client-ver: 26.11.9` passes and
`max-client-ver: 26.11.8` refuses, chrome155 over WebSocket and XHTTP TLS, XHTTP packet-up with the
data in a header or a cookie for 0029) passes 25/25 on the patched core and 9/25 on stock.

### 0029 XHTTP: default uplink data key

With `xhttp-opts.uplink-data-placement: header` or `cookie` and no `uplink-data-key`, vanilla sends
the packet-up payload under the names `-0`, `-1`, ..., which no Xray-compatible server reads, so
such a node never works. The patch falls back to Xray's defaults (`X-Data` for headers, `x_data` for
cookies) on both sides (`GetNormalizedUplinkDataKey`). A config that sets the key is unchanged.
SlothClash's 0028, byte for byte, with its `transport/xhttp/uplink_data_key_test.go`.

Upstream: none yet. Delete when mihomo defaults the key.

### 0030 XDrive: Mux.Cool and storage TLS fixes from Citrus

Later Citrus fixes to the code 0003 brought, as SlothClash took them into its xdrive patch
(834b9ab9): a stream's write deadline or a cancelled dial fails that stream only, not the Mux.Cool
session (Citrus 6f580ef9, a2cfd331); a session whose connection stalls on one frame for 30 s fails
(d447a87a); a session without streams closes after 16 s, as in Xray, instead of polling the storage
(94f62093); a test of the cancelled-dial path (b1835036); the storage HTTPS client verifies against
`component/ca` (custom certificates, the embedded bundle) instead of a bare `tls.Config`
(d3a24184). `transport/vless/muxcool/client.go` now equals Citrus `dev` (22da58f1) except for the
Go 1.20 `min`. Not taken from SlothClash's version: its refusal of unknown `template` keys (a
SlothClash-only change; Citrus ignores them, and an error there would fail the whole profile at
validation).

Upstream: with 0003. Delete with 0003.

### 0034 gRPC: frame reader without recursion

`transport/gun` read the next frame header by calling itself once per frame, so a peer that sends a
stream of empty frames grew the stack without bound until the process died (a fatal stack overflow,
which no `recover` catches), and it trusted the payload length of a frame beyond the gRPC message that
carries it. The reader is now a loop, and a payload length larger than its gRPC message (or than an
`int`) is `ErrInvalidLength`. Valid streams read exactly as before; the client and the server side of
gRPC share this reader. From Rerowros/veles (GPL-3.0), with its `transport/gun/gun_test.go` (200 000
empty frames under a 1 MB stack limit, oversized lengths, payloads split across reads).

Upstream: none yet. Delete when mihomo's reader loops.

### 0035 XHTTP: read and write deadlines that do not close an idle connection

Vanilla's XHTTP connection had one timer for both directions: `SetReadDeadline` and
`SetWriteDeadline` both reset it, and when it fired the whole connection was closed, even if nothing
was reading or writing. A multiplexer on top (`smux` with yamux sets a write deadline ahead of its
writes and leaves it in place) therefore saw idle sessions torn down. The connection now keeps one
deadline per direction, as `net.Conn` does: an operation started after its deadline fails with
`os.ErrDeadlineExceeded`, one still blocked when the deadline passes is ended by closing the connection
(the only way to unblock the HTTP body), and an idle connection stays open. `Close` runs once. From
Rerowros/veles (GPL-3.0), which took it from getmikan/Citrus `transport/xhttp/conn.go`; tests from
Veles' `transport/xhttp/conn_test.go`. Veles' parallel packet-up uploads (its P5/P8) are not part of
this patch.

Upstream: none yet. Delete when mihomo's XHTTP connection keeps per-direction deadlines.

### 0036 Share links: no credentials in the skip warning

When a `vless://` or `vmess://` line of a base64 subscription (`ConvertsV2Ray`, used for proxy-providers
and the app's import) cannot be converted, vanilla logs the whole line at warning level: the UUID, the
REALITY public key and short id, passwords in the query. The warning now names only the server and the
node name (`skip vless link to <host> (<name>): <reason>`). The proxies produced are unchanged. From
Rerowros/veles (GPL-3.0), with its `common/convert/redact_test.go`.

Upstream: none yet. Delete when mihomo stops logging the line.

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

### Outcomes at Citrus 22da58f1 (v1.0.0-beta.3 content)

163 cases: 156 `accept`, 4 `unsupported`, 3 `deferred` (with mihomo v1.19.34). Every `accept` holds with the series up to 0030.
Fixtures whose source did not change since ecaa9466 keep that commit in their header.

| Fixture | Outcome | Notes |
|---|---|---|
| `config-general` | accept | `geox-sha256` with the example's empty pins ignored, harmless: an empty pin is no pin |
| `config-tun`, `-sniffer`, `-tunnels`, `-groups` | accept | |
| `config-dns` | accept | `serve-expired*` read (0014) |
| `config-proxies` (49 proxies) | accept | placeholders of the example replaced; `vless-xdrive` is 0003 |
| `config-rules` (9 rules) | accept | |
| `config-rules#PROTOCOL,bittorrent,REJECT` | unsupported | PROTOCOL rule comes with a mihomo release |
| `site-chains`, `site-getting-started` | accept | chain group is 0005 |
| `citrus-reality-mldsa65-verify` | accept | 0006 |
| `interop-citrus-clients` (18) | accept | 0006 mldsa65; XHTTP over h3 (3 modes), XDrive local / template (0003), Citrus client vs sing-box (REALITY vision, Hysteria2) |
| `citrus-xray-fingerprints` (53 names) | accept | 0007; `hellochrome_155` is 0026 |
| `citrus-chrome-pinned` (`chrome155`, `chrome133`) | accept | 0026; `chrome` stays Chrome 133 in ClashFest |
| `citrus-reality-client-version` | accept | `client-version: 26.11.9` (0004) with `chrome155` (0026) |
| `citrus-tls-pin-lists` | accept | 0008 |
| `citrus-ech-xray` (PEM, query-server) | accept | 0009 |
| `citrus-openvpn-identity` | accept | 0010 |
| `citrus-quic-disable-mtu-discovery` | accept | 0011 |
| `citrus-tcp-connect-timeout` | accept | 0012 |
| `citrus-sniffer-skip-threshold` | accept | 0013 |
| `citrus-dns-serve-expired` | accept | 0014 |
| `citrus-desync`, `site-desync` | accept | 0021; checked on Windows and Linux (`split-wait: sent` is unsupported on macOS) |
| `citrus-xdrive-template` | accept | 0003; Citrus #168 refuses `template` / `local` in http proxy-providers, ClashFest refuses only `local`, at dial time on Android |
| `citrus-ws-early-data`, `citrus-xhttp-uplink-get` | accept | vanilla reads these keys |
| `citrus-ssh-host-key` | accept | `skip-host-key-check` ignored, harmless: vanilla never checks or warns without `host-key` |
| `citrus-dns-sdns` | unsupported | DNSCrypt `sdns://`, not wanted |
| `citrus-sniffer-bittorrent` | unsupported | feeds only the PROTOCOL rule |
| `citrus-log-format` | unsupported | server-only (stdout log format); ignored |
| `citrus-trusttunnel-timers` | deferred | needs Citrus' trusttunnel client rework (64970360, eb219944), ~110 lines in a transport ~3000 lines apart from vanilla; HTTP/2 ping / handshake timers only; ignored |
| `citrus-snell-v6` | accept | vanilla since mihomo v1.19.34, the same code as Citrus 22da58f1 `transport/snell`; the example's `psk: yourpsk` is replaced, v6 needs 12-255 bytes on both. Vanilla also accepts `mode: unsafe-raw` (no PSK, no encryption), which Citrus refuses; not in the Citrus docs, so not a case |
| `citrus-geox-sha256` | deferred | SHA-256 pins of remote geodata (Citrus #155/#156) are ignored; SlothClash 0027 (~670 lines with tests) ports them |
| `site-security` (`tls.ca-roots`, `tls.ca-denylist`) | deferred | Citrus #161; ignored, so the trust store stays system plus bundle, interception roots included if installed; SlothClash 0026 (~4.7k lines, mostly the Mozilla bundle) ports it |

Silently ignored by this core: `skip-host-key-check` (harmless), `log-format`,
`log-timestamp-format`, trusttunnel `ping-interval` / `ping-timeout` / `handshake-timeout`,
`geox-sha256`, `tls.ca-roots`, `tls.ca-denylist`.

Not testable by validation, so not in the suite, and not taken by decision: Citrus' X25519MLKEM768 on
by default, its XHTTP defaults (`x-padding-header`, XMUX `reuse-settings`), `chrome` meaning Chrome
155 (0026) and the removed protocols (vmess, SSR, hysteria v1, TUIC v4, stream ciphers, which
ClashFest keeps). Server-only items of beta.3 are not in the suite: `mux-option.max-streams`, grace
access, fallbacks, egress guard, PROXY protocol, `fallback-page`, the XDrive inbound.
