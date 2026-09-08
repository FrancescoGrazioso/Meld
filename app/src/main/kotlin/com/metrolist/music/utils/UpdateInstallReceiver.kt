/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.Manifest
import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.metrolist.music.R

/**
 * Receives install session status from the system package installer.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_STATUS = "com.metrolist.music.action.INSTALL_STATUS"

        private const val UPDATES_CHANNEL_ID = "updates"
        private const val CONFIRM_NOTIFICATION_ID = 1002
        private const val CONFIRM_REQUEST_CODE = 1002

        /** Drops a confirmation prompt left over from an install that never finished. */
        internal fun clearConfirmation(context: Context) {
            NotificationManagerCompat.from(context).cancel(CONFIRM_NOTIFICATION_ID)
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> requestConfirmation(context, intent)

            PackageInstaller.STATUS_SUCCESS -> {
                clearConfirmation(context)
                UpdateInstaller.onInstallSucceeded(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                clearConfirmation(context)
                UpdateInstaller.reset()
            }

            else -> {
                clearConfirmation(context)
                UpdateInstaller.onInstallFailed(context)
            }
        }
    }

    private fun requestConfirmation(
        context: Context,
        intent: Intent,
    ) {
        val confirmation = intent.confirmationIntent() ?: return UpdateInstaller.onInstallFailed(context)
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        UpdateInstaller.onAwaitingConfirmation()

        // A receiver cannot launch an activity while the app sits in the background,
        // so the prompt is offered as a notification instead of being dropped.
        if (isAppInForeground(context)) {
            context.startActivity(confirmation)
        } else {
            notifyConfirmationReady(context, confirmation)
        }
    }

    private fun isAppInForeground(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        val ownPid = Process.myPid()
        return manager.runningAppProcesses?.any {
            it.pid == ownPid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }

    private fun notifyConfirmationReady(
        context: Context,
        confirmation: Intent,
    ) {
        val pending =
            PendingIntent.getActivity(
                context,
                CONFIRM_REQUEST_CODE,
                confirmation,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat
                .Builder(context, UPDATES_CHANNEL_ID)
                .setSmallIcon(R.drawable.update)
                .setContentTitle(context.getString(R.string.update_ready_to_install))
                .setContentText(context.getString(R.string.tap_to_install_update))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(CONFIRM_NOTIFICATION_ID, notification)
        }
    }

    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_INTENT)
        }
}
