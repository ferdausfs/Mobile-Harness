package com.jarves.mh

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarves.mh.ui.MainViewModel
import com.jarves.mh.ui.PocketDevApp
import com.jarves.mh.ui.theme.PocketTheme

class MainActivity : ComponentActivity() {

    /**
     * Startup crash-recovery state, computed synchronously BEFORE the first
     * frame so the gate can render instead of the UI that may crash again.
     * All reads are best-effort: a corrupt crash file or missing prefs must
     * never itself block the app from opening (v1.0.31).
     */
    private var recoveryAction: CrashRecovery.Action = CrashRecovery.Action.NONE
    private var recoveryReport: String? = null
    private var recoveryStartupCrashes: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Belt and braces: MHApp already installs the handler first thing in
        // the process; this call is idempotent and covers any launch path
        // where Application state was recreated.
        CrashGuard.install(this)
        computeRecoveryState()
        super.onCreate(savedInstanceState)
        askForNotificationsOnce()
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            PocketTheme(themeMode = state.themeMode) {
                if (recoveryAction == CrashRecovery.Action.NONE) {
                    PocketDevApp(vm)
                    // "The app opened" marker: stamped only after the MAIN UI's
                    // first composition completes (a composition crash before
                    // this point leaves the marker stale, which is exactly what
                    // makes the next launch count as a startup crash).
                    LaunchedEffect(Unit) { markOpened() }
                } else {
                    // The gate replaces the main UI until the user chooses.
                    // Continue re-renders this branch into the app; the open
                    // marker is stamped by THAT render, never by the gate.
                    var dismissed by remember { mutableStateOf(false) }
                    if (!dismissed) {
                        CrashRecoveryGate(
                            report = recoveryReport,
                            startupCrashes = recoveryStartupCrashes,
                            fullScreen = recoveryAction == CrashRecovery.Action.FULL_SCREEN,
                            onContinue = { dismissed = true },
                            onResetAppData = { resetAppDataAndRelaunch() },
                        )
                    } else {
                        PocketDevApp(vm)
                        LaunchedEffect(Unit) { markOpened() }
                    }
                }
            }
        }
    }

    /**
     * Reads the crash report + open markers and decides whether this launch
     * is gated by the recovery UI. Every step is guarded — this runs on the
     * path that may already be crashing, so it must be unable to make
     * anything worse.
     */
    private fun computeRecoveryState() {
        val prefs = runCatching {
            getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
        }.getOrNull()
        val lastOpenAt = prefs?.getLong(KEY_LAST_OPEN_AT, 0L) ?: 0L
        var startupCrashes = prefs?.getInt(KEY_STARTUP_CRASHES, 0) ?: 0
        val reportText = runCatching { CrashGuard.report(this) }.getOrNull()
        val crashAt = runCatching { CrashGuard.lastCrashAtMillis(reportText) }.getOrNull()
        val isNewerThanOpen = crashAt != null && crashAt > lastOpenAt + CrashRecovery.NEWER_THAN_SLACK_MILLIS
        if (isNewerThanOpen) {
            // This process is walking into a crash that happened after the
            // last good open: count it BEFORE the user does anything, so a
            // repeat tomorrow still escalates even if this launch survives.
            startupCrashes = CrashRecovery.countStartupCrash(startupCrashes)
            runCatching {
                prefs?.edit()?.putInt(KEY_STARTUP_CRASHES, startupCrashes)?.apply()
            }
        }
        recoveryAction = CrashRecovery.decide(
            CrashRecovery.State(
                startupCrashes = startupCrashes,
                lastCrashAtMillis = crashAt,
                lastOpenAtMillis = lastOpenAt,
            ),
        )
        recoveryReport = reportText
        recoveryStartupCrashes = startupCrashes
    }

    /** Stamps a successful open and clears the crash-loop counter. */
    private fun markOpened() {
        runCatching {
            getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_OPEN_AT, System.currentTimeMillis())
                .putInt(KEY_STARTUP_CRASHES, CrashRecovery.resetStartupCrashes())
                .apply()
        }
    }

    /**
     * Full app-data reset, reachable ONLY from the recovery gate: clears every
     * SharedPreferences file, deletes everything in filesDir (the crash report
     * survives so it can still be copied), clears caches + web data, then
     * relaunches the process via a 150 ms alarm and exits. Every step is
     * guarded; the reset must never hang or crash the recovery path itself.
     */
    private fun resetAppDataAndRelaunch() {
        runCatching {
            listOf(
                RECOVERY_PREFS,
                "pocket_preferences",
                "pocket_secrets",
                "mh_tokens",
                "mh_runtime",
                "zweb_diagnostics",
            ).forEach { name ->
                runCatching {
                    getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                }
            }
        }
        runCatching {
            filesDir.listFiles()?.forEach { child ->
                if (child.name == "crash") return@forEach
                runCatching {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
        runCatching { cacheDir.deleteRecursively() }
        runCatching { externalCacheDir?.let { dir -> dir.deleteRecursively() } }
        runCatching {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
        }
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            if (launch != null) {
                val pending = PendingIntent.getActivity(
                    this,
                    9001,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val alarms = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarms?.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 150,
                    pending,
                )
            }
        }
        // The alarm relaunches; ending this process makes the fresh start real.
        runCatching { Runtime.getRuntime().exit(0) }
        // exit() never returns — these are unreachable last-resort fallbacks.
        runCatching { finishAffinity() }
    }

    /**
     * POST_NOTIFICATIONS (Android 13+) is requested exactly ONCE, at the
     * first cold start — a sensible first-use moment, never mid-task. The
     * rotation reminder and task-result notifications need it to be visible.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("mh_runtime", MODE_PRIVATE)
        if (prefs.getBoolean("notif_permission_asked", false)) return
        prefs.edit().putBoolean("notif_permission_asked", true).apply()
        runCatching { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001) }
    }

    private companion object {
        const val RECOVERY_PREFS = "mh_recovery"
        const val KEY_LAST_OPEN_AT = "last_open_at_millis"
        const val KEY_STARTUP_CRASHES = "startup_crashes"
    }
}
