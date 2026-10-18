package com.jarves.mh.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.jarves.mh.update.InstallDiagnostics
import com.jarves.mh.update.InstallDiagnostics.InstallResultRecord
import com.jarves.mh.update.InstallResultStore

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // A throw here shows the system crash dialog over the installer — the
        // exact "keeps stopping" report. Never let anything escape.
        runCatching { handle(context, intent) }
    }

    private fun handle(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val userAction = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            userAction?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (userAction != null) {
                runCatching { context.startActivity(userAction) }
            }
            return
        }
        recordResult(context, intent, status)
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
            val message = InstallDiagnostics.friendlyInstallFailure(status, raw)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                    context, 0, null, null, null,
                )
            }.onFailure {
                Toast.makeText(context, "Installed $packageName. Open it from your launcher.", Toast.LENGTH_LONG).show()
            }
            return
        }
        context.packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
        }
    }

    /**
     * Persist the exact session outcome so Settings → Install diagnostics can
     * show the numeric EXTRA_STATUS and the raw EXTRA_STATUS_MESSAGE instead
     * of a dead-end "App not installed".
     */
    private fun recordResult(context: Context, intent: Intent, status: Int) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        val other = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME).orEmpty()
        }
        val apkName = intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_NAME).orEmpty()
        val apkSize = intent.getLongExtra(AndroidAppInstaller.EXTRA_APK_SIZE, -1L)
        InstallResultStore.save(
            context,
            InstallResultRecord(
                atMillis = System.currentTimeMillis(),
                sessionId = sessionId,
                statusCode = status,
                statusMessage = raw,
                apkName = apkName,
                apkSizeBytes = apkSize,
                otherPackageName = other,
            ),
        )
    }
}
