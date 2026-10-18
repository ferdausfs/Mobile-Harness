package com.jarves.mh.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.ViewGroup
import com.jarves.mh.ui.theme.PocketOrange
import com.jarves.mh.zweb.ZaiAuthProbeClassifier
import com.jarves.mh.zweb.ZaiProbeScripts
import com.jarves.mh.zweb.ZaiPromptBuilder
import com.jarves.mh.zweb.ZaiRunPhase
import com.jarves.mh.zweb.ZaiSheetDiagnostics
import com.jarves.mh.zweb.ZaiSheetUserAgent
import com.jarves.mh.zweb.ZaiWebUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * The "Z.ai" tab body: account card (sign in / status / disconnect), connection
 * test, and the task box that drives chat.z.ai through the hidden WebView.
 *
 * Mirrors the Antigravity card layout; purely additive to the Agent screen and
 * completely independent from the CLI agent stack.
 */
@Composable
fun AgentZaiCard(
    state: AppUiState,
    onCheckConnection: () -> Unit,
    onSignIn: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit,
    onRunTask: (String) -> Unit,
    onCancel: () -> Unit,
    onCloseSignIn: () -> Unit,
    onSignedInViaSheet: () -> Unit,
    onClearResult: () -> Unit,
    onSheetDiagnostics: (ZaiSheetDiagnostics) -> Unit = {},
) {
    val zai = state.zaiWeb
    var task by remember { mutableStateOf("") }

    // First open: resolve the sign-in state once (also re-resolves after the
    // config hot-update) so the card never shows a stale unknown state.
    LaunchedEffect(Unit) {
        if (zai.signedIn == null && !zai.checkingConnection) onCheckConnection()
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Z.ai account", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            // ── Account row (mirrors the Google account row) ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(PocketOrange.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(1.dp, PocketOrange.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Z", color = PocketOrange, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        zai.accountEmail
                            ?: if (zai.signedIn == true) "Connected" else "Not signed in",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            zai.checkingConnection -> "Checking chat.z.ai…"
                            zai.signedIn == true -> "Connected with chat.z.ai"
                            zai.signedIn == false -> "Required for Z.ai Web"
                            else -> "Sign in with your free Z.ai account"
                        },
                        fontSize = 11.sp,
                        color = if (zai.signedIn == true) Color(0xFF2E9D72) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (zai.signedIn == true) {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = "Disconnect",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onDisconnect() },
                    )
                }
            }

            // ── Sign-in / status actions ──
            if (zai.checkingConnection) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (zai.signedIn != true) {
                Button(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Public, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign in to Z.ai", fontWeight = FontWeight.Bold)
                }
            }

            zai.runMessage?.takeIf { zai.runPhase == ZaiRunPhase.NEEDS_LOGIN }?.let { message ->
                ZaiNoticeRow(
                    icon = { Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) },
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (zai.signedIn == true) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // ── Connection test (mirrors the Antigravity test button) ──
                OutlinedButton(
                    onClick = onTest,
                    enabled = !zai.busy,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.7f)),
                ) {
                    if (zai.testRunning) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketOrange)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (zai.testRunning) "Testing connection…" else "Test connection",
                        color = PocketOrange,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                zai.runMessage?.takeIf {
                    zai.runPhase == ZaiRunPhase.CAPTCHA || zai.runPhase == ZaiRunPhase.TIMEOUT || zai.runPhase == ZaiRunPhase.ERROR
                }?.let { message ->
                    ZaiNoticeRow(
                        icon = {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                Modifier.size(14.dp),
                                tint = if (zai.runPhase == ZaiRunPhase.TIMEOUT || zai.runPhase == ZaiRunPhase.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    PocketOrange
                                },
                            )
                        },
                        text = message,
                        color = if (zai.runPhase == ZaiRunPhase.TIMEOUT || zai.runPhase == ZaiRunPhase.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // ── Task box ──
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        "New task",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = task,
                        onValueChange = { task = it },
                        placeholder = { Text("Describe what Z.ai should build or answer…") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        shape = RoundedCornerShape(14.dp),
                        enabled = !zai.busy,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (zai.busy && (zai.runPhase == ZaiRunPhase.OPENING_PAGE ||
                            zai.runPhase == ZaiRunPhase.SENDING || zai.runPhase == ZaiRunPhase.GENERATING ||
                            zai.runPhase == ZaiRunPhase.EXTRACTING)
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                zai.runMessage ?: "Working…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onCancel) { Text("Stop") }
                        }
                        Spacer(Modifier.height(4.dp))
                    } else {
                        Button(
                            onClick = {
                                onClearResult()
                                onRunTask(task)
                                task = ""
                            },
                            enabled = task.isNotBlank() && !zai.busy,
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(13.dp),
                        ) {
                            Icon(Icons.Default.Send, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Send task to Z.ai", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // ── Result: reply + extracted files ──
            if (zai.lastReply != null || zai.lastFiles.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Z.ai reply",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Clear",
                            fontSize = 11.sp,
                            color = PocketOrange,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onClearResult() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                    MarkdownText(markdown = zai.lastReply.orEmpty())
                    if (zai.lastFiles.isNotEmpty()) {
                        Text(
                            "Files extracted (${zai.lastFiles.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        zai.lastFiles.forEach { file ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.InsertDriveFile,
                                        null,
                                        Modifier.size(15.dp),
                                        tint = PocketOrange,
                                    )
                                    Spacer(Modifier.width(9.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            file.path,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${file.content.toByteArray().size} bytes" +
                                                if (file.language.isNotBlank()) " · ${file.language}" else "",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        zai.lastFilesDir?.let { dir ->
                            Text(
                                "Saved to app storage under $dir",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }

            Text(
                "Runs chat.z.ai in a hidden in-app browser · Sign-in stays on this device",
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (zai.signInSheetOpen) {
        ZaiSignInSheet(
            automationJson = zai.sheetAutomationJson,
            onClose = onCloseSignIn,
            onSignedIn = onSignedInViaSheet,
            onSheetDiagnostics = onSheetDiagnostics,
        )
    }
}

@Composable
private fun ZaiNoticeRow(icon: @Composable () -> Unit, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(7.dp))
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Full-screen visible WebView for the one-time chat.z.ai sign-in. Shares the
 * default CookieManager with the hidden engine WebView, so after this sheet
 * reports success the hidden browser is signed in too.
 *
 * v1.0.20 fixes (observed on a real device, then probed in a headless browser
 * with a mobile viewport and a WebView user agent):
 *
 *  1. The page loaded at /auth can come up as the guest home instead of the
 *     login form (how the site's own JS treats embedded WebViews). The sheet
 *     therefore watches the page and, when the login form is not on screen,
 *     opens the sidebar and taps "Sign in" itself — every lookup goes through
 *     the selector config (sidebarToggle / signInButton / loginFormMarker
 *     groups), never a hardcoded selector. When the automation cannot reach
 *     the form, a clear hint line says what to tap by hand.
 *  2. Signed-in detection is a ~2s poll of the session auth probe; on success
 *     the sheet closes itself and the card shows "Connected". Closing via X
 *     runs one final probe first, so "logged in, then closed" is never lost.
 *     The current page URL is shown in a small debug line while open.
 *  3. The sheet's WebView presents a Chrome-like UA (WebView markers stripped)
 *     so Google's disallowed_useragent filter does not reject the OAuth flow.
 *     Google's account chooser is a same-WebView navigation; anything the site
 *     shows — including its captcha puzzle — stays fully interactive here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZaiSignInSheet(
    automationJson: String?,
    onClose: () -> Unit,
    onSignedIn: () -> Unit,
    onSheetDiagnostics: (ZaiSheetDiagnostics) -> Unit,
) {
    var progress by remember { mutableStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var verified by remember { mutableStateOf(false) }
    var sheetUrl by remember { mutableStateOf("") }
    var sheetUa by remember { mutableStateOf("") }
    var showHint by remember { mutableStateOf(false) }
    var captchaSeen by remember { mutableStateOf(false) }

    val selectors = remember(automationJson) { ZaiProbeScripts.parseSheetSelectors(automationJson) }
    val authProbeJs = remember(selectors) {
        ZaiProbeScripts.buildAuthProbeForPath(selectors?.authProbePath ?: SHEET_FALLBACK_AUTH_PROBE_PATH)
    }

    // Session poll (~2s): auto-close on success; drive the login UI open otherwise.
    LaunchedEffect(selectors) {
        var autoRounds = 0
        while (isActive) {
            delay(SHEET_POLL_INTERVAL_MS)
            val view = webViewRef ?: continue
            if (verified) break

            // 1. Session check — runs on every tick, on any page (a fetch to a
            //    foreign origin simply fails and reads as signed-out). The probe
            //    mirrors the site's own check: Bearer-from-localStorage + cookie.
            val verdict = ZaiAuthProbeClassifier.classify(
                ZaiAuthProbeClassifier.parseEvalResult(view.evalSheetJs(authProbeJs)),
            )
            onSheetDiagnostics(
                ZaiSheetDiagnostics(view.url, sheetUa, ZaiAuthProbeClassifier.summary(verdict)),
            )
            if (verdict.signedIn) {
                verified = true
                CookieManager.getInstance().flush()
                onSignedIn()
                break
            }

            // 2. Login-form automation — only on chat.z.ai pages: never touch
            //    the Google account chooser the user might be working in.
            val sel = selectors ?: continue
            if (!(view.url ?: "").startsWith("https://chat.z.ai")) continue
            val st = parseSheetStateResult(
                view.evalSheetJs(ZaiProbeScripts.buildSheetProbeScript(sel)),
            ) ?: continue
            if (st.captchaPresent) captchaSeen = true
            if (st.loginFormPresent) {
                // The form is on screen — nothing to automate; re-arm the rounds.
                showHint = false
                autoRounds = 0
                continue
            }
            if (autoRounds < SHEET_AUTO_ROUNDS) {
                autoRounds++
                // Guest home: open the sidebar, then tap Sign in. Clicks are
                // best-effort — each reports {ok:false} when nothing matched.
                view.evalSheetJs(ZaiProbeScripts.buildClickVisibleScript(sel, "sidebarToggle"))
                delay(SHEET_AUTO_STEP_DELAY_MS)
                view.evalSheetJs(ZaiProbeScripts.buildClickVisibleScript(sel, "signInButton"))
                delay(SHEET_AUTO_SETTLE_MS)
            } else if (!showHint) {
                showHint = true
            }
        }
    }

    fun closeWithFinalProbe() {
        val view = webViewRef
        if (view == null || verified) {
            onClose()
            return
        }
        // X pressed: one last session probe so "logged in, then closed" is never lost.
        view.evaluateJavascript(authProbeJs) { result ->
            val verdict = ZaiAuthProbeClassifier.classify(
                ZaiAuthProbeClassifier.parseEvalResult(result),
            )
            onSheetDiagnostics(
                ZaiSheetDiagnostics(view.url, sheetUa, ZaiAuthProbeClassifier.summary(verdict)),
            )
            if (verdict.signedIn) {
                verified = true
                CookieManager.getInstance().flush()
                onSignedIn()
            } else {
                onClose()
            }
        }
    }

    Dialog(
        onDismissRequest = { closeWithFinalProbe() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { closeWithFinalProbe() }) {
                        Icon(Icons.Default.Close, contentDescription = "Close sign-in")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Sign in to Z.ai", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            when {
                                verified -> "Signed in — connecting…"
                                captchaSeen -> "Solve the puzzle below to continue"
                                else -> "Log in below — the form opens automatically"
                            },
                            fontSize = 11.sp,
                            color = if (verified) Color(0xFF2E9D72) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (verified) {
                        TextButton(onClick = onSignedIn) { Text("Continue") }
                    }
                }
                LinearProgressIndicator(
                    progress = { (progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (sheetUrl.isNotBlank()) {
                    Text(
                        sheetUrl,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                Box(Modifier.weight(1f)) {
                    AndroidViewWebView(
                        modifier = Modifier.fillMaxSize(),
                        onWebView = {
                            webViewRef = it
                            sheetUa = it.settings.userAgentString
                        },
                        onProgress = { progress = it },
                        onPageFinished = { url -> sheetUrl = url },
                        onVerify = {
                            // Fast path after every finished load (incl. the OAuth
                            // redirect back to chat.z.ai): probe immediately instead
                            // of waiting for the next poll tick.
                            val view = webViewRef
                            if (view != null && !verified) {
                                view.evaluateJavascript(authProbeJs) { result ->
                                    val verdict = ZaiAuthProbeClassifier.classify(
                                        ZaiAuthProbeClassifier.parseEvalResult(result),
                                    )
                                    onSheetDiagnostics(
                                        ZaiSheetDiagnostics(view.url, sheetUa, ZaiAuthProbeClassifier.summary(verdict)),
                                    )
                                    if (verdict.signedIn) {
                                        verified = true
                                        CookieManager.getInstance().flush()
                                        onSignedIn()
                                    }
                                }
                            }
                        },
                    )
                }
                if (showHint && !verified) {
                    Text(
                        "Can't see the sign-in form? In the page, tap the sidebar button (top corner), " +
                            "then tap \"Sign in\". If a puzzle appears, solve it right here.",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = PocketOrange,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "Your Z.ai session is stored only in this app's private browser storage and is never sent anywhere except chat.z.ai.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}

private const val SHEET_FALLBACK_AUTH_PROBE_PATH = "/api/v1/auths/"
private const val SHEET_POLL_INTERVAL_MS = 2_000L
private const val SHEET_AUTO_ROUNDS = 3
private const val SHEET_AUTO_STEP_DELAY_MS = 1_400L
private const val SHEET_AUTO_SETTLE_MS = 2_600L

private suspend fun WebView.evalSheetJs(script: String): String? =
    suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(script) { result -> cont.resume(result) }
        } catch (e: Throwable) {
            cont.resume(null)
        }
    }

private data class SheetPageState(
    val loginFormPresent: Boolean,
    val captchaPresent: Boolean,
    val sidebarFound: Boolean,
    val signInFound: Boolean,
    val url: String,
)

private fun parseSheetStateResult(raw: String?): SheetPageState? {
    val inner = raw?.sheetStatePayload() ?: return null
    return runCatching {
        val j = org.json.JSONObject(inner)
        SheetPageState(
            loginFormPresent = j.optBoolean("loginFormPresent"),
            captchaPresent = j.optBoolean("captchaPresent"),
            sidebarFound = j.optBoolean("sidebarFound"),
            signInFound = j.optBoolean("signInFound"),
            url = j.optString("url"),
        )
    }.getOrNull()
}

/** evaluateJavascript wraps a returned string in one level of JSON quoting. */
private fun String.sheetStatePayload(): String? = runCatching {
    org.json.JSONArray(this).optString(0)
}.getOrNull()

@Composable
private fun AndroidViewWebView(
    modifier: Modifier,
    onWebView: (WebView) -> Unit,
    onProgress: (Int) -> Unit,
    onPageFinished: (String) -> Unit,
    onVerify: () -> Unit,
) {
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Chrome-like UA for the sign-in sheet only: Google rejects OAuth
                // from UAs carrying the WebView markers (disallowed_useragent), and
                // chat.z.ai serves both UAs identically (live-probed 2026-10-18).
                // The hidden engine WebView keeps Android's default UA.
                runCatching { settings.userAgentString = ZaiSheetUserAgent.chromeLike(settings.userAgentString) }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgress(newProgress)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (url.isNullOrBlank()) return
                        onPageFinished(url)
                        // Probe on EVERY finished load — after the OAuth redirect
                        // back to chat.z.ai this is the fastest path to auto-close.
                        onVerify()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        // SPA route changes (sidebar → Sign in) never reload the
                        // document — track them so the debug line stays live.
                        if (url != null) onPageFinished(url)
                    }
                }
                loadUrl("https://chat.z.ai/auth")
                onWebView(this)
            }
        },
    )
}
