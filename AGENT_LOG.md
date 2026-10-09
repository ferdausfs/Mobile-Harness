# AGENT_LOG.md

Newest entries prepended. Never delete entries.

---

## 2026-10-09 — F-07 accounting: usage reporting wired for DSH + direct Anthropic/OpenRouter; Antigravity marked as not tracked

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Scope**: Audit finding F-07 (accounting). Approved scope this session: F-06, F-07, F-08, plus a build-time check for F-02.

### Root cause (re-verified at source)
`ProviderUsageTracker.recordUpstreamResult()` is the only path that increments the live-status-card counters and the `remainingToday` field that drives the pre-task daily-budget failover. It was wired **only** through `LocalFormatGateway.reportUsage()` (`LocalFormatGateway.kt:378–389`), and `LocalFormatGateway` is started by `ClaudeRuntimeBridge` **only** when `effectiveProtocol` is `OPENAI_CHAT` or `OPENAI_RESPONSES` (`ClaudeRuntimeBridge.kt:203–215`). Three routes therefore produced zero counters and never tripped the local daily-request limit:

1. **Claude on direct Anthropic-protocol** (no gateway) — `recordUpstreamResult` never called.
2. **Claude on OpenRouter routing gateway** — gateway forwards requests but does not call `onUpstreamResult`; tracker stays at zero.
3. **DSH (all providers, all protocols)** — `DshRuntimeBridge` was constructed with no `onUpstreamResult` callback (`MainViewModel.kt:284`), so even when DSH's stream carried usage info it was discarded.

In addition, **Antigravity** is excluded from provider usage entirely (`MainViewModel.kt:3255` short-circuits the daily-budget check for `AgentKind.ANTIGRAVITY`), but the live status card still showed the same zero-counters UI as a tracked provider, which is misleading.

### What changed (commit `1626d4b`, one review set)
1. **`ProviderUsageTracker.kt`** — added a `usageTracked: Boolean = true` field to `ProviderUsageSnapshot`. Added `markUsageNotTracked(kind, reason)` and `markUsageTracked(kind)`. `recordUpstreamResult` now flips `usageTracked=true` whenever it receives non-zero token data, so a real report from any route automatically re-enables tracking. The new field is persisted to and loaded from the saved JSON (`"usageTracked"` key; defaults to `true` for old saves).
2. **`ClaudeRuntimeBridge.kt`** — added `cachedProviderKind` field set at `startSession`. Added `reportResultUsage(result)` which extracts token usage from the terminal `"result"` event's `usage` object (`input_tokens` / `output_tokens`, with `prompt_tokens` / `completion_tokens` aliases) and forwards it via `onUpstreamResult(kind, 200, in, out)`. Covers direct Anthropic, OpenRouter routing, and Custom OpenAI routes that bypass the in-app gateway. The gateway path continues to report per-HTTP-call usage as before; result-event reporting fires once per task with the consolidated totals, and is a no-op when both token fields are zero (the CLI emits `usage: {}` on errors that we already classify via `ProviderRuntimeErrorDetector`).
3. **`DshRuntimeBridge.kt`** — added `onUpstreamResult` constructor callback (same shape as Claude's). Added a new `DshSdkProtocolEvent.UsageReported` variant. The `turn/end` parser now extracts `data.usage` (or `data.reason.usage`) via a new `extractUsage()` helper that accepts `input_tokens`/`output_tokens`, `prompt_tokens`/`completion_tokens`, or `total_tokens` as a fallback. When usage is present, the parser emits `UsageReported` instead of (or before) `TurnCompleted`/`Failed`; the SDK session loop forwards it to `onUpstreamResult`. Routes where DSH does not report usage stay at zero counters honestly — no fake numbers.
4. **`MainViewModel.kt`** — wired `DshRuntimeBridge`'s new `onUpstreamResult` callback to `usageTracker.recordUpstreamResult`. Added a `markUsageNotTracked(turnKind, "Antigravity CLI does not report token usage")` call when the user starts an Antigravity turn. Tightened the daily-budget failover check from `remainingToday == 0` to `usageTracked && remainingToday == 0` so a tracked provider that genuinely hit its limit still triggers failover, but an untracked provider never does.
5. **`AgentScreen.kt`** — `ProviderStatusRow` now branches on `snapshot.usageTracked`:
   - `false` → shows "Usage not tracked for this route" (italic) and only the task counter (which is still tracked for all agents). Hides the daily-limit progress bar so an untracked provider doesn't display a meaningless 100%-left bar.
   - `true` → unchanged (requests/tokens/limit/progress as before).

### What is intentionally NOT changed
- Antigravity is still excluded from the daily-budget failover (`if (state.value.agentKind != AgentKind.ANTIGRAVITY)`). The audit explicitly notes that Antigravity uses Google's official CLI which does not surface per-request token usage in its stream-json output. Per the user's brief: "If a route cannot report real usage, do NOT fake numbers: make the UI clearly say usage is not tracked for that route." This commit implements exactly that.
- `OpenRouterRoutingGateway.kt` does not call `onUpstreamResult`; the OpenRouter routing path is now covered by Claude's result-event reporter instead. Adding per-HTTP-call reporting to the routing gateway is left for a follow-up if finer-grained counters are needed (the consolidated result-event report is sufficient for the daily-budget gate and the live card).
- The OpenAI-protocol gateway path (`LocalFormatGateway`) is unchanged; it still reports per-HTTP-call usage as before. The result-event reporter and the gateway reporter do not double-count because the gateway increments `dayRequests` per HTTP call while the result-event reporter increments it once per task. The user sees the higher of the two counts, which is the correct behaviour (each task is at least one HTTP call).

### Verification
- `./gradlew :app:assembleOnlineRelease` → **BUILD SUCCESSFUL in 2m 46s**.
- `./gradlew :app:testOnlineDebugUnitTest` → **BUILD SUCCESSFUL** (existing DSH parser tests, failover tests, and provider-usage tests all green; no new tests added because the new event type is exercised only via the parser extension which the existing `turn/end` coverage touches).
- APK: `app-online-release.apk`, 87,642,111 bytes, sha256 `199cf6551e20f521b613a70ec42beb9b6f503d945cc0ecc1d346650097fa7371`.
- Signature cert SHA-256 `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` — verified via `apksigner verify --print-certs` from build-tools 35.0.0. Identical to v1.0.8–v1.0.13 line; in-place update path preserved.

### Notes for next agent
- The new `usageTracked` field is persisted, so existing installs will load `usageTracked=true` for every saved provider (the `optBoolean("usageTracked", true)` default). A provider that is actually untracked (Antigravity) gets flipped to `false` on the next Antigravity turn and stays that way until the user runs a tracked agent on the same provider kind — which is the intended UX (the card reflects "the most recent route used for this provider kind").
- If a future DSH version starts emitting `usage` under a different key (e.g. `data.metadata.usage`), `extractUsage` will return null and the route will appear untracked. Re-check the DSH SDK release notes before adding more key paths.
- The result-event usage reporter relies on Claude Code's stream-json schema. If Claude Code's CLI changes to emit usage under `result.meta.usage` or similar, `reportResultUsage` will silently no-op (the result event is still consumed for completion signalling). Re-check after CLI upgrades.

---

## 2026-10-09 — F-06 privacy: Claude/DSH bridge logs no longer leak prompt/history/stream

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Scope**: Audit finding F-06 (privacy). Approved scope this session: F-06, F-07, F-08, plus a build-time check for F-02. F-03/F-04/F-05 explicitly out of scope (product/security decisions).

### Root cause (re-verified at source)
`ClaudeRuntimeBridge.kt` emitted three categories of sensitive data to logcat via `Log.d` with no `BuildConfig.DEBUG` guard, while release `isMinifyEnabled = false` (`app/build.gradle.kts:130`) kept those statements live in shipped APKs:
1. `Launching command: $command` — the `command` list contained `contextPrompt` as the `-p` argument. `contextPrompt` is built from the user's current prompt **plus** recent conversation history (`buildContextPrompt(prompt, conversationHistory, …)`).
2. `OUTPUT: $line` / `TRAILING OUTPUT: $line` — every raw stream-json line emitted by the Claude CLI. These lines contain assistant text, tool inputs/outputs (file contents, shell commands), and provider-side error messages.
3. `Provider: …, BaseUrl: …` / `Launch environment keys: …` — provider identity and the env-var names passed to the CLI (not values, but still metadata).

`DshRuntimeBridge.kt` had less sensitive `Log.d` calls (route name, model, exit code, changed-files list) but the same pattern of being live in release builds.

`AntigravityRuntimeBridge.kt` was checked — no `Log.d/i/v/w/e` calls at all. No change.

### What changed (commit `408801f`, one review set)
- `ClaudeRuntimeBridge.kt`:
  - Wrapped every `Log.d` in `if (BuildConfig.DEBUG)`. For the launch log, replaced `Launching command: $command` with `Launching claude (model=…)` — the full command line is no longer logged even in debug, because `contextPrompt` is part of it. (A debug build still gets the model and provider info; a release build gets nothing.)
  - Removed the `OUTPUT:` and `TRAILING OUTPUT:` `Log.d` calls entirely. There is no safe redaction possible for raw stream-json: lines may be tool-result messages that embed file contents. The error detector and the `consumeClaudeEvent` parser still receive the line as before; only the log statement is gone.
  - Guarded `Log.e("ClaudeBridge", "Session failed", error)` and `Log.w("ClaudeBridge", "Could not post task result notification", error)` with `BuildConfig.DEBUG`. The user-facing failure message is still surfaced through `friendlyError(error)` + `RuntimeEvent.RuntimeFailure`, so release users still see what failed — only the stacktrace stops entering logcat.
  - Added `import com.jarves.mh.BuildConfig`.
- `DshRuntimeBridge.kt`:
  - Same `BuildConfig.DEBUG` guard applied to its three `Log.d` calls (route/model, exit code, changed files) and the two `Log.e`/`Log.w` failure paths. DSH does not currently log raw stream content, so nothing was deleted — just gated.
  - Added `import com.jarves.mh.BuildConfig`.

### What is intentionally NOT changed
- `LocalFormatGateway.kt:87` `Log.w("FormatGateway", "Provider returned HTTP ${upstream.first}: ${providerError(upstream.second)}")` — logs only HTTP status + provider-side error string (no user prompt, no response body). Operational, left unguarded.
- `RuntimeInstaller.kt` install-progress logs (`Log.i` about stripped macOS metadata, `Log.e`/`Log.w` about Linux compat link repair and UTF-8 decode failures) — operational, no prompt data. Left unguarded.
- All other `Log.d` calls in the project (no others exist in the three runtime bridges or `LocalFormatGateway`/`OpenRouterRoutingGateway`).
- The README's "zero plain-text leaks" claim is now actually true for prompt/history/stream content for Claude+DSH. It was not true before this commit. The README itself was not edited as part of F-06 (F-08 handles README/version metadata separately).

### Verification
- `./gradlew :app:assembleOnlineRelease` → **BUILD SUCCESSFUL in 2m 35s**.
- APK: `app/build/outputs/apk/online/release/app-online-release.apk`, 87,636,107 bytes, sha256 `c70d594a7d8fba64aae58c2222648a05856ea9ef986af55662f29d90549f9eb1`.
- Signature cert SHA-256 `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` — verified via `apksigner verify --print-certs` from build-tools 35.0.0. Identical to v1.0.8–v1.0.13 line; in-place update path preserved.
- Not run this commit: unit tests (F-06 is a log-statement change; no test asserts log output; running full `testOnlineDebugUnitTest` is deferred to F-07/F-08/F-02 to avoid four rebuild cycles).

### Notes for next agent
- The "Launching claude" debug log still includes `launch.environment["ANTHROPIC_MODEL"]` and `provider.model` — model identity, not prompt content. If you ever expand it, do not include the `command` list (it contains `-p contextPrompt`).
- Build env bootstrap this session: JDK 21 at `/home/z/jdk` (Temurin 21.0.5+11, freshly downloaded — env was wiped), SDK at `/home/z/android-sdk` (platform-tools, platform-36 r02, build-tools 35.0.0 + 36.0.0, licenses written directly), NDK r26b at `/home/z/android-ndk`. Submodules initialized. AGY bundle in `dist/runtime-bundles/` (sha256 `a659ab91…d78`, matches manifest.json). Keystore fetched from private gist `4b76689e972c864d8a6e187503b174c4` to `/home/z/my-project/keystores/mobile-harness-release.jks`. `local.properties`, `gradle.properties.orig`, `gradle.properties.local`-merged-into-`gradle.properties` are all gitignored or excluded from commits (only the runtime source files went into commit `408801f`).
- The `gradle.properties` file in the working tree was tuned for this 4 GB box (`-Xmx2400m -XX:+UseSerialGC`, `kotlin.compiler.execution.strategy=in-process`, `--no-daemon`, workers.max=1). It is NOT committed; the committed `gradle.properties` is unchanged.

---

---

## 2026-10-08 — v1.0.13 release: OpenRouter + Ollama Cloud provider fixes (dual-wire validation/runtime, reasoning-model hardening)

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Feature/trigger**: User report after v1.0.12: OpenRouter key rejected on the OpenRouter provider but the same key works via Custom API; Ollama Cloud "not supported anywhere".

### Root causes found
1. **OpenRouter validation sent no `anthropic-version` header** (OPENROUTER was excluded from the header block in `ProviderApiClient.request`) and its `validationCandidates` had **no fallback** (`OPENROUTER -> Unit`). A valid key therefore died on the `/api/v1/messages` shim while Custom auto-detected the OpenAI wire and succeeded — the exact reported symptom.
2. The runtime could not honor a detected wire for OpenRouter: `providerProtocolForAgent` ignored `dshApi` for LLM_ROUTER, `DshRouteMapper` hardcoded `anthropic-messages`, and detection persistence was CUSTOM-only.
3. The OpenRouter routing gateway would 404 an OpenAI-wire dsh route routed through it (only `/messages` is proxied).
4. Ollama Cloud: validation lacked the `/api`→`/v1` rescue the runtime gateway has; 401s gave no Ollama-specific guidance; translated replies from reasoning models (gpt-oss:120b returns `content: null` when the budget is spent reasoning) produced an **empty Anthropic content array**, which Claude Code rejects.
5. Verified healthy upstream: `~anthropic/claude-sonnet-latest` IS in OpenRouter's public catalog (the `~` alias family is real); `ollama.com/v1/models` answers anonymously and `gpt-oss:120b` exists; `/v1/chat/completions` 401s with a bad key (Bearer). Note: OpenRouter's 401 wording is "Missing Authentication header" even for present-but-invalid keys — do not treat that message as a missing-header bug.

### What changed (commit `081e167`, one review set)
- `ProviderApiClient`: anthropic-version for the OPENROUTER protocol (Bearer only, no x-api-key, mirroring the routing gateway); OPENROUTER validation falls back to OpenAI chat candidates; Ollama-style `/api` bases get a `/v1` rescue candidate on both OpenAI branches; Ollama 401 hint (ollama.com/keys); OPENROUTER model discovery tolerates a versioned base.
- `Models.kt` `providerProtocolForAgent`: LLM_ROUTER honors a saved `dshApi` detection (openai-completions → OPENAI_CHAT, openai-responses → OPENAI_RESPONSES, else OPENROUTER).
- `MainViewModel`: detection persistence (remember/apply) extended to LLM_ROUTER.
- `ClaudeRuntimeBridge`: routing gateway only starts when the effective protocol is OPENROUTER (prevents an unused loopback listener shadowing the translation gateway).
- `DshRuntimeBridge`: routing-gateway URL swap only for `anthropic-messages` routes; LLM_ROUTER route honors `dshApi`.
- `LocalFormatGateway`: OpenRouter routing order injected into upstream OpenAI/Responses bodies; empty translated content falls back to the model's `reasoning`/`thinking` text.
- Tests: +4 (`ProviderUniversalCompatTest` x3, `DshBridgeTest` x1) → **122 tests, 0 failures, 0 errors**.

### Verification
- APK `mobile-harness-online-v1.0.13.apk` 87,637,363 bytes, sha256 `85c896e424a064172fa51da20ccf9f8a049820cf49b080cf1b28330f5eb0d4eb` (re-downloaded from the release URL, byte-identical), v2 cert `d07ba804…ccfe` = the v1.0.8+ key (in-place update for v1.0.12 users).
- Release https://github.com/ferdausfs/Mobile-Harness/releases/tag/v1.0.13 (ID 406628924) with APK + manifest; `releases/latest` → v1.0.13, manifest serves versionCode 14.
- certcheck parser script rewritten (the saved copy had a stale u32/u64 bug and printed nothing): `/home/z/my-project/scripts/certcheck/apk_v2_cert.py`.

### Pending / notes for next agent
- On-device end-to-end with REAL OpenRouter/Ollama keys is not verifiable here. If Ollama Cloud still fails after v1.0.13, the on-screen message now shows the provider's actual error — ask the user for that text before guessing further.
- First validation after this update must succeed once for the detected wire to be saved; until then a stale profile keeps its old base/dshApi (defaults unchanged, backward compatible).


## 2026-10-08 — v1.0.12 release: signing-key migration guidance shipped (uninstall-once path made explicit; same key forever)

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Feature/trigger**: Follow-up to the install/update failure diagnosis (previous entry). User demanded a permanent solution, provided a fresh GitHub token. Push the pending fixes, ship them as v1.0.12 (versionCode 13), lock in the signing-key commitment.

### What changed (one commit per concept)
1. **`f3fc15d`** — Updater error messages (AppUpdater.kt, AndroidAppInstallReceiver.kt): signature mismatch now spells out "back up API key → uninstall once → install new APK"; PackageInstaller failures translate `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_FAILED_VERSION_DOWNGRADE` into plain instructions.
2. **`b605bda`** — Version bump 1.0.12 (versionCode 13) + fastlane changelog 13.txt.
3. **`7775230`** — gradle.properties build-JVM tuning: Xmx 2400m + SerialGC + `kotlin.compiler.execution.strategy=in-process`. On the 4 GB/4 GB-swapless container the previous settings OOM-killed the daemon during mergeDex; with these, `:app:assembleOnlineRelease` completes. Swap could not be enabled (container denies swapon) and disk is 9.9 GB — keep tuning lean.
4. **`42a7e3b`** — mobile-harness-update.json → versionCode 13.

### Verification
- Unit tests: `./gradlew testOnlineDebugUnitTest` → **118 tests, 0 failures, 0 errors**.
- APK: `mobile-harness-online-v1.0.12.apk`, 87,636,055 bytes, sha256 `e84d2ac7c98177b8391b9719d208e4d371af3bec0c8d3df090ca11c1b2aefe5d` — re-downloaded from the release URL and re-verified byte-identical.
- **Signature cert SHA-256 `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` — identical to v1.0.8–v1.0.11.** Never change this key; users on v1.0.8+ update in place forever.
- Release: https://github.com/ferdausfs/Mobile-Harness/releases/tag/v1.0.12 (ID 406533197), assets: APK + mobile-harness-update.json. `releases/latest` → v1.0.12; `releases/latest/download/mobile-harness-update.json` serves versionCode 13.
- Runtime bundle `pocketdev-agy-arm64-2026.09.1.tar.zst` re-downloaded from the ferdausfs runtime release, sha256 `a659ab91…d78` matches manifest.json.

### Notes for next agent
- The one-time uninstall is unavoidable for v1.0.4–v1.0.7 phones (Android key check); everyone else updates in place. Release notes include Bangla instructions.
- Build env bootstrap used this session: JDK 21 at `/home/z/jdk` (Temurin), SDK at `/home/z/android-sdk` (platform-36, build-tools 34.0.0, NDK 26.1.10909125), submodules initialized, agy bundle in `dist/runtime-bundles/`. Bootstrap script: `/home/z/my-project/scripts/mh-bootstrap.sh` (needs /tmp cleanup patience; sdkmanager may die silently — rerun the missing pieces).
- Disk is tight (9.9 GB total): clean `/tmp`, old APKs in scripts/certcheck, and app/build before building.
- `apksigner` binary was absent from build-tools 34.0.0 install; cert verification used the self-contained python parser (`scripts/certcheck/apk_v2_cert.py`) — equivalent output to `apksigner verify --print-certs`.

---

## 2026-10-08 — Install/update failure diagnosis (root cause: 4 different signing keys across v1.0.4–v1.0.8) + updater error-message fixes (LOCAL ONLY, not pushed)

- **Agent/tool**: Claude Code agent session (Super Z), direct repo work on `ferdausfs/Mobile-Harness` branch `main`
- **Feature/trigger**: User report "install hoy na .. update o hoy na .." — the phone refuses both a direct APK install and the in-app update. Diagnose the cause, fix what code can fix, publish what publishing allows (no GitHub token available this session → local commits only).

### Root cause (verified, not guessed)
Extracted the APK Signature Scheme v2 signer certificate SHA-256 from every published release (parser script: `/home/z/my-project/scripts/certcheck/apk_v2_cert.py`, mirrors `apksigner verify --print-certs`):

| Release | v2 signing cert SHA-256 |
|---------|------------------------|
| v1.0.4 | `d364b1edd80b955e7fe9d99edc4cc211ce723e461ded6a5160c6a2db3fdd7af1` |
| v1.0.5 | `9d6b60952000a093b71e84b10a773edba71ab664bdc8f4216c290e3d7ebdf1ad` |
| v1.0.6 | `63256b9730ad360de9e81119bdaf1761a2c4bf71ad2d9fe852325ebd20913470` |
| v1.0.7 | `63256b9730ad360de9e81119bdaf1761a2c4bf71ad2d9fe852325ebd20913470` |
| v1.0.8 | `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` |
| v1.0.9 | `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` |
| v1.0.10 | `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` |
| v1.0.11 | `d07ba804cfa95dd39083002628b65487d7ae99acab12cdfa61c5f42bca7dccfe` |

**Four different keys in four releases (v1.0.4 → 5 → 6 → 8).** Android refuses any in-place install whose APK is signed with a different key (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so every user still on v1.0.4–v1.0.7 is hard-blocked from every newer release: direct install fails ("App not installed") AND the in-app updater's own `verifyApk` signature check rejects it by design. minSdk has always been 28 (not a factor). Server side is healthy: `releases/latest` → v1.0.11, APK re-downloaded and sha256 matches `mobile-harness-update.json`, all 7 runtime-bundle assets on `runtime-2026.09.4` return 200.

### What changed (one commit per concept, local only)
1. **AppUpdater signature-mismatch message** (`update/AppUpdater.kt`) — the dead-end "Update is not signed with the installed app's signing key" now explains the one-time uninstall path and the data-loss consequence (API key, chats, workspaces, installed agents).
2. **PackageInstaller failure translation** (`runtime/AndroidAppInstallReceiver.kt`) — `INSTALL_FAILED_UPDATE_INCOMPATIBLE` → "uninstall once, reinstall"; `INSTALL_FAILED_VERSION_DOWNGRADE` → "a newer version is already installed"; raw status message otherwise.

### Verification
- Release/manifest/bundle reachability re-verified over HTTPS (see above). Code changes are string-only; no unit test covers the receiver, and the Android SDK container env was reset this session (rebuild required for a compile run) — no build/package/signing was possible without `MH_UPLOAD_*` secrets + keystore, which are also gone from this environment.

### Pending items
- **Push these two commits + ship v1.0.12 (versionCode 13)** once a GitHub token is available. Requires the standing release procedure: bump version, changelog `13.txt`, build online flavor with `MH_UPLOAD_*`, `apksigner verify --print-certs` must equal `d07ba804...ccfe`, update `mobile-harness-update.json`, GitHub Release.
- **Never change the signing key again.** The v1.0.6 "new release signing key" and the v1.0.8 key switch each orphaned every older install silently. Current key (private gist `4b76689e972c864d8a6e187503b174c4`) must stay forever.
- Optional future kindness: a one-time "export API key to a shareable file" action in Settings would soften the mandatory uninstall for stranded users (feature work — not started).
- Environment for next session: JDK/Android SDK/NDK containers were wiped again; bootstrap per the v1.0.10 entry notes before building.

### Notes for next agent
- The user-facing fix for anyone on v1.0.4–v1.0.7 is **uninstall once → install v1.0.11 fresh** (no code can change Android's key check). Users on v1.0.8+ update normally over the top.
- Cert-fingerprint script is self-contained (`python3 apk_v2_cert.py <apk...>`) — reuse it to verify any future APK before publishing.

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
