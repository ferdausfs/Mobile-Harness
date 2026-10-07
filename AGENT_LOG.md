# AGENT_LOG.md

Newest entries prepended. Never delete entries.

---

## 2026-10-08 — v1.0.10 failover/detection fixes (session-scoped failover, strict error shapes, per-session bridge state, changes-tab baseline, native fork-safety)

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Feature/trigger**: Fix 5 regression concepts introduced by the v1.0.7–v1.0.9 stability pushes; ship v1.0.10 (versionCode 11)

### What changed (one commit per concept)
1. **Failover state** (`app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`) — commit `a8b3e8d` (+ `11753e3` escape fix)
   - `retryWithNextProvider` no longer calls `preferences.saveProvider`; backup provider applies to the running session only, original restored on task end (`restoreFailoverPrimaryProvider`).
   - Pre-task daily-limit switch (was line ~3249) also made session-scoped.
   - Every delayed restart (`delay(300)` → `startSession`) re-checks `isRunning` and `activeRuntimeRequest` identity; each retry stores a fresh `RuntimeRetryRequest` identity so superseded restarts cancel themselves.
   - Mid-task manual provider picks survive the restore (tracked via `failoverActiveKind`).
2. **Error detection** (`runtime/ClaudeRuntimeBridge.kt` `ProviderRuntimeErrorDetector`, `ui/MainViewModel.kt`) — commits `282569f`, `aba8bff`
   - Non-JSON branch and is_error result text match only explicit provider error shapes (`API Error: 4xx`, `HTTP[/1.x] 4xx`, `402 Payment Required`, `authentication_failed`, `invalid api key`, `rate_limit_error`, `too many requests`, `usage limit`, `credit balance`, `api key expired`, OAuth token expiry...). Bare numbers (`line 401`, `in 429 ms`) and loose words (`quota`, `expired`, `rate limit` alone) never match.
   - `api_retry` events classify via structured `error_status`.
   - MainViewModel: `isRateLimitFailure` routes 429/rate-limit failures straight to the backup chain **without** `vault.activate` (no persistent key switch).
   - `friendlyError` in both Claude + Dsh bridges only rewrites explicit auth wording; raw `lastDiagnostic` passes through.
   - Tests updated: `ProviderRuntimeErrorDetectorTest` (17 tests incl. 7 new regressions), `FailoverProviderTest` unchanged/green.
3. **Bridge shared state** (`runtime/ClaudeRuntimeBridge.kt`, `runtime/DshRuntimeBridge.kt`) — commit `cd813d0`
   - `streamedText`, `streamedThinking`, `toolNames`, `seenToolCalls`, `userStopRequested` + thinking/reasoning throttles moved into per-session state maps (`SessionStreamState` / `DshSessionState`). Late tail output from an ended session is dropped; stop flags set per session only.
4. **Changes tab on retry** (`ClaudeRuntimeBridge.kt`, `DshRuntimeBridge.kt`) — commit `3abeef0`
   - Attempt baseline (`snapshot(workspace)`) kept reachable from `onFailure`; a failed attempt persists its changed paths (merged into `changes.json`) and emits `FilesChanged`, so the failover retry's fresh snapshot cannot hide them.
5. **Native** (`app/src/main/cpp/pocket_spawn.c`) — commit `04de6f1`
   - `close_inherited_fds()` replaced `opendir/readdir` (not async-signal-safe after fork in multithreaded JVM) with `close_range(2)` + bounded plain-close fallback; verified no launcher path relies on inherited fds.
   - All early `return NULL` paths free argv/envp/cwd/output_path/slave_name via a `fail:` label; forked child killed+reaped on failed handover; every `strdup`/`GetStringUTFChars` null-checked; JNI exceptions handled; `NewIntArray` null-checked.
6. **Release** — commits `4cb8936` (bump), `f2f1718` (manifest)

### Verification
- Unit tests: `./gradlew testOnlineDebugUnitTest testOfflineDebugUnitTest` → **212 tests, 0 failures, 0 errors** (note: bare `testDebugUnitTest` is ambiguous with flavors — run both flavor tasks).
- Native compile verified via NDK clang 17 (`gcc -Wall -fsyntax-only` + full `externalNativeBuildOnlineRelease`).
- APK: `mobile-harness-online-v1.0.10.apk`, 87,628,887 bytes, sha256 `adb83867a01d2bcd80446ae20c8c1c19b12c19102d0198f5e78b259476f2c17c`, v2-signed.
- **Signature cert SHA-256 `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` — identical to v1.0.9** (apksigner verify --print-certs, compared). Same key as v1.0.8/9 line.
- Release: https://github.com/ferdausfs/Mobile-Harness/releases/tag/v1.0.10 (ID 406172306), assets: APK + `mobile-harness-update.json`. `releases/latest/download/mobile-harness-update.json` serves versionCode 11; APK re-downloaded from the release URL and hash re-verified. v1.0.9 release/tag untouched.

### Touched files
`ui/MainViewModel.kt`, `runtime/ClaudeRuntimeBridge.kt`, `runtime/DshRuntimeBridge.kt`, `app/src/main/cpp/pocket_spawn.c`, `app/build.gradle.kts`, `mobile-harness-update.json`, `fastlane/metadata/android/en-US/changelogs/11.txt`, `app/src/test/.../ProviderRuntimeErrorDetectorTest.kt`, this file.

### Environment / build notes for next agent
- **Keystore**: release keystore `mobile-harness-release.jks` (alias `mobile-harness`) is backed up in the **private gist `4b76689e972c864d8a6e187503b174c4`** (`mobile-harness-release.jks.b64` + `KEYSTORE_INFO.txt` with the passwords). Local copy: `/home/z/my-project/keystores/mobile-harness-release.jks` (env resets wipe it — re-fetch from the gist). Cert SHA-256 `d07ba804...ccfe` — **NEVER generate a new key**; verify against the v1.0.9/v1.0.10 APKs before signing. Signing env vars: `MH_UPLOAD_STORE_FILE/STORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD` (no CI workflow exists — build locally).
- **Build env bootstrap** (environment resets wipe everything): JDK 21 → `/home/z/jdk` (temurin direct URL works, ~207MB); SDK → `/home/z/android-sdk` via direct zips (`platform-36_r02.zip`, `build-tools_r36_linux.zip` — underscore, not hyphen!, `platform-tools-latest-linux.zip`) because `sdkmanager` stalls/OOMs here; NDK 26.1.10909125 (r26b zip, ~670MB) required for the CMake native build; **git submodules required**: `git submodule update --init --recursive` (proot + libandroid-shmem under `third_party/`).
- **RAM is 4GB, no swap**: OOM kills the JVM mid-build. Workaround that worked: `gradle.properties` locally adds `kotlin.compiler.execution.strategy=in-process` + `kotlin.daemon.jvmargs=-Xmx900m` (reverted before commit — re-add locally), run `:app:externalNativeBuildOnlineRelease` first, then `:app:assembleOnlineRelease`, `--no-daemon`, workers.max=1.
- Runtime bundle `pocketdev-agy-arm64-2026.09.1.tar.zst` → `dist/runtime-bundles/` (gitignored) from techjarves/Mobile-Harness release `runtime-2026.09.4`; sha256 must match `manifest.json` (`a659ab91...d78`).
- Update manifest must be uploaded **as a release asset** (not just committed) for `releases/latest/download/...` to serve it.

### Pending items
- None from this session's brief. (User earlier wanted Ollama Cloud usage/live-quota display polish and provider fallback UX — v1.0.7–9 already added the provider + failover chain; this session fixed its regressions. No new features were in scope.)
