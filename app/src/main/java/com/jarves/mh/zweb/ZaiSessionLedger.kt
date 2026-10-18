package com.jarves.mh.zweb

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the last connection-check outcome across app runs, so the
 * Diagnostics report can show whether a signed-in session survived a process
 * restart. This is the on-device answer to the session-persistence question:
 * chat.z.ai's `token` cookie is session-scoped (server-verified), while the
 * site's own session token in localStorage is what should persist.
 *
 * Stores outcome labels and a signed-in tri-state only — never tokens,
 * emails, or cookie values.
 */
class ZaiSessionLedger(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Snapshot of the previous run; read BEFORE this run's first [record]. */
    fun readPreviousRun(): Pair<String?, Boolean?> = runCatching {
        val outcome = prefs.getString(KEY_OUTCOME, null)?.takeIf(String::isNotBlank)
        val signedIn = when (prefs.getString(KEY_SIGNED_IN, null)) {
            VALUE_YES -> true
            VALUE_NO -> false
            else -> null
        }
        outcome to signedIn
    }.getOrDefault(null to null)

    fun record(outcome: String?, signedIn: Boolean?) {
        runCatching {
            prefs.edit()
                .putString(KEY_OUTCOME, outcome?.take(160))
                .putString(
                    KEY_SIGNED_IN,
                    when (signedIn) {
                        true -> VALUE_YES
                        false -> VALUE_NO
                        null -> VALUE_UNKNOWN
                    },
                )
                .apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "zweb_diagnostics"
        const val KEY_OUTCOME = "last_check_outcome"
        const val KEY_SIGNED_IN = "last_signed_in"
        const val VALUE_YES = "yes"
        const val VALUE_NO = "no"
        const val VALUE_UNKNOWN = "unknown"
    }
}
