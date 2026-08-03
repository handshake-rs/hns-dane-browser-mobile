package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MobileWalletApprovalProjectionTest {
    @Test
    fun allTwelveAbiV2SummaryKindsHaveTypedFixedDisplays() {
        val fixtures = approvalFixtures()
        val prompts = fixtures.map { (request, candidate) ->
            MobileWalletApprovalProjection.validate(candidate, ORIGIN, request, NOW)
        }
        assertEquals(
            setOf(
                "permissions", "moduleEnablement", "send", "nameTransfer", "nameFinalize",
                "typedSignature", "nameMarketOffer", "nameMarketPurchase", "marketIntent",
                "fillAcceptance", "swapRedeem", "swapRefund",
            ),
            prompts.map { it.summary.kind }.toSet(),
        )
        prompts.forEach { prompt ->
            assertEquals(2, prompt.schemaVersion)
            val display = MobileWalletApprovalProjection.display(prompt)
            val expected = expectedDisplays.getValue(prompt.summary.kind)
            assertEquals(expected.first, display.title)
            assertEquals(expected.second, display.rows.map { it.label })
        }
    }

    @Test
    fun privateRawPromptMismatchAndFreeFormDisplayFailClosed() {
        val request = providerRequest(
            "asset_send",
            JSONObject().put("module", "ethereum"),
        )
        val valid = approvalFixtures().single { it.first.method == "asset_send" }.second

        expectCode("invalidApproval") {
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("authorityHandle", "private-native-handle"),
                ORIGIN,
                request,
                NOW,
            )
        }
        expectCode("invalidApproval") {
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("origin", "https://attacker.test"),
                ORIGIN,
                request,
                NOW,
            )
        }
        expectCode("invalidApproval") {
            val nonCanonicalOrigin = "https://Example.test/"
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("origin", nonCanonicalOrigin),
                nonCanonicalOrigin,
                request,
                NOW,
            )
        }
        expectCode("invalidApproval") {
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("expiresAtUnixMs", NOW + 90_001),
                ORIGIN,
                request,
                NOW,
            )
        }
        expectCode("invalidApproval") {
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("approvalId", "AAAAAAAAAAAAAAAAAAAAAA"),
                ORIGIN,
                request,
                NOW,
            )
        }
        expectCode("invalidApproval") {
            val candidate = JSONObject(valid.toString())
            candidate.getJSONObject("summary").put("display", "Approve whatever native says")
            MobileWalletApprovalProjection.validate(candidate, ORIGIN, request, NOW)
        }
        expectCode("invalidApproval") {
            val candidate = JSONObject(valid.toString())
            candidate.getJSONObject("summary")
                .getJSONObject("maximumFee")
                .put("asset", "BTC")
            MobileWalletApprovalProjection.validate(candidate, ORIGIN, request, NOW)
        }
    }

    @Test
    fun scalarAmountFinalityEnumAndFrameBoundsFailClosed() {
        val request = providerRequest("asset_send", JSONObject().put("module", "ethereum"))
        val valid = approvalFixtures().single { it.first.method == "asset_send" }.second

        fun reject(mutate: (JSONObject) -> Unit) {
            expectCode("invalidApproval") {
                val candidate = JSONObject(valid.toString())
                mutate(candidate)
                MobileWalletApprovalProjection.validate(candidate, ORIGIN, request, NOW)
            }
        }

        reject { it.put("schemaVersion", "2") }
        reject { it.put("approvalId", APPROVAL_ID.dropLast(1) + "B") }
        reject { it.put("expiresAtUnixMs", NOW) }
        reject { it.put("method", 7) }
        reject { it.put("origin", 7) }
        reject { it.getJSONObject("summary").getJSONObject("amount").put("baseUnits", 100) }
        reject { it.getJSONObject("summary").getJSONObject("amount").put("baseUnits", "01") }
        reject {
            it.getJSONObject("summary").getJSONObject("amount")
                .put("baseUnits", "340282366920938463463374607431768211456")
        }
        reject { it.getJSONObject("summary").put("finality", "proof_of_work_confirmations") }
        reject { it.getJSONObject("summary").put("chain", "solana") }
        reject {
            it.getJSONObject("summary").put(
                "warnings",
                array("settlementCanBeDelayed", "feeEstimateMayChange"),
            )
        }
        expectCode("approvalTooLarge") {
            MobileWalletApprovalProjection.validate(
                JSONObject(valid.toString()).put("padding", "x".repeat(17 * 1024)),
                ORIGIN,
                request,
                NOW,
            )
        }
    }

    private fun approvalFixtures(): List<Pair<WalletProviderRequest, JSONObject>> = listOf(
        fixture(
            providerRequest(
                "wallet_requestPermissions",
                JSONObject().put("capabilities", array("accounts", "send")),
            ),
            JSONObject().put("kind", "permissions").put("capabilities", array("accounts", "send")),
        ),
        fixture(
            providerRequest("wallet_enableModule", JSONObject().put("module", "bitcoin")),
            JSONObject()
                .put("kind", "moduleEnablement")
                .put("module", "bitcoin")
                .put("action", "enable"),
        ),
        fixture(
            providerRequest("asset_send", JSONObject().put("module", "ethereum")),
            JSONObject()
                .put("kind", "send")
                .put("amount", amount("ETH", "100"))
                .put("recipient", "0x1111111111111111111111111111111111111111")
                .put("maximumFee", amount("ETH", "3"))
                .put("chain", "ethereum")
                .put("finality", "ethereum_finalized_checkpoint")
                .put("warnings", array("feeEstimateMayChange")),
        ),
        fixture(
            providerRequest("hns_transferName"),
            JSONObject()
                .put("kind", "nameTransfer")
                .put("name", "example")
                .put("recipient", "hs1qrecipient")
                .put("maximumFee", amount("HNS", "1"))
                .put("warnings", array("nameTransferIsIrreversible")),
        ),
        fixture(
            providerRequest("hns_finalizeName"),
            JSONObject()
                .put("kind", "nameFinalize")
                .put("name", "example")
                .put("recipient", "hs1qrecipient")
                .put("maximumFee", amount("HNS", "1"))
                .put("warnings", JSONArray()),
        ),
        fixture(
            providerRequest("hns_signTypedMessage"),
            JSONObject()
                .put("kind", "typedSignature")
                .put("messageType", "hns-login-v1")
                .put("messageDigest", "0123456789abcdef"),
        ),
        fixture(
            providerRequest("nameMarket_createFixedPriceOffer"),
            JSONObject()
                .put("kind", "nameMarketOffer")
                .put("action", "create")
                .put("name", "example")
                .put("listingId", JSONObject.NULL)
                .put("price", amount("HNS", "500"))
                .put("maximumFee", amount("HNS", "2"))
                .put("warnings", JSONArray()),
        ),
        fixture(
            providerRequest("nameMarket_acceptOffer"),
            JSONObject()
                .put("kind", "nameMarketPurchase")
                .put("name", "example")
                .put("listingId", "listing-1")
                .put("payment", amount("HNS", "500"))
                .put("recipient", "hs1qseller")
                .put("maximumFee", amount("HNS", "2"))
                .put("warnings", array("settlementCanBeDelayed")),
        ),
        fixture(
            providerRequest("swap_publishMarketIntent"),
            JSONObject()
                .put("kind", "marketIntent")
                .put("action", "publish")
                .put("marketIntentId", JSONObject.NULL)
                .put("offered", amount("HNS", "100"))
                .put("requestedAsset", "BTC")
                .put("priceRound", "round-1")
                .put("maximumFee", amount("HNS", "1"))
                .put("warnings", JSONArray()),
        ),
        fixture(
            providerRequest("swap_acceptFill"),
            JSONObject()
                .put("kind", "fillAcceptance")
                .put("marketIntentId", "intent-1")
                .put("fillId", "fill-1")
                .put("offered", amount("HNS", "100"))
                .put("expected", amount("BTC", "10"))
                .put("priceRound", "round-1")
                .put("refundTimeoutUnixMs", NOW + 10_000)
                .put("maximumFee", amount("HNS", "1"))
                .put("warnings", array("refundRequiresManualAction")),
        ),
        fixture(
            providerRequest("swap_redeem"),
            JSONObject()
                .put("kind", "swapRedeem")
                .put("swapSessionId", "swap-1")
                .put("amount", amount("ETH", "50"))
                .put("recipient", "0x1111111111111111111111111111111111111111")
                .put("maximumFee", amount("ETH", "2"))
                .put("finality", "ethereum_finalized_checkpoint")
                .put("warnings", JSONArray()),
        ),
        fixture(
            providerRequest("swap_refund"),
            JSONObject()
                .put("kind", "swapRefund")
                .put("swapSessionId", "swap-1")
                .put("amount", amount("BTC", "50"))
                .put("recipient", "bc1qrefund")
                .put("maximumFee", amount("BTC", "2"))
                .put("refundAvailableAtUnixMs", NOW + 20_000)
                .put("warnings", array("refundRequiresManualAction")),
        ),
    )

    private fun fixture(
        request: WalletProviderRequest,
        summary: JSONObject,
    ): Pair<WalletProviderRequest, JSONObject> = request to JSONObject()
        .put("schemaVersion", 2)
        .put("approvalId", APPROVAL_ID)
        .put("method", request.method)
        .put("origin", ORIGIN)
        .put("expiresAtUnixMs", NOW + 30_000)
        .put("summary", summary)

    private fun providerRequest(
        method: String,
        params: Any? = JSONObject(),
    ): WalletProviderRequest = WalletProviderRequest("request-1", 1, method, params)

    private fun amount(asset: String, baseUnits: String): JSONObject =
        JSONObject().put("asset", asset).put("baseUnits", baseUnits)

    private fun array(vararg values: String): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    private fun expectCode(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (error: WalletProviderException) {
            assertEquals(code, error.code)
        }
    }

    private companion object {
        const val ORIGIN = "https://example.test"
        const val APPROVAL_ID = "AQEBAQEBAQEBAQEBAQEBAQ"
        const val NOW = 2_000_000_000_000L
        val expectedDisplays = mapOf(
            "permissions" to (
                "Approve wallet permissions" to listOf("Capabilities")
                ),
            "moduleEnablement" to (
                "Enable wallet module" to listOf("Module", "Action")
                ),
            "send" to (
                "Approve asset send" to
                    listOf("Amount", "Recipient", "Maximum fee", "Chain", "Finality", "Warnings")
                ),
            "nameTransfer" to (
                "Approve name transfer" to listOf("Name", "Recipient", "Maximum fee", "Warnings")
                ),
            "nameFinalize" to (
                "Approve name finalization" to listOf("Name", "Recipient", "Maximum fee")
                ),
            "typedSignature" to (
                "Approve typed signature" to listOf("Message type", "Message digest")
                ),
            "nameMarketOffer" to (
                "Approve name offer action" to
                    listOf("Action", "Name", "Price", "Maximum fee")
                ),
            "nameMarketPurchase" to (
                "Approve name purchase" to
                    listOf("Name", "Listing ID", "Payment", "Recipient", "Maximum fee", "Warnings")
                ),
            "marketIntent" to (
                "Approve market intent" to
                    listOf("Action", "Offered", "Requested asset", "Price round", "Maximum fee")
                ),
            "fillAcceptance" to (
                "Approve marketplace fill" to listOf(
                    "Market intent ID", "Fill ID", "Offered", "Expected", "Price round",
                    "Refund timeout", "Maximum fee", "Warnings",
                )
                ),
            "swapRedeem" to (
                "Approve swap redemption" to
                    listOf("Swap session ID", "Amount", "Recipient", "Maximum fee", "Finality")
                ),
            "swapRefund" to (
                "Approve swap refund" to listOf(
                    "Swap session ID", "Amount", "Recipient", "Maximum fee",
                    "Refund available at", "Warnings",
                )
                ),
        )
    }
}
