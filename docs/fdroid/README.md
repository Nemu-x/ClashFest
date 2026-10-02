# Publishing ClashFest on F-Droid

F-Droid does not accept APKs. Its build server clones this repository at a tag, builds the app
from source with its own toolchain and signs the result with F-Droid's key. The recipe lives in
the [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repository as
`metadata/com.nemu.clashfest.clash.alpha.yml`; a reference copy is kept next to this file.

## What a store build changes

Two Gradle properties switch the GitHub-specific behaviour off; both default to the normal
release behaviour, so CI and local builds are unaffected:

| Property | Effect |
|---|---|
| `clashfest.selfUpdate=false` | No GitHub release check, no APK download. `BuildConfig.SELF_UPDATE` hides the About button and the header badge; the release manifest overlay `app/src/fdroid/AndroidManifest.xml` removes `REQUEST_INSTALL_PACKAGES` and the update receivers/activity. |
| `clashfest.bundleGeo=false` | The `downloadGeoFiles` task is skipped and nothing is bundled under `assets/`; mihomo fetches GeoIP / GeoSite / ASN from the trusted mirrors on first use. The bundled `GeoLite2-ASN.mmdb` carries MaxMind's non-free licence, which F-Droid would flag. |

Everything else is handled in the recipe:

- **Go toolchain.** Debian's `golang-go` is older than the `go 1.25` directive of the bridge
  modules, so the `sudo` step installs the official go.dev tarball and applies the same two
  runtime patches as `.github/patch/`.
- **Vendored artifacts.** `maven/` holds the kr328 Gradle plugin and kaidl as jars. F-Droid's
  scanner rejects committed binaries, so `prebuild` points the build at the JitPack builds of
  the same sources (the coordinates upstream Clash Meta For Android uses) and `scandelete`
  drops the directory.
- **Signing and splits.** `signingConfig` is removed (F-Droid signs) and ABI splits are disabled
  so a single APK is produced; without splits AGP drops the `-universal` suffix, so `output`
  is `clashfest-v*-alpha-release-unsigned.apk`.
- **Root CA bundle.** `init` copies Debian's bundle into the mihomo submodule, as
  `populate-ca-bundle.sh` does in CI.

## Local test with the F-Droid build server image

```sh
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata ~/fdroiddata
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
cp docs/fdroid/com.nemu.clashfest.clash.alpha.yml ~/fdroiddata/metadata/
docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
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
- F-Droid's signature differs from the GitHub releases. Users switching between the two must
  uninstall first; say so in the release notes of the first F-Droid version.
- Tags: every release commit is tagged `vX.Y.Z` on `feat/init-clashfest`; `UpdateCheckMode`
  ignores the inherited upstream `v2.x` tags.
