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

internal const val ATOMIC_SWAP_ACTION_CHANNEL_ID = "atomic_swap_actions_v1"

/** A funding alert is urgent only while this wallet can still submit its own lock. */
internal fun swapFundingActionRequired(
    execution: com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary,
    nowUnix: Long,
): Boolean {
    if (nowUnix >= execution.fundingDeadlineUnix ||
        execution.localFundingState in setOf("broadcast", "seen", "confirmed")
    ) return false
    return when (execution.localRole) {
        "maker" -> execution.state == "first_funding_pending" &&
            nowUnix <= execution.firstFundingCutoffUnix
        "taker" -> execution.state in setOf("first_funded", "second_funding_pending")
        else -> false
    }
}

/**
 * Publishes one durable, deduplicated notification for each meaningful atomic-swap stage.
 *
 * The native execution journal remains the authority. This class persists only the last
 * presentation fingerprint and funding-warning time for each random session ID, plus one-way
 * fingerprints for confirmed HNS receives; it stores no keys, raw transaction IDs or bytes,
 * addresses, names, amounts, or counterparty endpoints. The first observation establishes a
 * baseline so historical activity does not all alert at once. Every later new session, stage
 * transition, or confirmed incoming HNS payment is eligible for one notification.
 */
internal class AtomicSwapNotificationCoordinator(context: Context) {
    private val applicationContext = context.applicationContext
    private val manager = applicationContext.getSystemService(NotificationManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scheduledFundingWarnings = mutableMapOf<String, String>()

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
        manager.createNotificationChannel(
            NotificationChannel(
                ATOMIC_SWAP_ACTION_CHANNEL_ID,
                applicationContext.getString(R.string.wallet_swap_notification_action_required),
                NotificationManager.IMPORTANCE_HIGH,
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
            val deadline = pending.fundingDeadlineUnix
            val now = System.currentTimeMillis() / 1_000L
            val oneHourWarning = deadline != null && deadline > now &&
                deadline - now <= ONE_HOUR_SECONDS
            records[pending.sessionId] = SwapNotificationRecord(
                sessionId = pending.sessionId,
                fingerprint = "pending_acceptance:${deadline ?: 0L}:$oneHourWarning",
                title = applicationContext.getString(
                    if (oneHourWarning) R.string.wallet_swap_notification_action_required
                    else R.string.wallet_swap_notification_updated,
                ),
                text = if (deadline == null) {
                    applicationContext.getString(
                        R.string.wallet_swap_notification_negotiating,
                        pending.sessionId.take(12),
                    )
                } else {
                    applicationContext.getString(
                        R.string.wallet_swap_acceptance_sent,
                        pending.sessionId.take(12),
                        localDeadline(deadline),
                        remainingTime(deadline, now),
                    )
                },
                historicalTerminal = false,
                actionRequired = oneHourWarning,
            )
        }
        status.executions.forEach { execution ->
            val stage = stageText(execution)
            val milestone = notificationMilestone(execution)
            val actionRequired = notificationRequiresAction(execution, milestone)
            val firstObservationIsOfferAcceptance =
                execution.localRole == "taker" &&
                    execution.state !in TERMINAL_STATES &&
                    !actionRequired
            records[execution.sessionId] = SwapNotificationRecord(
                sessionId = execution.sessionId,
                // Do not include the native revision: confirmation-count and replay bookkeeping
                // can increment it without changing what either participant needs to do.
                // Countdown text changes each minute. Semantic buckets notify
                // once for first funding, the one-hour warning, expiry, and
                // each durable state transition without minute-by-minute spam.
                fingerprint = "${execution.state}:${execution.localRole}:$milestone",
                title = notificationTitle(execution, milestone),
                text = applicationContext.getString(
                    R.string.wallet_swap_notification_stage,
                    stage,
                    execution.sessionId.take(12),
                ),
                historicalTerminal = execution.state in TERMINAL_STATES,
                actionRequired = actionRequired,
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
        reconcileFundingWarnings(status)
        val fundingWarningNeedsPermission =
            hasOpenFundingDeadline(status) && !canPostNotifications()

        if (!preferences.getBoolean(INITIALIZED, false)) {
            val canNotify = canPostNotifications()
            var permissionNeeded = fundingWarningNeedsPermission
            preferences.edit().apply {
                records.values.filterNot { it.actionRequired }.forEach {
                    putString(sessionKey(it.sessionId), it.fingerprint)
                }
                putBoolean(INITIALIZED, true)
            }.apply()
            records.values.filter { it.actionRequired }.forEach { record ->
                if (!canNotify) {
                    permissionNeeded = true
                    return@forEach
                }
                manager.notify(
                    "atomic-swap:${record.sessionId}",
                    NOTIFICATION_ID,
                    notification(record),
                )
                preferences.edit()
                    .putString(sessionKey(record.sessionId), record.fingerprint)
                    .apply()
            }
            return permissionNeeded
        }

        val canNotify = canPostNotifications()
        var permissionNeeded = fundingWarningNeedsPermission
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

    private fun reconcileFundingWarnings(status: NativeShakescapeExecutionStatus) {
        val now = System.currentTimeMillis() / 1_000L
        val previouslyScheduled = preferences.getStringSet(FUNDING_WARNING_SESSIONS, emptySet())
            ?.toSet() ?: emptySet()
        val scheduled = linkedSetOf<String>()

        fun scheduleWarning(
            sessionId: String,
            fundingDeadlineUnix: Long,
            warningFingerprint: String,
            warningText: (String) -> String,
        ) {
            val warningAt = fundingDeadlineUnix - ONE_HOUR_SECONDS
            if (warningAt <= now) {
                AtomicSwapFundingWarningReceiver.cancel(applicationContext, sessionId)
                scheduledFundingWarnings.remove(sessionId)
                return
            }
            val deadline = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(runCatching {
                Math.multiplyExact(fundingDeadlineUnix, 1_000L)
            }.getOrElse { return }))
            // Keep the optimization in memory, not as durable authority.
            // Android cancels alarms when an app is force-stopped. A fresh
            // process must therefore reinstall every still-live warning even
            // when SharedPreferences remembers the prior session.
            if (scheduledFundingWarnings[sessionId] != warningFingerprint) {
                val warningScheduled = AtomicSwapFundingWarningReceiver.schedule(
                    applicationContext,
                    sessionId,
                    runCatching { Math.multiplyExact(warningAt, 1_000L) }
                        .getOrElse { return },
                    applicationContext.getString(
                        R.string.wallet_swap_notification_action_required,
                    ),
                    warningText(deadline),
                )
                if (warningScheduled) {
                    scheduledFundingWarnings[sessionId] = warningFingerprint
                }
            }
            scheduled += sessionId
        }

        status.pendingAcceptances.forEach { pending ->
            val fundingDeadlineUnix = pending.fundingDeadlineUnix ?: return@forEach
            scheduleWarning(
                sessionId = pending.sessionId,
                fundingDeadlineUnix = fundingDeadlineUnix,
                warningFingerprint = "$fundingDeadlineUnix:pending",
            ) { deadline ->
                applicationContext.getString(
                    R.string.wallet_swap_acceptance_sent,
                    pending.sessionId.take(12),
                    deadline,
                    applicationContext.getString(
                        R.string.wallet_swap_duration_hours_minutes,
                        1,
                        0,
                    ),
                )
            }
        }
        status.executions.forEach { execution ->
            if (!swapFundingActionRequired(execution, now)) return@forEach
            val target = if (execution.state in setOf("first_funded", "second_funding_pending")) {
                execution.secondChain
            } else {
                execution.firstChain
            }.let(::chainLabel)
            val warningFingerprint = "${execution.fundingDeadlineUnix}:$target"
            scheduleWarning(
                sessionId = execution.sessionId,
                fundingDeadlineUnix = execution.fundingDeadlineUnix,
                warningFingerprint = warningFingerprint,
            ) { deadline ->
                val warningStage = applicationContext.getString(
                    R.string.wallet_swap_stage_with_deadline,
                    applicationContext.getString(R.string.wallet_swap_notification_action_required),
                    applicationContext.getString(
                        R.string.wallet_swap_duration_hours_minutes,
                        1,
                        0,
                    ),
                    target,
                    deadline,
                )
                applicationContext.getString(
                    R.string.wallet_swap_notification_stage,
                    warningStage,
                    execution.sessionId.take(12),
                )
            }
        }
        (previouslyScheduled - scheduled).forEach { sessionId ->
            AtomicSwapFundingWarningReceiver.cancel(applicationContext, sessionId)
            scheduledFundingWarnings.remove(sessionId)
        }
        if (scheduled != previouslyScheduled) {
            preferences.edit().putStringSet(FUNDING_WARNING_SESSIONS, scheduled).apply()
        }
    }

    private fun chainLabel(chain: String): String = when (chain) {
        "bitcoin" -> "BTC"
        "handshake" -> "HNS"
        else -> chain.replaceFirstChar { it.uppercase() }
    }

    private fun hasOpenFundingDeadline(status: NativeShakescapeExecutionStatus): Boolean {
        val now = System.currentTimeMillis() / 1_000L
        return status.pendingAcceptances.any {
            it.fundingDeadlineUnix?.let { deadline -> deadline > now } == true
        } || status.executions.any {
            it.state in FUNDING_STATES && it.fundingDeadlineUnix > now
        }
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
        return Notification.Builder(
            applicationContext,
            if (record.actionRequired) ATOMIC_SWAP_ACTION_CHANNEL_ID else CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(record.title)
            .setContentText(record.text)
            .setStyle(Notification.BigTextStyle().bigText(record.text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(
                if (record.actionRequired) Notification.CATEGORY_REMINDER
                else Notification.CATEGORY_STATUS,
            )
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
    }

    private fun notificationMilestone(
        execution: com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary,
    ): String {
        val now = System.currentTimeMillis() / 1_000L
        val fundingState = execution.state in FUNDING_STATES
        return when {
            fundingState && now >= execution.fundingDeadlineUnix -> "funding_expired"
            swapFundingActionRequired(execution, now) &&
                execution.fundingDeadlineUnix - now <= ONE_HOUR_SECONDS ->
                "funding_one_hour"
            swapFundingActionRequired(execution, now) && execution.localRole == "taker" ->
                "second_funding_action"
            swapFundingActionRequired(execution, now) && execution.localRole == "maker" ->
                "first_funding_action"
            else -> execution.state
        }
    }

    private fun notificationTitle(
        execution: com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary,
        milestone: String,
    ): String = when {
        execution.state == "completed" -> applicationContext.getString(
            R.string.wallet_swap_notification_completed,
        )
        execution.state == "refunded" -> applicationContext.getString(
            R.string.wallet_swap_notification_refunded,
        )
        execution.state == "failed" -> applicationContext.getString(
            R.string.wallet_swap_notification_failed,
        )
        notificationRequiresAction(execution, milestone) ->
            applicationContext.getString(R.string.wallet_swap_notification_action_required)
        else -> applicationContext.getString(R.string.wallet_swap_notification_updated)
    }

    private fun notificationRequiresAction(
        execution: com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary,
        milestone: String,
    ): Boolean = execution.state == "refund_eligible" ||
        milestone in setOf(
            "funding_one_hour",
            "first_funding_action",
            "second_funding_action",
        ) || (execution.state == "both_funded" && execution.localRole == "maker") ||
        (execution.state in setOf("first_redeemed", "secret_observed") &&
            execution.localRole == "taker")

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun localDeadline(deadlineUnix: Long): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(Math.multiplyExact(deadlineUnix, 1_000L)))

    private fun remainingTime(deadlineUnix: Long, nowUnix: Long): String {
        val minutes = ((deadlineUnix - nowUnix).coerceAtLeast(0L) + 59L) / 60L
        val hours = minutes / 60L
        val remainingMinutes = minutes % 60L
        return if (hours > 0L) {
            applicationContext.getString(
                R.string.wallet_swap_duration_hours_minutes,
                hours,
                remainingMinutes,
            )
        } else {
            applicationContext.getString(
                R.string.wallet_swap_duration_minutes,
                remainingMinutes,
            )
        }
    }

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
        val actionRequired: Boolean = false,
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
        const val FUNDING_WARNING_SESSIONS = "funding_warning_sessions"
        const val NOTIFICATION_ID = 1
        const val ONE_HOUR_SECONDS = 60L * 60L
        val FUNDING_STATES = setOf(
            "terms_frozen",
            "refunds_prepared",
            "first_funding_pending",
            "first_funded",
            "second_funding_pending",
        )
        val TERMINAL_STATES = setOf("completed", "refunded", "failed")
    }
}
