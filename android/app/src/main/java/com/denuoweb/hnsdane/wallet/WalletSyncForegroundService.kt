package com.denuoweb.hnsdane.wallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.ui.WalletActivity

/**
 * Keeps a user-initiated, read-only direct HNS or Bitcoin wallet scan eligible to run
 * while the user briefly leaves the app. The service deliberately owns no
 * wallet key, controller, or signing capability; [WalletActivity] continues
 * to own the authenticated native operation and stops this service when the
 * scan is finished or fails closed.
 */
internal class WalletSyncForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 gives dataSync foreground services a finite budget. Stop
        // promptly when that budget expires rather than risking a process ANR.
        Log.w(TAG, "Wallet synchronization foreground-service time budget expired")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.wallet_sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun notification(): Notification {
        val openWallet = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WalletActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.wallet_sync_notification_title))
            .setContentText(getString(R.string.wallet_sync_notification_text))
            .setContentIntent(openWallet)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val TAG = "WalletSyncService"
        private const val ACTION_START = "com.denuoweb.hnsdane.wallet.START_SYNC"
        private const val ACTION_STOP = "com.denuoweb.hnsdane.wallet.STOP_SYNC"
        private const val CHANNEL_ID = "wallet-sync"
        private const val NOTIFICATION_ID = 3_014

        /**
         * This is called directly from the user-visible Wallet screen before
         * its direct peer scan begins, satisfying Android's foreground-service
         * start restrictions.
         */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WalletSyncForegroundService::class.java).setAction(ACTION_START),
            )
        }.onFailure { error ->
            Log.w(TAG, "Unable to start wallet synchronization foreground service", error)
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, WalletSyncForegroundService::class.java))
        }
    }
}

internal fun supportsTypedDataSyncForegroundService(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.Q
