# Updating the mihomo core

The core is the `MetaCubeX/mihomo` git submodule at `core/src/foss/golang/clash`, pinned to a
release tag. Bumps ship in their own PR (see CONTRIBUTING). The steps below are the whole recipe;
the two Go modules are what makes it easy to get wrong.

## Layout

| Module | Path | Role |
|---|---|---|
| `foss` | `core/src/foss/golang` | The shipped build: `go build -buildmode=c-shared cfa/native` → `libclash.so`. `replace cfa => ../../main/golang`, `replace mihomo => ./clash` (the submodule). |
| `cfa` | `core/src/main/golang` | App-side Go code and the snapshot unit test (`go test ./native/snapshot/...`). `replace mihomo => ../../foss/golang/clash`. |

Gradle builds with `-mod=readonly`, so `go.mod` / `go.sum` of **both** modules must already contain
every dependency the new core needs. `go mod tidy` cannot do that: it ignores build tags, and the
core's Android dependencies sit behind `foss,with_gvisor,cmfa` with `GOOS=android`. Use
`go list -mod=mod -tags …` instead — it accepts the tags and adds exactly what the build needs.

## Recipe

```bash
git checkout -b chore/core-X.Y.Z

# 1. submodule → tag
git -C core/src/foss/golang/clash fetch --depth=1 origin tag vX.Y.Z
git -C core/src/foss/golang/clash checkout vX.Y.Z

# 2. bump the mihomo require line in both go.mod (cosmetic, replace wins, but keeps `go list` honest)
sed -i 's#metacubex/mihomo v<old>#metacubex/mihomo vX.Y.Z#' core/src/foss/golang/go.mod core/src/main/golang/go.mod

# 3. sync dependencies for the Android build — go list, NOT tidy
( cd core/src/foss/golang && GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -mod=mod -tags foss,with_gvisor,cmfa cfa/native >/dev/null )
( cd core/src/main/golang && GOOS=android GOARCH=arm64 CGO_ENABLED=1 go list -mod=mod -tags foss,with_gvisor,cmfa ./native/snapshot/... >/dev/null )

# 4. build + verify on a device
./gradlew assembleAlphaDebug
adb logcat | grep "Init core"      # gitVersion must show the NEW tag
```

CI compiles the core with the patched Go toolchain (`.github/scripts` / `.github/patch`, same as
upstream CMFA) and populates the embedded Root CA bundle before the build; nothing to do locally.

## After every bump

- **New outbound types.** The Kotlin side recognises proxy types by name
  (`core/src/main/java/.../core/model/Proxy.kt` and the picker's badge/colour tables in
  `ProfileAdapter`). Grep the new release's `adapter/outbound` for added types and teach the
  recogniser, otherwise the new nodes render as `Unknown`.
- **Bridge imports.** Our Go bridge imports a few mihomo internals directly
  (`native/config/configscript` uses the YAML library, `native/tunnel` uses `outboundgroup` and
  `provider`). A refactor upstream shows up as a compile error in `go list` above.
- **Snapshot test.** `goTestNativeSnapshot` runs before every Java compile; a shape change in the
  engine's JSON surfaces there first.

## Cache trap

Gradle tracks the submodule commit, `go.mod` / `go.sum` and the CA bundle as inputs of the Go
tasks, and CMake re-configures when the submodule `HEAD` changes, so a bump invalidates the
`.so` and the embedded `gitVersion`. If a stale core ever ships again anyway, the manual reset is
`rm -rf core/.cxx` + `./gradlew :core:clean`.
