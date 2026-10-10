package com.denuoweb.hnsdane.ui

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.denuoweb.hnsdane.R

/**
 * Delivers the deadline-minus-one-hour warning even when the wallet Activity
 * is backgrounded. It owns presentation data only: no wallet handle, key,
 * transaction, address, amount, or peer endpoint is placed in the alarm.
 */
internal class AtomicSwapFundingWarningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WARNING || !canPostNotifications(context)) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
            ?.takeIf { value ->
                value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
            } ?: return
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf(String::isNotBlank) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf(String::isNotBlank) ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureChannel(context, manager)
        val openWallet = PendingIntent.getActivity(
            context,
            sessionId.hashCode() and Int.MAX_VALUE,
            Intent(context, WalletActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            "atomic-swap:$sessionId",
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openWallet)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build(),
        )
    }

    companion object {
        private const val ACTION_WARNING =
            "com.denuoweb.hnsdane.ui.ATOMIC_SWAP_FUNDING_WARNING"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val CHANNEL_ID = ATOMIC_SWAP_ACTION_CHANNEL_ID
        private const val NOTIFICATION_ID = 1

        fun schedule(
            context: Context,
            sessionId: String,
            triggerAtMillis: Long,
            title: String,
            text: String,
        ): Boolean = runCatching {
            val manager = context.getSystemService(AlarmManager::class.java)
            val alarm = alarmIntent(context, sessionId, title, text)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, alarm)
        }.isSuccess

        fun cancel(context: Context, sessionId: String) {
            runCatching {
                val manager = context.getSystemService(AlarmManager::class.java)
                val alarm = alarmIntent(context, sessionId, "", "")
                manager.cancel(alarm)
                alarm.cancel()
            }
        }

        private fun alarmIntent(
            context: Context,
            sessionId: String,
            title: String,
            text: String,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            sessionId.hashCode() and Int.MAX_VALUE,
            Intent(context, AtomicSwapFundingWarningReceiver::class.java)
                .setAction(ACTION_WARNING)
                .setData(Uri.parse("hnsdane://atomic-swap-warning/$sessionId"))
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun ensureChannel(context: Context, manager: NotificationManager) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.wallet_swap_notification_action_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(
                        R.string.wallet_swap_notification_action_channel_description,
                    )
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }

        private fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
