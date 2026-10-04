# Publishing ClashFest on F-Droid

F-Droid does not accept APKs. Its build server clones this repository at a tag and builds the
app from source with its own toolchain. The build is
[reproducible](https://f-droid.org/docs/Reproducible_Builds/): F-Droid compares its unsigned
APK with the signed reference APK we attach to the GitHub release and, when they match,
publishes ours, so F-Droid and GitHub installs share one signing key. The recipe lives in the
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository as
`metadata/com.nemu.clashfest.clash.alpha.yml`; a reference copy is kept next to this file.

## What a store build changes

Two Gradle properties switch the GitHub-specific behaviour off; both default to the normal
release behaviour, so CI and local builds are unaffected:

| Property | Effect |
|---|---|
| `clashfest.selfUpdate=false` | No GitHub release check, no APK download. `BuildConfig.SELF_UPDATE` hides the About button and the header badge; the release manifest overlay `app/src/fdroid/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES` and the update receivers/activity. |
| `clashfest.bundleGeo=false` | The `downloadGeoFiles` task is skipped and nothing is bundled under `assets/`; mihomo fetches GeoIP / GeoSite / ASN from the trusted mirrors on first use. The bundled `GeoLite2-ASN.mmdb` carries MaxMind's non-free licence, which F-Droid would flag. |

Everything else is handled in the recipe:

- **Go toolchain.** trixie's `golang-go` (1.24) is older than the `go 1.25` directive of the
  bridge modules, and the trixie-backports `golang-go` depends on a `golang-1.26-go` that is
  not in backports. The `sudo` step therefore installs Debian's `golang-1.26-go` /
  `golang-1.26-src` 1.26.8-1 (forky) from snapshot.debian.org, checked against their
  sha256: a moving suite would change the compiler under a published version and break
  reproducibility. It then applies the two runtime patches from `.github/patch/` to GOROOT. Debian's
  `GOROOT/src` is a symlink into `/usr/share/go-*` and GNU patch does not follow symlinks, so
  the patches are applied to the resolved source tree. Each patch is skipped when it already
  reverse-applies: the fdroiddata CI runs all four build blocks in one container.
  `sudo` runs before the checkout, so the patches are fetched from the pinned commit.
- **Vendored artifacts.** `maven/` holds the kr328 Gradle plugin and kaidl as jars. F-Droid's
  scanner rejects committed binaries, so `prebuild` points the build at the JitPack builds of
  the same sources (the coordinates upstream Clash Meta For Android uses) and `scandelete`
  drops the directory.
- **Signing.** `signingConfig` is removed; F-Droid builds unsigned. `Binaries` points at our
  signed APK of the same build and `AllowedAPKSigningKeys` pins our certificate (SHA-256
  `91b980057c555ba85b0fb9068f7b5cdee0d39d3810a1c08c34929290e1905fc7`).
- **ABI split.** One build block per ABI. `prebuild` narrows the ABI list in
  `build.gradle.kts` (ndk, cmake and splits all use the same literal) to that ABI, so the Go
  plugin and CMake build only it, disables AGP splits and sets `versionCode` to `$$VERCODE$$`.
  `VercodeOperation` is `%c + 1..4` for armeabi-v7a < arm64-v8a < x86 < x86_64; the
  `patch * 1000` scheme keeps the low digits free, so they never collide with the next
  release. Without splits AGP drops the `-universal` suffix, so `output` is
  `clashfest-v*-alpha-release-unsigned.apk`.
- **Root CA bundle.** `init` copies Debian's bundle into the mihomo submodule, as
  `populate-ca-bundle.sh` does in CI.

## Reference APKs (reproducible builds)

`.github/workflows/fdroid-binaries.yml` builds every ABI block of the recipe in F-Droid's
`buildserver-trixie` image the way the fdroiddata CI does (`.github/scripts/fdroid-build.sh`),
signs the unsigned APK on the runner with the release key, checks with
`apksigcopier compare --unsigned` that the signature transplants onto the unsigned build (the
check F-Droid runs) and attaches `clashfest-v<version>-fdroid-<versionCode>.apk` to the release.
It runs on every `v*` tag; for an older release start it manually with the tag (and the source
commit, if F-Droid builds a different one).

`apksigner` must sign with `--alignment-preserved`; without it the entries are re-aligned and
the signature no longer fits F-Droid's build.

If the assets are missing or differ, F-Droid does not publish that version. Fix the cause and
re-run the workflow rather than editing the recipe's `Binaries` line.

## Local test with the F-Droid build server image

```sh
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata ~/fdroiddata
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
cp docs/fdroid/com.nemu.clashfest.clash.alpha.yml ~/fdroiddata/metadata/
docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie
# inside the container
fdroid readmeta
fdroid lint com.nemu.clashfest.clash.alpha
fdroid build com.nemu.clashfest.clash.alpha
```

Before the first submission verify the build in the container, then open a merge request to
fdroiddata titled `New App: com.nemu.clashfest.clash.alpha`. The MR pipeline runs
`fdroid rewritemeta` and fails on any formatting drift: no comments in the yml, no `Summary:`
(it comes from fastlane, see `make-summary-translatable`), lines wrapped at 80 columns, keys in
the order rewritemeta emits. Run `fdroid rewritemeta com.nemu.clashfest.clash.alpha` in the
container before pushing. The reference copy next to this file is the exact submitted file.

## Things reviewers will ask about

- The app sends `x-hwid` and device-model headers with every subscription request so operators
  can enforce device limits. They go only to the subscription URL the user configured. This is
  documented in `PRIVACY_POLICY.md`.
- Geo databases are downloaded at runtime from GitHub / jsDelivr mirrors; the ASN database is
  MaxMind GeoLite2 (non-free licence). Expect a `NonFreeNet` / `NonFreeAssets` discussion.
- F-Droid publishes our signature, so users can move between GitHub and F-Droid builds
  without reinstalling. F-Droid versionCodes are the GitHub ones plus the ABI offset, so the
  GitHub APK of the same version counts as a downgrade over an F-Droid install.
- Tags: every release commit is tagged `vX.Y.Z` on `feat/init-clashfest`; `UpdateCheckMode`
  ignores the inherited upstream `v2.x` tags.
