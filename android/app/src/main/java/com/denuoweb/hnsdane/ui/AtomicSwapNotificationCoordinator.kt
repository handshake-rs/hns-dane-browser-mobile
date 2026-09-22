package com.denuoweb.hnsdane.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionStatus

/**
 * Publishes one durable, deduplicated notification for each meaningful atomic-swap stage.
 *
 * The native execution journal remains the authority. This class persists only the last
 * presentation fingerprint for each random session ID; it stores no keys, transaction bytes,
 * addresses, amounts, or counterparty endpoints. The first observation after this feature is
 * installed establishes a baseline so historical or abandoned executions do not all alert at
 * once. Every later new session and stage transition is eligible for one notification.
 */
internal class AtomicSwapNotificationCoordinator(context: Context) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(NotificationManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.wallet_swap_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = applicationContext.getString(
                    R.string.wallet_swap_notification_channel_description,
                )
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    /**
     * Reconcile the latest authenticated execution projection. Returns true only when Android
     * notification permission is the remaining reason a new stage could not be delivered.
     */
    fun reconcile(
        status: NativeShakescapeExecutionStatus,
        stageText: (com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary) -> String,
    ): Boolean {
        val records = LinkedHashMap<String, SwapNotificationRecord>()
        status.pendingAcceptances.forEach { pending ->
            records[pending.sessionId] = SwapNotificationRecord(
                sessionId = pending.sessionId,
                fingerprint = "pending_acceptance",
                title = applicationContext.getString(R.string.wallet_swap_notification_updated),
                text = applicationContext.getString(
                    R.string.wallet_swap_notification_negotiating,
                    pending.sessionId.take(12),
                ),
                historicalTerminal = false,
            )
        }
        status.executions.forEach { execution ->
            val stage = stageText(execution)
            records[execution.sessionId] = SwapNotificationRecord(
                sessionId = execution.sessionId,
                // Do not include the native revision: confirmation-count and replay bookkeeping
                // can increment it without changing what either participant needs to do.
                fingerprint = "${execution.state}:${execution.localRole}:$stage",
                title = notificationTitle(execution.state, stage),
                text = applicationContext.getString(
                    R.string.wallet_swap_notification_stage,
                    stage,
                    execution.sessionId.take(12),
                ),
                historicalTerminal = execution.state in TERMINAL_STATES,
            )
        }

        if (!preferences.getBoolean(INITIALIZED, false)) {
            preferences.edit().apply {
                records.values.forEach { putString(sessionKey(it.sessionId), it.fingerprint) }
                putBoolean(INITIALIZED, true)
            }.apply()
            return false
        }

        val canNotify = canPostNotifications()
        var permissionNeeded = false
        records.values.forEach { record ->
            val key = sessionKey(record.sessionId)
            val previous = preferences.getString(key, null)
            if (previous == record.fingerprint) return@forEach
            // A terminal execution first discovered after preferences were pruned or a wallet was
            // restored is history, not a fresh event. A terminal transition is announced only
            // when this installation already observed the session in a nonterminal stage.
            if (previous == null && record.historicalTerminal) {
                preferences.edit().putString(key, record.fingerprint).apply()
                return@forEach
            }
            if (!canNotify) {
                permissionNeeded = true
                return@forEach
            }
            manager.notify(
                "atomic-swap:${record.sessionId}",
                NOTIFICATION_ID,
                notification(record),
            )
            preferences.edit().putString(key, record.fingerprint).apply()
        }
        return permissionNeeded
    }

    fun notificationsAllowed(): Boolean = canPostNotifications()

    fun shouldRequestPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !canPostNotifications() &&
            !preferences.getBoolean(PERMISSION_REQUESTED, false)

    fun markPermissionRequested() {
        preferences.edit().putBoolean(PERMISSION_REQUESTED, true).apply()
    }

    private fun notification(record: SwapNotificationRecord): Notification {
        val requestCode = record.sessionId.hashCode() and Int.MAX_VALUE
        val intent = Intent(applicationContext, WalletActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(record.title)
            .setContentText(record.text)
            .setStyle(Notification.BigTextStyle().bigText(record.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun notificationTitle(state: String, stage: String): String = when {
        state == "completed" -> applicationContext.getString(
            R.string.wallet_swap_notification_completed,
        )
        state == "refunded" -> applicationContext.getString(
            R.string.wallet_swap_notification_refunded,
        )
        state == "failed" -> applicationContext.getString(
            R.string.wallet_swap_notification_failed,
        )
        state == "refund_eligible" ||
            stage.contains("ready for approval", ignoreCase = true) ||
            stage.contains("redeem", ignoreCase = true) ||
            stage.contains("refund is eligible", ignoreCase = true) ->
            applicationContext.getString(R.string.wallet_swap_notification_action_required)
        else -> applicationContext.getString(R.string.wallet_swap_notification_updated)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun sessionKey(sessionId: String): String = "session:$sessionId"

    private data class SwapNotificationRecord(
        val sessionId: String,
        val fingerprint: String,
        val title: String,
        val text: String,
        val historicalTerminal: Boolean,
    )

    private companion object {
        const val CHANNEL_ID = "atomic_swaps"
        const val PREFERENCES = "atomic_swap_notifications_v1"
        const val INITIALIZED = "initialized"
        const val PERMISSION_REQUESTED = "permission_requested"
        const val NOTIFICATION_ID = 1
        val TERMINAL_STATES = setOf("completed", "refunded", "failed")
    }
}
