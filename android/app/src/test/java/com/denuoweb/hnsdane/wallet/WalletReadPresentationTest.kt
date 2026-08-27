package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalletReadPresentationTest {
    @Test
    fun durableWalletNeverFallsBackToFirstRunWhenControllerWasRetired() {
        assertEquals(
            WalletDashboardMode.LockedWallet,
            walletDashboardMode(
                hasUnconfirmedRecovery = false,
                hasRetainedSynchronization = false,
                hasController = false,
                hasDurableWalletStorage = true,
                synchronizationInProgress = false,
                controllerUnlocked = false,
            ),
        )
        assertEquals(
            WalletDashboardMode.NoWallet,
            walletDashboardMode(
                hasUnconfirmedRecovery = false,
                hasRetainedSynchronization = false,
                hasController = false,
                hasDurableWalletStorage = false,
                synchronizationInProgress = false,
                controllerUnlocked = false,
            ),
        )
    }

    @Test
    fun hnsAmountsRemainExactWithoutFloatingPoint() {
        assertEquals("0", formatHnsBaseUnits("0"))
        assertEquals("1", formatHnsBaseUnits("1000000"))
        assertEquals("1.000001", formatHnsBaseUnits("1000001"))
        assertEquals("1.23", formatHnsBaseUnits("1230000"))
        assertEquals(
            "340282366920938463463374607431768.211455",
            formatHnsBaseUnits("340282366920938463463374607431768211455"),
        )
    }

    @Test
    fun hnsAmountParserAcceptsLeadingDecimalShorthandExactly() {
        assertEquals("100000", parsePositiveHnsToBaseUnits(".1"))
        assertEquals("10000", parsePositiveHnsToBaseUnits(".01"))
        assertEquals("1", parsePositiveHnsToBaseUnits(".000001"))
        assertNull(parsePositiveHnsToBaseUnits("."))
        assertNull(parsePositiveHnsToBaseUnits("-.1"))
        assertNull(parsePositiveHnsToBaseUnits("00.1"))
    }

    @Test
    fun nativeStatusCodesBecomePlainLabels() {
        assertEquals("canonical decoded", walletReadCodeLabel("canonicalDecoded"))
        assertEquals("watch only canonical state decoder unavailable", walletReadCodeLabel("watchOnlyCanonicalStateDecoderUnavailable"))
        assertEquals("wallet scan", walletReadCodeLabel("wallet_scan"))
    }

    @Test
    fun localTransactionStatesDoNotClaimRemoteMempoolAdmission() {
        assertEquals("pending (local wallet)", walletTransactionStatusLabel("mempool"))
        assertEquals("submitted to peers", walletTransactionStatusLabel("broadcast"))
        assertEquals("confirmed", walletTransactionStatusLabel("confirmed"))
        assertEquals("dropped", walletTransactionStatusLabel("dropped"))
    }

    @Test
    fun pendingOutgoingDoesNotDoubleSubtractNativeSpendableBalance() {
        val snapshot = walletSnapshot(
            balanceBaseUnits = "1000000",
            transactions = listOf(
                walletTransaction(status = "mempool", negative = true, magnitude = "100123"),
                walletTransaction(status = "mempool", negative = false, magnitude = "50000"),
                walletTransaction(status = "confirmed", negative = true, magnitude = "25000"),
                walletTransaction(status = "dropped", negative = true, magnitude = "9000"),
            ),
        )

        assertEquals(
            WalletHnsBalanceProjection(
                spendableBaseUnits = "1000000",
                pendingOutgoingBaseUnits = "100123",
            ),
            snapshot.hnsBalanceProjection(),
        )
    }

    @Test
    fun pendingOutgoingCanExceedSpendableWhenItsInputIsFullyReserved() {
        val projection = walletSnapshot(
            balanceBaseUnits = "100",
            transactions = listOf(
                walletTransaction(status = "broadcast", negative = true, magnitude = "101"),
            ),
        ).hnsBalanceProjection()

        assertEquals("100", projection.spendableBaseUnits)
        assertEquals("101", projection.pendingOutgoingBaseUnits)
    }

    @Test
    fun receiveCopyTargetsRemainRawAndSeparateFromPresentationText() {
        val snapshot = walletSnapshot(
            balanceBaseUnits = "0",
            transactions = emptyList(),
            nameReceiveTarget = NativeWalletNameReceiveTarget(
                accountId = "02".padEnd(32, '0'),
                display = "hs1qnameowner",
                derivationIndex = 9,
            ),
        )

        assertEquals(
            WalletHnsReceiveTargets(
                paymentAddress = "hs1qpayment",
                nameTransferAddress = "hs1qnameowner",
            ),
            snapshot.hnsReceiveTargets(),
        )
    }

    private fun walletSnapshot(
        balanceBaseUnits: String,
        transactions: List<NativeWalletTransaction>,
        nameReceiveTarget: NativeWalletNameReceiveTarget? = null,
    ) = NativeWalletReadSnapshot(
        balanceBaseUnits = balanceBaseUnits,
        paymentReceiveTarget = NativeWalletPaymentReceiveTarget(
            accountId = "01".padEnd(32, '0'),
            display = "hs1qpayment",
            derivationIndex = 0,
        ),
        nameReceiveTarget = nameReceiveTarget,
        height = 1,
        transactions = transactions,
        trackedNames = emptyList(),
    )

    private fun walletTransaction(
        status: String,
        negative: Boolean,
        magnitude: String,
    ) = NativeWalletTransaction(
        txid = "01".padEnd(64, '0'),
        status = status,
        negative = negative,
        magnitudeBaseUnits = magnitude,
        feeBaseUnits = null,
        blockHeight = null,
        firstSeenUnix = 1,
        confirmationCount = 0,
    )
}
