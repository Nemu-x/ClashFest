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
| every patch reverse-applies cleanly | series already applied, nothing to do |
| tree clean, every patch applies | apply the series in name order |
| anything else | fail and name the offending patch |

So a core bump that moves the patched lines stops the build at this task instead of shipping an
unpatched `libclash.so`. Local edits in the files a patch touches also stop it; stash them or
`git -C core/src/foss/golang/clash checkout -- <file>`. Other files in the submodule may be
modified freely: CI and the F-Droid recipe overwrite the embedded Root CA bundle before building.

`:core:revertCorePatches` undoes the series and runs on `clean`. The submodule therefore shows as
modified while a build tree is set up; never commit the applied patches into the submodule.

Tests that ship inside a patch run with the Go test task (`go test github.com/metacubex/mihomo/component/tls`
from the `cfa` module), against the patched tree.

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
