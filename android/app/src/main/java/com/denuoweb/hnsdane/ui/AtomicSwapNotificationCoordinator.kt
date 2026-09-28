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
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import java.security.MessageDigest

/**
 * Publishes one durable, deduplicated notification for each meaningful atomic-swap stage.
 *
 * The native execution journal remains the authority. This class persists only the last
 * presentation fingerprint for each random session ID and one-way fingerprints for confirmed
 * HNS receives; it stores no keys, raw transaction IDs or bytes, addresses, names, amounts, or
 * counterparty endpoints. The first observation after this feature is installed establishes a
 * baseline so historical activity does not all alert at once. Every later new session, stage
 * transition, or confirmed incoming HNS payment is eligible for one notification.
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
        status.pendingOfferResponses.forEach { offer ->
            records[offer.sessionId] = SwapNotificationRecord(
                sessionId = offer.sessionId,
                fingerprint = "offer_response",
                title = applicationContext.getString(
                    R.string.wallet_swap_notification_offer_accepted,
                ),
                text = applicationContext.getString(
                    R.string.wallet_swap_notification_offer_accepted_detail,
                    offer.sessionId.take(12),
                ),
                historicalTerminal = false,
            )
        }
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
            val firstObservationIsOfferAcceptance =
                execution.localRole == "taker" && execution.state !in TERMINAL_STATES
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
                initialTitle = if (firstObservationIsOfferAcceptance) {
                    applicationContext.getString(R.string.wallet_swap_notification_offer_accepted)
                } else {
                    null
                },
                initialText = if (firstObservationIsOfferAcceptance) {
                    applicationContext.getString(
                        R.string.wallet_swap_notification_offer_accepted_detail,
                        execution.sessionId.take(12),
                    )
                } else {
                    null
                },
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
            val presentation = if (
                previous == null && record.initialTitle != null && record.initialText != null
            ) {
                record.copy(title = record.initialTitle, text = record.initialText)
            } else {
                record
            }
            manager.notify(
                "atomic-swap:${record.sessionId}",
                NOTIFICATION_ID,
                notification(presentation),
            )
            preferences.edit().putString(key, record.fingerprint).apply()
        }
        return permissionNeeded
    }

    /**
     * Announce newly confirmed incoming HNS from the ordinary verified wallet snapshot. This is
     * also the fixed-price name-sale completion signal: that protocol has no interactive peer
     * acceptance packet, so the confirmed chain payment is the first authoritative event.
     *
     * A per-wallet baseline suppresses restore/history floods. Preferences retain only SHA-256
     * fingerprints of account and transaction identifiers, never amounts, addresses, or names.
     */
    fun reconcileIncomingHns(
        snapshot: NativeWalletReadSnapshot,
        amountText: (String) -> String,
    ): Boolean {
        val accountFingerprint = fingerprint("account:${snapshot.paymentReceiveTarget.accountId}")
        val incoming = snapshot.transactions.filter { transaction ->
            transaction.status == "confirmed" &&
                transaction.confirmationCount > 0L &&
                !transaction.negative &&
                transaction.magnitudeBaseUnits != "0"
        }
        val currentFingerprints = incoming
            .mapTo(linkedSetOf()) { transaction -> fingerprint("hns:${transaction.txid}") }
        if (
            !preferences.getBoolean(HNS_RECEIVE_INITIALIZED, false) ||
                preferences.getString(HNS_RECEIVE_ACCOUNT, null) != accountFingerprint
        ) {
            preferences.edit()
                .putBoolean(HNS_RECEIVE_INITIALIZED, true)
                .putString(HNS_RECEIVE_ACCOUNT, accountFingerprint)
                .putStringSet(HNS_RECEIVE_FINGERPRINTS, currentFingerprints)
                .apply()
            return false
        }

        val known = preferences.getStringSet(HNS_RECEIVE_FINGERPRINTS, emptySet())
            ?.toSet() ?: emptySet()
        val canNotify = canPostNotifications()
        var permissionNeeded = false
        incoming.forEach { transaction ->
            val transactionFingerprint = fingerprint("hns:${transaction.txid}")
            if (transactionFingerprint in known) return@forEach
            if (!canNotify) {
                permissionNeeded = true
                return@forEach
            }
            val record = SwapNotificationRecord(
                sessionId = "hns:$transactionFingerprint",
                fingerprint = "confirmed",
                title = applicationContext.getString(R.string.wallet_hns_received_notification_title),
                text = applicationContext.getString(
                    R.string.wallet_hns_received_notification_detail,
                    amountText(transaction.magnitudeBaseUnits),
                ),
                historicalTerminal = false,
            )
            manager.notify(
                "hns-receive:$transactionFingerprint",
                NOTIFICATION_ID,
                notification(record),
            )
        }
        // The authenticated snapshot is bounded. Replacing the set instead of
        // accumulating every historical tx keeps unencrypted presentation
        // metadata bounded as well. Do not advance it while permission is
        // unavailable; a later grant should still announce the new receipts.
        if (canNotify && known != currentFingerprints) {
            preferences.edit()
                .putStringSet(HNS_RECEIVE_FINGERPRINTS, currentFingerprints)
                .apply()
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

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class SwapNotificationRecord(
        val sessionId: String,
        val fingerprint: String,
        val title: String,
        val text: String,
        val historicalTerminal: Boolean,
        val initialTitle: String? = null,
        val initialText: String? = null,
    )

    private companion object {
        const val CHANNEL_ID = "atomic_swaps"
        const val PREFERENCES = "atomic_swap_notifications_v1"
        const val INITIALIZED = "initialized"
        const val PERMISSION_REQUESTED = "permission_requested"
        const val HNS_RECEIVE_INITIALIZED = "hns_receive_initialized"
        const val HNS_RECEIVE_ACCOUNT = "hns_receive_account"
        const val HNS_RECEIVE_FINGERPRINTS = "hns_receive_fingerprints"
        const val NOTIFICATION_ID = 1
        val TERMINAL_STATES = setOf("completed", "refunded", "failed")
    }
}
