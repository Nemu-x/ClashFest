Current session scope:
- Initial maintainer review of the ClashFest Android client codebase.
- Focus: Security defaults, VPN/runtime stability, Profile/import correctness, Performance, and Release readiness.

Files inspected:
- `AGENTS.md` (project contracts and rules)
- `service/src/main/java/com/github/kr328/clash/service/util/ProxyHardener.kt`
- `service/src/main/java/com/github/kr328/clash/service/util/YamlHardener.kt`
- `service/src/main/java/com/github/kr328/clash/service/util/GeoMirrors.kt`
- `service/src/main/java/com/github/kr328/clash/service/util/GeoUrlSanitizer.kt`
- `app/src/main/java/com/github/kr328/clash/ExternalControlActivity.kt`
- `service/src/main/java/com/github/kr328/clash/service/TunService.kt`
- `app/src/main/AndroidManifest.xml`
- `service/src/main/AndroidManifest.xml`

Main risks found:
- Exported components bypassing settings (`ExternalControlActivity`).
- Potential for VPN restart races causing silent disconnections.
- Unencrypted HTTP2 / HTTP3 proxy protocol leaks based on underlying Go routines.
- `ClashService` / `TunService` ForegroundService lifecycle on modern Android API variants.

Recommended next action:
- Discuss P1/P2 structural mitigations below and prioritize quick-win security patches.

[Severity: P1]
Area: vpn / stability
File(s): `service/src/main/java/com/github/kr328/clash/service/TunService.kt`
Problem: The VPN restart handoff waits up to 500ms using `StatusProvider.awaitServiceShutdown()`. If the previous instance's coroutine scope fails to teardown in time, the new TunService instance silently commits suicide (`stopSelf()`), leaving the VPN dead and the user unprotected.
Why it matters: Flaky reconnect behaviour breaks user trust and leaves traffic un-routed if the user assumed they are protected.
Suggested fix: Enhance the handoff synchronization. If the previous instance is stuck, forcefully terminate its scope or use a distinct notification rather than silently stopping.
Risk/tradeoff: Modifying core VpnService lifecycle could introduce new crashes on specific Android OEMs.
How to verify: Spam connect/disconnect button in UI and verify state always settles correctly.
Minimal patch plan: Increase timeout or implement a stronger inter-process lock on `TunService` start-up.

[Severity: P2]
Area: ui / security
File(s): `app/src/main/java/com/github/kr328/clash/ExternalControlActivity.kt`
Problem: While `ExternalControlActivity` requires intent confirmation for `install-config`, the `START_CLASH` and `STOP_CLASH` intents only log a toast if `allowExternalControl` is enabled (default on). The `notifyExternalStop()` function handles stops, but the channel notification might be missed by users.
Why it matters: A malicious app could repeatedly send intent triggers to stop the VPN covertly without adequate user intervention.
Suggested fix: Make `allowExternalControl` default to false, or prompt the user the first time an external intent attempts to stop the VPN.
Risk/tradeoff: Breaks automation tools like Tasker that users may rely on.
How to verify: Trigger the stop intent via `adb shell am start` and observe prompt behaviour.
Minimal patch plan: Change default value of `allowExternalControl` in `ServiceStore`.

[Severity: P2]
Area: performance / architecture
File(s): `service/src/main/java/com/github/kr328/clash/service/util/GeoUrlSanitizer.kt`
Problem: Uses a simple mtime/size check `signatureOf(file)` to bypass YAML parsing. On some file systems or rapid updates, this signature might collide or miss changes (though mitigated by the low-resolution nature).
Why it matters: Could lead to the sanitizer not running when a profile updates too fast, leaving a malicious `geox-url` intact temporarily.
Suggested fix: Use a stronger hash (e.g., SHA-1) of the file content or an explicit dirty flag instead of `mtime` + `size`.
Risk/tradeoff: Hashing adds disk I/O overhead on every connection start.
How to verify: Fast sequence write of file with identical length but different content and verify if sanitizer runs.
Minimal patch plan: Replace `signatureOf` with MD5/SHA1 hash.

[Severity: P2]
Area: build / release
File(s): `build.gradle.kts`
Problem: Dependency versions and compiler warnings indicating deprecated features (`kapt` vs `ksp` for room/databinding, deprecated Android APIs).
Why it matters: Technical debt that will eventually block upgrading to newer AGP/Gradle versions or targeting API 36+.
Suggested fix: Migrate fully to KSP and resolve deprecated `onBackPressed()` warnings in UI.
Risk/tradeoff: Low risk, high maintainability reward.
How to verify: Build logs should be free of deprecation warnings.
Minimal patch plan: Update `BaseActivity` to use `OnBackPressedDispatcher`.

1. Top 5 urgent fixes:
   - Stabilize `TunService` handoff race conditions (P1).
   - Review and possibly secure `allowExternalControl` defaults against automation abuse (P2).

2. Top 5 medium-risk technical debts:
   - Migration from KAPT to KSP for data binding.
   - Deprecated `onBackPressed()` usages across all Activities.
   - `GeoUrlSanitizer` signature generation logic.
   - Foreground service `specialUse` type justification refinements in Manifest.

3. Suggested roadmap:
   - quick wins: Fix deprecation warnings and `GeoUrlSanitizer` caching.
   - security hardening: Lock down external intents and review geo mirror list.
   - stability/performance: Investigate robust `TunService` lifecycle.
   - cleanup after release: Drop dead code and old UI assets.

4. What NOT to touch right now:
   - `mihomo` core submodule update (unless necessary).
   - `ProxyHardener` logic (appears solid and robust).
   - Migration to Jetpack Compose (strictly forbidden by AGENTS.md).

5. Questions for me before changing code:
   - Should we change `allowExternalControl` to default `false`, or keep `true` for Tasker compatibility?
   - Do you want to enforce a stronger hash in `GeoUrlSanitizer` even with the minor performance hit?
   - Should we refactor `TunService` lifecycle, or is the 500ms timeout deemed acceptable for this release?
