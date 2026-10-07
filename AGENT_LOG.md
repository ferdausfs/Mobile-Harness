# AGENT_LOG.md

Newest entries prepended. Never delete entries.

---

## 2026-10-08 — v1.0.11 full-scan fixes (agent-screen install flow, Antigravity v1.0.10 parity, UTF-8/native/installer hardening, ferdausfs URL migration)

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Feature/trigger**: Full-app review after the v1.0.10 release; fix-first the reported "Agent screen: only DeepSeek tab works" symptom, then every real bug found by a whole-app scan; ship v1.0.11 (versionCode 12)

### What changed (one commit per concept)
1. **Agent screen install flow** (`ui/AgentScreen.kt`) — commit `18167f0`
   - The engine selector only acted `if (isInstalled)`: tapping Claude Code or Antigravity when uninstalled was a silent no-op, and the install card below the tabs was dead UI (viewedAgent could never become an uninstalled agent). Tapping an uninstalled agent now shows its install card and starts `installAgent` with live progress, switching on success (existing Settings flow); a rejected switch still toasts "Stop the current agent before switching."
   - `viewedAgent` follows `state.agentKind` via `LaunchedEffect` (no more stale highlighted tab after a Settings/onboarding switch).
   - Install failures surface `agentMessage` on the not-installed card (previously set but never displayed).
   - Claude Code + Antigravity install paths verified sound in code (`AgentRegistry.builtIns` → `RuntimeInstaller.ensureAgentInstalled`); end-to-end on-device not verifiable here (see Pending).
2. **Antigravity v1.0.10 parity** (`runtime/AntigravityRuntimeBridge.kt`) — commit `400ff0d`
   - v1.0.10's per-session state and failed-attempt-changes fixes touched only Claude/Dsh. Antigravity: `onFailure` now merges the attempt baseline's changed paths into changes.json + emits FilesChanged (a failed task's edits stay visible/undoable instead of being absorbed into the next baseline); `userStopRequested` is per-session (`AntigravitySessionState`); tail teardown guarded by `activeSessionId == sessionId` so an old dying session can't clear the new session's Stop or hijack notifications.
3. **UTF-8 chunk-boundary corruption** (`runtime/BoundedFileReads.kt`, all 3 bridges) — commit `fc87957`
   - All bridges decoded each 16 KB output chunk independently: a multibyte char straddling a boundary became U+FFFD, breaking the whole JSON event line (lost deltas/tool events/possibly the result envelope). New `Utf8LineAssembler` decodes only complete newline-terminated lines (same scheme the gateways already used); Claude/Dsh/Antigravity (session + hello probe) use it; capped recent-output buffer preserves error diagnostics. Tests: `Utf8LineAssemblerTest` (5, incl. the multibyte-split regression).
4. **Native spawn** (`app/src/main/cpp/pocket_spawn.c`, `runtime/NativeSpawnProcess.kt`) — commit `1d9fea1`
   - Double-reap race: watchdog/stop threads call `isAlive()` while a worker blocks in `waitFor()`; the WNOHANG side could reap first, the blocking waitpid got ECHILD, and -138 was cached as the exit code (success → bogus failure). Single-reaper lock; waitFor polls WNOHANG (liveness checks stay responsive); exit status never overwritten; errno encodings retried briefly, never cached; C side retries waitpid on EINTR.
   - Failed spawn returned NULL to a non-null `IntArray` → bare NPE; now throws RuntimeException (skipped if a JNI exception is pending).
   - Child resets `SIGPIPE` to SIG_DFL (Android's SIG_IGN survives execve; guest pipelines died incorrectly). `signal()` is async-signal-safe; fd-sweep/leak fixes from v1.0.10 re-verified intact.
5. **Agent updates** (`runtime/RuntimeInstaller.kt`) — commit `c8253f1`
   - `isVersionNewer` folded pre-release digits into the numeric core: stable `0.1.2` was never offered over `0.1.2-rc.1`, and suffix words containing "rc" misclassified. Now semver-style (numeric core first, stable outranks its own pre-release), exposed as internal `isAgentVersionNewer` + `AgentVersionComparisonTest` (7).
   - `updateClaude`/`updateAgy` renamed the new binary over the old before verification with no rollback → a broken release left a dead binary with a stale marker. Both keep `.previous` aside and restore on verification failure (same contract `updateDsh` already had).
6. **techjarves → ferdausfs URL migration** (`app/build.gradle.kts`, `fdroid/`, `PRIVACY.md`, `docs/`, `README.md`, `scripts/build-play-release.sh`) — commit `a70bccc`
   - `runtimeReleaseBaseUrl` (BuildConfig, online flavor) and the default privacy-policy URL pointed at the frozen techjarves org (latest release v1.0.4, no redirects). The `runtime-2026.09.4` release assets (7 bundles + manifest, ~1 GB) were mirrored to `ferdausfs/Mobile-Harness` release `runtime-2026.09.4` byte-for-byte, sha256-verified against `manifest.json` (core-09.4 has no manifest entry; uploaded unverified). fdroid metadata refreshed (author/URLs, version pins 1.0.2/3 → 1.0.11/12); README badge/download links → v1.0.11; `build-play-release.sh` no longer defaults to versionCode 1 when called without args. YouTube channel link intentionally kept (different platform).
7. **Release** — commits `1e4da8c` (bump), `d2a81f6` (manifest)

### Verification
- Unit tests: `./gradlew testOnlineDebugUnitTest` → **118 tests, 0 failures, 0 errors** (12 new). Bare `testDebugUnitTest` remains ambiguous with flavors. `testOfflineDebugUnitTest` could NOT run on this host: `prepareOfflineRuntimeAssets` copies all 5 bundles (~870 MB) into build/ and the 4 GB-RAM/10 GB-disk box ran out of headroom — same test source set as online (flavor-independent), so coverage is not affected.
- Native compile verified via full `:app:externalNativeBuildOnlineRelease` + NDK clang `-Wall -Wextra -fsyntax-only`.
- APK: `mobile-harness-online-v1.0.11.apk`, 87,635,591 bytes, sha256 `9283618c6b5c67148edbf48a94f520fb1e593ac39c106bd963b56726d530c8c5` (re-downloaded from the release URL and re-verified).
- **Signature cert SHA-256 `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` — identical to v1.0.9/v1.0.10** (apksigner verify --print-certs, compared). Same key; in-place update works.
- Release: https://github.com/ferdausfs/Mobile-Harness/releases/tag/v1.0.11 (ID 406216069), assets: APK + `mobile-harness-update.json`. `releases/latest/download/mobile-harness-update.json` serves versionCode 12. v1.0.9/v1.0.10 releases and tags untouched.

### Touched files
`ui/AgentScreen.kt`, `runtime/AntigravityRuntimeBridge.kt`, `runtime/BoundedFileReads.kt`, `runtime/ClaudeRuntimeBridge.kt`, `runtime/DshRuntimeBridge.kt`, `runtime/NativeSpawnProcess.kt`, `runtime/RuntimeInstaller.kt`, `app/src/main/cpp/pocket_spawn.c`, `app/build.gradle.kts`, `mobile-harness-update.json`, `fastlane/metadata/android/en-US/changelogs/12.txt`, `fdroid/com.jarves.mh.yml`, `PRIVACY.md`, `README.md`, `docs/PLAY_STORE_CHECKLIST.md`, `docs/play/APP_CONTENT_DECLARATIONS.md`, `scripts/build-play-release.sh`, tests (`AgentVersionComparisonTest`, `Utf8LineAssemblerTest`), this file.

### Pending items (real gaps found by the scan, deliberately not fixed this release)
- **Usage tracking hole**: `recordUpstreamResult` is only fed by `LocalFormatGateway.reportUsage`, which the Claude bridge installs for OpenAI-protocol providers only. DeepSeek Harness (all providers) and Claude on Anthropic-protocol providers never report usage → the Live status counters stay 0 and the pre-task daily-limit failover never fires there. Fixing requires usage extraction in the Dsh/Claude protocol parsers = feature work, deferred.
- **Offline flavor**: unbuildable from a fresh clone (5 gitignored bundle tarballs required); a bootstrap script (download-by-sha256 from the runtime release) would fix tests and builds.
- **No CI**: zero GitHub workflows; tests run only locally. A test-only workflow is the safe first step.
- **On-device end-to-end** (install Claude Code/Antigravity → authenticate → run a prompt) not verifiable in this environment — code paths reviewed only.
- Minor: SetupScreen's agent switch sheet switches to an uninstalled agent without offering install (clear error appears on send; left as is).
- Keystore unchanged: recoverable from private gist `4b76689e972c864d8a6e187503b174c4`; local copy `keystores/mobile-harness-release.jks`.

### Notes for next agent
- Build env bootstrap notes from the v1.0.10 entry below still apply (JDK 21 at /home/z/jdk, SDK 36 + NDK r26b via direct zips, submodules required, RAM/workers strategy in gradle.properties, agy bundle in dist/runtime-bundles).
- **New**: runtime bundles now ALSO serve from this repo (`releases/download/runtime-2026.09.4/...`); `runtimeReleaseBaseUrl` in `app/build.gradle.kts` points there since v1.0.11. The techjarves mirror still exists but is stale — never point anything new at it.

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
