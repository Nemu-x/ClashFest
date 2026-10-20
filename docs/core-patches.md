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
`go test` on `component/tls`, `transport/xdrive`, `transport/vless/muxcool`, `adapter`
and `adapter/outbound` from the `cfa` module), against the patched tree. Engine-oracle tests for
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

Note: the engine accepts `service: local` (a folder on the device, meant for tests) like
upstream does; it can only create and delete XDrive session files under the given folder.
ClashFest's `YamlHardener` turns such nodes (in `proxies:` and inline `proxy-providers`
payloads, under any key spelling the decoder accepts) into REJECT and drops `xdrive-config`
listeners in Strict and Compat; Off leaves them alone. Remote and file proxy-providers
(`type: http` / `file`) are fetched or read by the engine at runtime and never pass through the
hardener, so a `service: local` node served that way is not neutralised.

Delete when mihomo ships XDrive itself, or when ClashFest stops advertising `xdrive` in
`coreFeatures` (the bridge's `cfa/native/features` imports this package, so dropping the patch
breaks the build until the feature is removed there too).

### 0004 REALITY: per-proxy client version

`reality-opts.client-version: "26.9.9"` overrides the global client version from 0001 for that
proxy only; empty keeps the global one, which is still read at handshake time so the app's
`setRealityPolicy` reaches every proxy without an override. A malformed value (anything but
`x.y.z` with parts 0..255) fails config validation. The idea is from Citrus commit 9c9b0856, the
implementation is ours on top of 0001/0002: no ML-KEM-by-default, no `mldsa65-verify`, and the
adaptive ML-KEM policy from 0002 is untouched.

Upstream: same status as 0001. Delete together with 0001.
