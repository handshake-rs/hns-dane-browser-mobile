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
                "typedSignature", "nameMarketOffer", "nameMarketPurchase", "directOffer",
                "directOfferTake", "swapRedeem", "swapRefund",
            ),
            prompts.map { it.summary.kind }.toSet(),
        )
        prompts.forEach { prompt ->
            assertEquals(3, prompt.schemaVersion)
            val display = MobileWalletApprovalProjection.display(prompt)
            val expected = expectedDisplays.getValue(prompt.summary.kind)
            assertEquals(expected.first, display.title)
            assertEquals(expected.second, display.rows.map { it.label })
        }
    }

    @Test
    fun schemaThreeHnsNameDisclosuresValidateAndRenderEveryExactPair() {
        val request = providerRequest(
            "wallet_requestPermissions",
            JSONObject().put("capabilities", array("names")),
        )
        val candidate = fixture(
            request,
            JSONObject()
                .put("kind", "permissions")
                .put("capabilities", array("names"))
                .put(
                    "hnsNames",
                    hnsNames(
                        "alpha" to ALPHA_HASH,
                        "alpha_beta" to ALPHA_BETA_HASH,
                    ),
                ),
        ).second

        val prompt = MobileWalletApprovalProjection.validate(candidate, ORIGIN, request, NOW)
        val summary = prompt.summary as WalletApprovalSummary.Permissions
        assertEquals(
            listOf(
                WalletHnsNameDisclosure("alpha", ALPHA_HASH),
                WalletHnsNameDisclosure("alpha_beta", ALPHA_BETA_HASH),
            ),
            summary.hnsNames,
        )
        val display = MobileWalletApprovalProjection.display(prompt)
        assertEquals(
            listOf("Capabilities", "HNS name 1", "HNS name hash 1", "HNS name 2", "HNS name hash 2"),
            display.rows.map { it.label },
        )
        assertEquals(
            listOf("names", "alpha", ALPHA_HASH, "alpha_beta", ALPHA_BETA_HASH),
            display.rows.map { it.value },
        )

        val accountsRequest = providerRequest("hns_requestAccounts")
        val accountsPrompt = MobileWalletApprovalProjection.validate(
            fixture(
                accountsRequest,
                permissionSummary(array("accounts"), JSONArray()),
            ).second,
            ORIGIN,
            accountsRequest,
            NOW,
        )
        assertEquals(
            emptyList<WalletHnsNameDisclosure>(),
            (accountsPrompt.summary as WalletApprovalSummary.Permissions).hnsNames,
        )
    }

    @Test
    fun schemaThreeHnsNameDisclosuresFailClosed() {
        fun reject(
            method: String = "wallet_requestPermissions",
            requested: JSONArray = array("names"),
            summary: JSONObject,
        ) {
            val params = if (method == "hns_requestAccounts") {
                JSONObject()
            } else {
                JSONObject().put("capabilities", requested)
            }
            val request = providerRequest(method, params)
            expectCode("invalidApproval") {
                MobileWalletApprovalProjection.validate(
                    fixture(request, summary).second,
                    ORIGIN,
                    request,
                    NOW,
                )
            }
        }

        reject(
            summary = JSONObject()
                .put("kind", "permissions")
                .put("capabilities", array("names")),
        )
        reject(
            requested = array("balance"),
            summary = permissionSummary(array("balance"), hnsNames("alpha" to ALPHA_HASH)),
        )
        reject(
            requested = array("accounts"),
            summary = permissionSummary(array("accounts"), JSONArray()),
        )
        reject(
            method = "hns_requestAccounts",
            requested = array("accounts"),
            summary = permissionSummary(array("accounts"), hnsNames("alpha" to ALPHA_HASH)),
        )
        reject(
            summary = permissionSummary(
                array("names"),
                hnsNames("beta" to BETA_HASH, "alpha" to ALPHA_HASH),
            ),
        )
        reject(
            summary = permissionSummary(
                array("names"),
                hnsNames("alpha" to ALPHA_HASH, "alpha" to ALPHA_HASH),
            ),
        )
        reject(
            summary = permissionSummary(
                array("names"),
                JSONArray().apply { repeat(65) { put(JSONObject()) } },
            ),
        )

        for (name in listOf("Alpha", "-alpha", "alpha-", "_alpha", "alpha_", "a.b", "example", "a".repeat(64))) {
            reject(
                summary = permissionSummary(
                    array("names"),
                    hnsNames(name to "0".repeat(64)),
                ),
            )
        }
        for (hash in listOf(ALPHA_HASH.uppercase(), "0".repeat(63), "0".repeat(64))) {
            reject(
                summary = permissionSummary(array("names"), hnsNames("alpha" to hash)),
            )
        }
        reject(
            summary = permissionSummary(
                array("names"),
                JSONArray().put(
                    JSONObject()
                        .put("name", "alpha")
                        .put("nameHash", ALPHA_HASH)
                        .put("display", "trust me"),
                ),
            ),
        )
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

        reject { it.put("schemaVersion", 2) }
        reject { it.put("schemaVersion", "3") }
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
                JSONObject().put("capabilities", array("balance", "send")),
            ),
            permissionSummary(array("balance", "send"), JSONArray()),
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
            providerRequest("swap_publishDirectOffer"),
            JSONObject()
                .put("kind", "directOffer")
                .put("action", "publish")
                .put("directOfferId", JSONObject.NULL)
                .put("offered", amount("HNS", "100"))
                .put("received", amount("BTC", "10"))
                .put("maximumFee", amount("HNS", "1"))
                .put("warnings", JSONArray()),
        ),
        fixture(
            providerRequest("swap_acceptDirectOffer"),
            JSONObject()
                .put("kind", "directOfferTake")
                .put("directOfferId", "offer-1")
                .put("swapSessionId", "session-1")
                .put("offered", amount("HNS", "100"))
                .put("received", amount("BTC", "10"))
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
        .put("schemaVersion", 3)
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

    private fun hnsNames(vararg values: Pair<String, String>): JSONArray = JSONArray().apply {
        values.forEach { (name, nameHash) ->
            put(JSONObject().put("name", name).put("nameHash", nameHash))
        }
    }

    private fun permissionSummary(capabilities: JSONArray, hnsNames: JSONArray): JSONObject =
        JSONObject()
            .put("kind", "permissions")
            .put("capabilities", capabilities)
            .put("hnsNames", hnsNames)

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
        const val ALPHA_HASH = "271878f8a927b4566ac951fc815b18dfad8d0302d61d11d80cbe15b7a3a056af"
        const val ALPHA_BETA_HASH = "e91efa3d4629261bc45787aca2461e087ea169c044fc711108b89d44d75f26ce"
        const val BETA_HASH = "f0277d92062bd9a41dd26cddbaf2c41d576cf7b0173cbe96c23d5f5a4f92cc8f"
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
            "directOffer" to (
                "Approve direct offer" to
                    listOf("Action", "Offered", "Received", "Maximum fee")
                ),
            "directOfferTake" to (
                "Approve direct-offer take" to listOf(
                    "Direct offer ID", "Swap session ID", "Offered", "Received",
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
