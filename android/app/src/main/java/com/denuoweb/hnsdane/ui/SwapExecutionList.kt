package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.wallet.NativeShakescapeExecutionSummary

internal const val SWAP_EXECUTION_PAGE_SIZE = 4

/** Hide only presentation rows. The native session, chain watches, and recovery stay intact. */
internal fun swapExecutionCanDismiss(
    execution: NativeShakescapeExecutionSummary,
    nowUnix: Long = System.currentTimeMillis() / 1_000L,
): Boolean {
    if (execution.state == "refunded" && execution.refundConfirmed) return true
    if (execution.state == "completed" && (
        (execution.firstRedemptionConfirmed && execution.secondRedemptionConfirmed) ||
            // Recovered sessions carry verified settlements on both chains,
            // but their synthetic summary has no funding/redemption flags.
            (execution.fundingDeadlineUnix == 0L &&
                execution.localFundingState == "submitted")
        )
    ) return true
    if (execution.fundingDeadlineUnix == 0L ||
        execution.localFundingState != null ||
        execution.firstFundingConfirmed || execution.secondFundingConfirmed ||
        execution.firstRedemptionConfirmed || execution.secondRedemptionConfirmed ||
        swapExecutionHasUnverifiedMakerFunding(execution, nowUnix)
    ) return false
    return execution.state in setOf(
        "terms_frozen", "refunds_prepared", "first_funding_pending", "failed",
    ) && !execution.refundConfirmed
}

internal fun orderedSwapExecutions(
    executions: List<NativeShakescapeExecutionSummary>,
): List<NativeShakescapeExecutionSummary> = executions.sortedWith(
    // The signed funding deadline tracks when a regular session was created.
    // Chain rescans refresh lastVerifiedAtUnix on an older open session, so
    // sorting by that field first would keep stale 1-of-5 rows above new swaps.
    compareByDescending<NativeShakescapeExecutionSummary> { it.fundingDeadlineUnix }
        .thenByDescending { it.lastVerifiedAtUnix }
        .thenByDescending { it.sessionId },
)

internal fun visibleSwapExecutions(
    executions: List<NativeShakescapeExecutionSummary>,
    dismissedIds: Set<String>,
): List<NativeShakescapeExecutionSummary> = orderedSwapExecutions(executions).filterNot {
    it.sessionId in dismissedIds && swapExecutionCanDismiss(it)
}

/** Keep a single funded failure visible for recovery as well as live sessions. */
internal fun singleActiveSwapExecution(
    executions: List<NativeShakescapeExecutionSummary>,
    dismissedIds: Set<String>,
): NativeShakescapeExecutionSummary? = visibleSwapExecutions(executions, dismissedIds)
    .filter {
        it.state !in setOf("completed", "refunded", "failed") ||
            (it.state == "failed" && !swapExecutionCanDismiss(it))
    }.singleOrNull()

internal data class SwapExecutionPage(
    val entries: List<NativeShakescapeExecutionSummary>,
    val index: Int,
    val count: Int,
    val total: Int,
)

internal fun swapExecutionPage(
    executions: List<NativeShakescapeExecutionSummary>,
    requestedIndex: Int,
): SwapExecutionPage {
    val count = ((executions.size + SWAP_EXECUTION_PAGE_SIZE - 1) /
        SWAP_EXECUTION_PAGE_SIZE).coerceAtLeast(1)
    val index = requestedIndex.coerceIn(0, count - 1)
    return SwapExecutionPage(
        entries = executions.drop(index * SWAP_EXECUTION_PAGE_SIZE)
            .take(SWAP_EXECUTION_PAGE_SIZE),
        index = index,
        count = count,
        total = executions.size,
    )
}
