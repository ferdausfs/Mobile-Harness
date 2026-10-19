package com.jarves.mh

import android.app.Application

/**
 * The app's Application class (v1.0.31). Exists for exactly one job: install
 * the crash guard as the FIRST thing the process runs — before any Activity,
 * ViewModel, or first-frame composition.
 *
 * Why: until v1.0.31 the uncaught-exception handler was installed in
 * MainActivity.onCreate. A crash that looped at launch could never be read by
 * the user (the report was on disk; the only reader was the Settings screen
 * the app could never reach), and anything crashing before the Activity was
 * never captured at all. Installing here means every crash — including the
 * launcher-loop case — lands in filesDir/crash/last-crash.txt, where the
 * startup recovery gate (MainActivity + [CrashRecovery]) surfaces it with a
 * Copy button and a Reset-app-data escape hatch.
 *
 * Deliberately NOTHING else happens here — no SDK init, no file I/O beyond
 * the handler install — so this class cannot itself become the startup
 * problem it exists to solve.
 */
class MHApp : Application() {

    override fun onCreate() {
        // First statement of the process's app code: capture every uncaught
        // exception from now on. Never throws (internally guarded).
        runCatching { CrashGuard.install(this) }
        super.onCreate()
    }
}
