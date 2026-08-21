package com.denuoweb.hnsdane.wallet

import java.math.BigInteger
import java.net.URI
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class WalletApprovalAmount(
    val asset: String,
    val baseUnits: String,
)

internal data class WalletHnsNameDisclosure(
    val name: String,
    val nameHash: String,
)

internal sealed class WalletApprovalSummary(val kind: String) {
    data class Permissions(
        val capabilities: List<String>,
        val hnsNames: List<WalletHnsNameDisclosure>,
    ) : WalletApprovalSummary("permissions")

    data class ModuleEnablement(
        val module: String,
        val action: String,
    ) : WalletApprovalSummary("moduleEnablement")

    data class Send(
        val amount: WalletApprovalAmount,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val chain: String,
        val finality: String,
        val warnings: List<String>,
    ) : WalletApprovalSummary("send")

    data class NameTransfer(
        val name: String,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("nameTransfer")

    data class NameFinalize(
        val name: String,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("nameFinalize")

    data class TypedSignature(
        val messageType: String,
        val messageDigest: String,
    ) : WalletApprovalSummary("typedSignature")

    data class NameMarketOffer(
        val action: String,
        val name: String,
        val listingId: String?,
        val price: WalletApprovalAmount,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("nameMarketOffer")

    data class NameMarketPurchase(
        val name: String,
        val listingId: String,
        val payment: WalletApprovalAmount,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("nameMarketPurchase")

    data class DirectOffer(
        val action: String,
        val directOfferId: String?,
        val offered: WalletApprovalAmount,
        val received: WalletApprovalAmount,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("directOffer")

    data class DirectOfferTake(
        val directOfferId: String,
        val swapSessionId: String,
        val offered: WalletApprovalAmount,
        val received: WalletApprovalAmount,
        val refundTimeoutUnixMs: Long,
        val maximumFee: WalletApprovalAmount,
        val warnings: List<String>,
    ) : WalletApprovalSummary("directOfferTake")

    data class SwapRedeem(
        val swapSessionId: String,
        val amount: WalletApprovalAmount,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val finality: String,
        val warnings: List<String>,
    ) : WalletApprovalSummary("swapRedeem")

    data class SwapRefund(
        val swapSessionId: String,
        val amount: WalletApprovalAmount,
        val recipient: String,
        val maximumFee: WalletApprovalAmount,
        val refundAvailableAtUnixMs: Long,
        val warnings: List<String>,
    ) : WalletApprovalSummary("swapRefund")
}

internal data class WalletApprovalPrompt(
    val schemaVersion: Int,
    val approvalId: String,
    val method: String,
    val origin: String,
    val expiresAtUnixMs: Long,
    val summary: WalletApprovalSummary,
)

internal data class WalletApprovalDisplayRow(
    val label: String,
    val value: String,
)

internal data class WalletApprovalDisplay(
    val title: String,
    val rows: List<WalletApprovalDisplayRow>,
)

/** Browser-owned approval-schema-v3 public projection of the private ABI-v2 approval union. */
internal object MobileWalletApprovalProjection {
    const val SCHEMA_VERSION = 3
    const val MAX_APPROVAL_LIFETIME_MS = 90_000L
    const val MAX_APPROVAL_BYTES = 16 * 1024

    private const val MAX_PUBLIC_STRING_BYTES = 4_096
    private const val MAX_HNS_NAME_DISCLOSURES = 64
    private val maxU128 = BigInteger("340282366920938463463374607431768211455")
    private val approvalIdPattern = Regex("[A-Za-z0-9_-]{21}[AQgw]")
    private val assets = setOf("HNS", "BTC", "ETH")
    private val moduleAsset = mapOf(
        "handshake" to "HNS",
        "bitcoin" to "BTC",
        "ethereum" to "ETH",
    )
    private val finalityByModule = mapOf(
        "handshake" to "proof_of_work_confirmations",
        "bitcoin" to "proof_of_work_confirmations",
        "ethereum" to "ethereum_finalized_checkpoint",
    )
    private val finalityByAsset = mapOf(
        "HNS" to "proof_of_work_confirmations",
        "BTC" to "proof_of_work_confirmations",
        "ETH" to "ethereum_finalized_checkpoint",
    )
    private val permissionCapabilities = listOf(
        "accounts", "balance", "transactions", "receive_target", "send", "names",
        "name_transfer", "name_finalize", "typed_identity_signature", "name_market",
        "cross_chain_market", "swap_settlement",
    )
    private val warningCodes = listOf(
        "feeEstimateMayChange", "nameTransferIsIrreversible",
        "refundRequiresManualAction", "settlementCanBeDelayed",
    )
    private val reservedHnsNames = setOf("example", "invalid", "local", "localhost", "test")
    private val approvalMethods = mapOf(
        "permissions" to setOf("wallet_requestPermissions", "hns_requestAccounts"),
        "moduleEnablement" to setOf("wallet_enableModule", "wallet_disableModule"),
        "send" to setOf("hns_send", "asset_send"),
        "nameTransfer" to setOf("hns_transferName"),
        "nameFinalize" to setOf("hns_finalizeName"),
        "typedSignature" to setOf("hns_signTypedMessage"),
        "nameMarketOffer" to setOf(
            "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
            "nameMarket_recoverName",
        ),
        "nameMarketPurchase" to setOf(
            "nameMarket_acceptOffer", "nameMarket_finalizePurchase",
        ),
        "directOffer" to setOf("swap_publishDirectOffer", "swap_cancelDirectOffer"),
        "directOfferTake" to setOf("swap_takeDirectOffer", "swap_acceptDirectOffer"),
        "swapRedeem" to setOf("swap_redeem"),
        "swapRefund" to setOf("swap_refund"),
    )

    fun validate(
        candidate: JSONObject,
        expectedOrigin: String,
        expectedRequest: WalletProviderRequest,
        nowUnixMs: Long,
    ): WalletApprovalPrompt {
        if (candidate.toString().toByteArray(Charsets.UTF_8).size > MAX_APPROVAL_BYTES) {
            throw WalletProviderException(
                "approvalTooLarge",
                "Native wallet approval prompt exceeds its byte limit",
            )
        }
        requireExactFields(
            candidate,
            "schemaVersion", "approvalId", "method", "origin", "expiresAtUnixMs", "summary",
        )
        if (safeInteger(candidate.opt("schemaVersion"), allowZero = false) != SCHEMA_VERSION.toLong()) {
            fail()
        }
        val approvalId = candidate.opt("approvalId") as? String ?: fail()
        if (!approvalIdPattern.matches(approvalId) || approvalId == "AAAAAAAAAAAAAAAAAAAAAA") {
            fail()
        }
        val method = candidate.opt("method") as? String ?: fail()
        val origin = canonicalHttpsOrigin(candidate.opt("origin"))
        val expiresAtUnixMs = safeInteger(candidate.opt("expiresAtUnixMs"), allowZero = false)
        val maximumExpiry = if (
            nowUnixMs > MobileWalletProviderProtocol.MAX_SAFE_JSON_INTEGER - MAX_APPROVAL_LIFETIME_MS
        ) {
            MobileWalletProviderProtocol.MAX_SAFE_JSON_INTEGER
        } else {
            nowUnixMs + MAX_APPROVAL_LIFETIME_MS
        }
        if (
            method !in MobileWalletProviderProtocol.methods || method != expectedRequest.method ||
            origin != expectedOrigin || expiresAtUnixMs <= nowUnixMs ||
            expiresAtUnixMs > maximumExpiry
        ) {
            fail()
        }
        val summaryCandidate = candidate.opt("summary") as? JSONObject ?: fail()
        val summary = validateSummary(summaryCandidate, method, expectedRequest)
        return WalletApprovalPrompt(
            schemaVersion = SCHEMA_VERSION,
            approvalId = approvalId,
            method = method,
            origin = origin,
            expiresAtUnixMs = expiresAtUnixMs,
            summary = summary,
        )
    }

    fun display(prompt: WalletApprovalPrompt): WalletApprovalDisplay {
        val rows = mutableListOf<WalletApprovalDisplayRow>()
        fun add(label: String, value: Any) {
            rows += WalletApprovalDisplayRow(label, value.toString())
        }
        fun addAmount(label: String, amount: WalletApprovalAmount) {
            add(label, "${amount.baseUnits} ${amount.asset}")
        }
        fun addWarnings(warnings: List<String>) {
            if (warnings.isNotEmpty()) add("Warnings", warnings.joinToString(", "))
        }

        val title = when (val summary = prompt.summary) {
            is WalletApprovalSummary.Permissions -> {
                add("Capabilities", summary.capabilities.joinToString(", "))
                summary.hnsNames.forEachIndexed { index, disclosure ->
                    add("HNS name ${index + 1}", disclosure.name)
                    add("HNS name hash ${index + 1}", disclosure.nameHash)
                }
                "Approve wallet permissions"
            }
            is WalletApprovalSummary.ModuleEnablement -> {
                add("Module", summary.module)
                add("Action", summary.action)
                if (summary.action == "enable") "Enable wallet module" else "Disable wallet module"
            }
            is WalletApprovalSummary.Send -> {
                addAmount("Amount", summary.amount)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                add("Chain", summary.chain)
                add("Finality", summary.finality)
                addWarnings(summary.warnings)
                "Approve asset send"
            }
            is WalletApprovalSummary.NameTransfer -> {
                add("Name", summary.name)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve name transfer"
            }
            is WalletApprovalSummary.NameFinalize -> {
                add("Name", summary.name)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve name finalization"
            }
            is WalletApprovalSummary.TypedSignature -> {
                add("Message type", summary.messageType)
                add("Message digest", summary.messageDigest)
                "Approve typed signature"
            }
            is WalletApprovalSummary.NameMarketOffer -> {
                add("Action", summary.action)
                add("Name", summary.name)
                summary.listingId?.let { add("Listing ID", it) }
                addAmount("Price", summary.price)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve name offer action"
            }
            is WalletApprovalSummary.NameMarketPurchase -> {
                add("Name", summary.name)
                add("Listing ID", summary.listingId)
                addAmount("Payment", summary.payment)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve name purchase"
            }
            is WalletApprovalSummary.DirectOffer -> {
                add("Action", summary.action)
                summary.directOfferId?.let { add("Direct offer ID", it) }
                addAmount("Offered", summary.offered)
                addAmount("Received", summary.received)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve direct offer"
            }
            is WalletApprovalSummary.DirectOfferTake -> {
                add("Direct offer ID", summary.directOfferId)
                add("Swap session ID", summary.swapSessionId)
                addAmount("Offered", summary.offered)
                addAmount("Received", summary.received)
                add("Refund timeout", summary.refundTimeoutUnixMs)
                addAmount("Maximum fee", summary.maximumFee)
                addWarnings(summary.warnings)
                "Approve direct-offer take"
            }
            is WalletApprovalSummary.SwapRedeem -> {
                add("Swap session ID", summary.swapSessionId)
                addAmount("Amount", summary.amount)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                add("Finality", summary.finality)
                addWarnings(summary.warnings)
                "Approve swap redemption"
            }
            is WalletApprovalSummary.SwapRefund -> {
                add("Swap session ID", summary.swapSessionId)
                addAmount("Amount", summary.amount)
                add("Recipient", summary.recipient)
                addAmount("Maximum fee", summary.maximumFee)
                add("Refund available at", summary.refundAvailableAtUnixMs)
                addWarnings(summary.warnings)
                "Approve swap refund"
            }
        }
        return WalletApprovalDisplay(title, rows.toList())
    }

    private fun validateSummary(
        candidate: JSONObject,
        method: String,
        expectedRequest: WalletProviderRequest,
    ): WalletApprovalSummary {
        val kind = candidate.opt("kind") as? String ?: fail()
        if (method !in approvalMethods[kind].orEmpty()) fail()
        return when (kind) {
            "permissions" -> validatePermissions(candidate, method, expectedRequest)
            "moduleEnablement" -> validateModuleEnablement(candidate, method, expectedRequest)
            "send" -> validateSend(candidate, method, expectedRequest)
            "nameTransfer" -> validateNameChange(candidate, finalize = false)
            "nameFinalize" -> validateNameChange(candidate, finalize = true)
            "typedSignature" -> validateTypedSignature(candidate)
            "nameMarketOffer" -> validateNameMarketOffer(candidate, method)
            "nameMarketPurchase" -> validateNameMarketPurchase(candidate)
            "directOffer" -> validateDirectOffer(candidate, method)
            "directOfferTake" -> validateDirectOfferTake(candidate)
            "swapRedeem" -> validateSwapRedeem(candidate)
            "swapRefund" -> validateSwapRefund(candidate)
            else -> fail()
        }
    }

    private fun validatePermissions(
        candidate: JSONObject,
        method: String,
        request: WalletProviderRequest,
    ): WalletApprovalSummary.Permissions {
        requireExactFields(candidate, "kind", "capabilities", "hnsNames")
        val capabilities = canonicalEnumList(
            candidate.opt("capabilities"),
            permissionCapabilities,
            allowEmpty = false,
        )
        val hnsNames = hnsNameDisclosures(candidate.opt("hnsNames"))
        if ("names" !in capabilities && hnsNames.isNotEmpty()) fail()
        if (method == "hns_requestAccounts") {
            if (capabilities != listOf("accounts") || hnsNames.isNotEmpty()) fail()
        } else {
            if ("accounts" in capabilities) fail()
            val params = request.params as? JSONObject ?: fail()
            val hasCapabilities = params.has("capabilities")
            val hasScopes = params.has("scopes")
            if (hasCapabilities == hasScopes) fail()
            val requested = canonicalEnumList(
                params.opt(if (hasCapabilities) "capabilities" else "scopes"),
                permissionCapabilities,
                allowEmpty = false,
                requireCanonicalInput = false,
            )
            if (requested != capabilities) fail()
        }
        return WalletApprovalSummary.Permissions(capabilities, hnsNames)
    }

    private fun validateModuleEnablement(
        candidate: JSONObject,
        method: String,
        request: WalletProviderRequest,
    ): WalletApprovalSummary.ModuleEnablement {
        requireExactFields(candidate, "kind", "module", "action")
        val module = module(candidate.opt("module"))
        val action = enumValue(candidate.opt("action"), setOf("enable", "disable"))
        val requestedModule = (request.params as? JSONObject)?.opt("module") as? String
        if ((action == "enable") != (method == "wallet_enableModule") || requestedModule != module) {
            fail()
        }
        return WalletApprovalSummary.ModuleEnablement(module, action)
    }

    private fun validateSend(
        candidate: JSONObject,
        method: String,
        request: WalletProviderRequest,
    ): WalletApprovalSummary.Send {
        requireExactFields(
            candidate,
            "kind", "amount", "recipient", "maximumFee", "chain", "finality", "warnings",
        )
        val chain = module(candidate.opt("chain"))
        val expectedChain = if (method == "hns_send") {
            "handshake"
        } else {
            (request.params as? JSONObject)?.opt("module") as? String
        }
        val amount = amount(candidate.opt("amount"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        val finality = candidate.opt("finality") as? String ?: fail()
        if (
            chain != expectedChain || amount.asset != moduleAsset[chain] ||
            maximumFee.asset != amount.asset || finality != finalityByModule[chain]
        ) {
            fail()
        }
        return WalletApprovalSummary.Send(
            amount,
            publicString(candidate.opt("recipient")),
            maximumFee,
            chain,
            finality,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateNameChange(
        candidate: JSONObject,
        finalize: Boolean,
    ): WalletApprovalSummary {
        requireExactFields(candidate, "kind", "name", "recipient", "maximumFee", "warnings")
        val name = publicString(candidate.opt("name"))
        val recipient = publicString(candidate.opt("recipient"))
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        val warnings = warnings(candidate.opt("warnings"))
        if (maximumFee.asset != "HNS") fail()
        return if (finalize) {
            WalletApprovalSummary.NameFinalize(name, recipient, maximumFee, warnings)
        } else {
            WalletApprovalSummary.NameTransfer(name, recipient, maximumFee, warnings)
        }
    }

    private fun validateTypedSignature(candidate: JSONObject): WalletApprovalSummary.TypedSignature {
        requireExactFields(candidate, "kind", "messageType", "messageDigest")
        return WalletApprovalSummary.TypedSignature(
            publicString(candidate.opt("messageType")),
            publicString(candidate.opt("messageDigest")),
        )
    }

    private fun validateNameMarketOffer(
        candidate: JSONObject,
        method: String,
    ): WalletApprovalSummary.NameMarketOffer {
        requireExactFields(
            candidate,
            "kind", "action", "name", "listingId", "price", "maximumFee", "warnings",
        )
        val action = enumValue(candidate.opt("action"), setOf("create", "cancel", "recover"))
        val expectedAction = when (method) {
            "nameMarket_createFixedPriceOffer" -> "create"
            "nameMarket_cancelOffer" -> "cancel"
            "nameMarket_recoverName" -> "recover"
            else -> fail()
        }
        val price = amount(candidate.opt("price"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        if (action != expectedAction || price.asset != "HNS" || maximumFee.asset != "HNS") fail()
        return WalletApprovalSummary.NameMarketOffer(
            action,
            publicString(candidate.opt("name")),
            optionalPublicString(candidate.opt("listingId")),
            price,
            maximumFee,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateNameMarketPurchase(candidate: JSONObject): WalletApprovalSummary.NameMarketPurchase {
        requireExactFields(
            candidate,
            "kind", "name", "listingId", "payment", "recipient", "maximumFee", "warnings",
        )
        val payment = amount(candidate.opt("payment"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        if (payment.asset != "HNS" || maximumFee.asset != "HNS") fail()
        return WalletApprovalSummary.NameMarketPurchase(
            publicString(candidate.opt("name")),
            publicString(candidate.opt("listingId")),
            payment,
            publicString(candidate.opt("recipient")),
            maximumFee,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateDirectOffer(
        candidate: JSONObject,
        method: String,
    ): WalletApprovalSummary.DirectOffer {
        requireExactFields(
            candidate,
            "kind", "action", "directOfferId", "offered", "received",
            "maximumFee", "warnings",
        )
        val action = enumValue(candidate.opt("action"), setOf("publish", "cancel"))
        val expectedAction = if (method == "swap_publishDirectOffer") "publish" else "cancel"
        val offered = amount(candidate.opt("offered"), allowZero = false)
        val received = amount(candidate.opt("received"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        if (
            action != expectedAction || offered.asset == received.asset ||
            maximumFee.asset != offered.asset
        ) {
            fail()
        }
        return WalletApprovalSummary.DirectOffer(
            action,
            optionalPublicString(candidate.opt("directOfferId")),
            offered,
            received,
            maximumFee,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateDirectOfferTake(candidate: JSONObject): WalletApprovalSummary.DirectOfferTake {
        requireExactFields(
            candidate,
            "kind", "directOfferId", "swapSessionId", "offered", "received",
            "refundTimeoutUnixMs", "maximumFee", "warnings",
        )
        val offered = amount(candidate.opt("offered"), allowZero = false)
        val received = amount(candidate.opt("received"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        if (offered.asset == received.asset || maximumFee.asset != offered.asset) fail()
        return WalletApprovalSummary.DirectOfferTake(
            publicString(candidate.opt("directOfferId")),
            publicString(candidate.opt("swapSessionId")),
            offered,
            received,
            positiveTime(candidate.opt("refundTimeoutUnixMs")),
            maximumFee,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateSwapRedeem(candidate: JSONObject): WalletApprovalSummary.SwapRedeem {
        requireExactFields(
            candidate,
            "kind", "swapSessionId", "amount", "recipient", "maximumFee", "finality", "warnings",
        )
        val amount = amount(candidate.opt("amount"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        val finality = candidate.opt("finality") as? String ?: fail()
        if (maximumFee.asset != amount.asset || finality != finalityByAsset[amount.asset]) fail()
        return WalletApprovalSummary.SwapRedeem(
            publicString(candidate.opt("swapSessionId")),
            amount,
            publicString(candidate.opt("recipient")),
            maximumFee,
            finality,
            warnings(candidate.opt("warnings")),
        )
    }

    private fun validateSwapRefund(candidate: JSONObject): WalletApprovalSummary.SwapRefund {
        requireExactFields(
            candidate,
            "kind", "swapSessionId", "amount", "recipient", "maximumFee",
            "refundAvailableAtUnixMs", "warnings",
        )
        val amount = amount(candidate.opt("amount"), allowZero = false)
        val maximumFee = amount(candidate.opt("maximumFee"), allowZero = true)
        if (maximumFee.asset != amount.asset) fail()
        return WalletApprovalSummary.SwapRefund(
            publicString(candidate.opt("swapSessionId")),
            amount,
            publicString(candidate.opt("recipient")),
            maximumFee,
            positiveTime(candidate.opt("refundAvailableAtUnixMs")),
            warnings(candidate.opt("warnings")),
        )
    }

    private fun amount(candidate: Any?, allowZero: Boolean): WalletApprovalAmount {
        val value = candidate as? JSONObject ?: fail()
        requireExactFields(value, "asset", "baseUnits")
        val asset = asset(value.opt("asset"))
        val baseUnits = value.opt("baseUnits") as? String ?: fail()
        if (!Regex("^(0|[1-9][0-9]{0,38})$").matches(baseUnits)) fail()
        val numericValue = runCatching { BigInteger(baseUnits) }.getOrElse { fail() }
        if (numericValue > maxU128 || (!allowZero && numericValue == BigInteger.ZERO)) fail()
        return WalletApprovalAmount(asset, baseUnits)
    }

    private fun warnings(candidate: Any?): List<String> =
        canonicalEnumList(candidate, warningCodes, allowEmpty = true)

    private fun canonicalEnumList(
        candidate: Any?,
        ordered: List<String>,
        allowEmpty: Boolean,
        requireCanonicalInput: Boolean = true,
    ): List<String> {
        val array = candidate as? JSONArray ?: fail()
        if ((!allowEmpty && array.length() == 0) || array.length() > ordered.size) fail()
        val values = List(array.length()) { index -> array.opt(index) as? String ?: fail() }
        if (values.toSet().size != values.size || values.any { it !in ordered }) fail()
        val canonical = ordered.filter(values::contains)
        if (requireCanonicalInput && values != canonical) fail()
        return canonical
    }

    private fun hnsNameDisclosures(candidate: Any?): List<WalletHnsNameDisclosure> {
        val array = candidate as? JSONArray ?: fail()
        if (array.length() > MAX_HNS_NAME_DISCLOSURES) fail()
        val names = mutableSetOf<String>()
        val hashes = mutableSetOf<String>()
        var previous: WalletHnsNameDisclosure? = null
        return List(array.length()) { index ->
            val value = array.opt(index) as? JSONObject ?: fail()
            requireExactFields(value, "name", "nameHash")
            val name = value.opt("name") as? String ?: fail()
            val nameHash = value.opt("nameHash") as? String ?: fail()
            if (
                !isCanonicalHnsName(name) || !isLowerHex256(nameHash) ||
                sha3_256Hex(name.toByteArray(Charsets.UTF_8)) != nameHash ||
                !names.add(name) || !hashes.add(nameHash)
            ) {
                fail()
            }
            val disclosure = WalletHnsNameDisclosure(name, nameHash)
            previous?.let {
                if (
                    it.name > disclosure.name ||
                    (it.name == disclosure.name && it.nameHash >= disclosure.nameHash)
                ) {
                    fail()
                }
            }
            previous = disclosure
            disclosure
        }
    }

    private fun isCanonicalHnsName(name: String): Boolean {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..63 || name in reservedHnsNames) return false
        return bytes.withIndex().all { (index, byte) ->
            val value = byte.toInt() and 0xff
            value in '0'.code..'9'.code || value in 'a'.code..'z'.code ||
                (value == '-'.code || value == '_'.code) &&
                index != 0 && index + 1 != bytes.size
        }
    }

    private fun isLowerHex256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun sha3_256Hex(input: ByteArray): String {
        val digest = runCatching { MessageDigest.getInstance("SHA3-256").digest(input) }
            .getOrElse { fail() }
        val alphabet = "0123456789abcdef"
        return buildString(64) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    private fun module(candidate: Any?): String = enumValue(candidate, moduleAsset.keys)

    private fun asset(candidate: Any?): String = enumValue(candidate, assets)

    private fun enumValue(candidate: Any?, allowed: Set<String>): String {
        val value = candidate as? String ?: fail()
        if (value !in allowed) fail()
        return value
    }

    private fun publicString(candidate: Any?): String {
        val value = candidate as? String ?: fail()
        if (
            value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_PUBLIC_STRING_BYTES ||
            value.any { it.code !in 0x20..0x7e }
        ) {
            fail()
        }
        return value
    }

    private fun canonicalHttpsOrigin(candidate: Any?): String {
        val value = candidate as? String ?: fail()
        if (
            value.isEmpty() || value.length > 512 || value.any { it.code > 0x7f } ||
            value.toByteArray(Charsets.UTF_8).size > 512
        ) {
            fail()
        }
        val parsed = runCatching { URI(value) }.getOrElse { fail() }
        val host = parsed.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: fail()
        if (
            parsed.scheme != "https" || parsed.rawUserInfo != null || parsed.rawQuery != null ||
            parsed.rawFragment != null || parsed.rawPath.isNotEmpty() || parsed.port !in -1..65_535
        ) {
            fail()
        }
        val serializedHost = if (':' in host) "[$host]" else host
        val canonical = if (parsed.port == -1 || parsed.port == 443) {
            "https://$serializedHost"
        } else {
            "https://$serializedHost:${parsed.port}"
        }
        if (value != canonical) fail()
        return value
    }

    private fun optionalPublicString(candidate: Any?): String? =
        if (candidate == null || candidate === JSONObject.NULL) null else publicString(candidate)

    private fun positiveTime(candidate: Any?): Long = safeInteger(candidate, allowZero = false)

    private fun safeInteger(candidate: Any?, allowZero: Boolean): Long {
        val value = when (candidate) {
            is Byte -> candidate.toLong()
            is Short -> candidate.toLong()
            is Int -> candidate.toLong()
            is Long -> candidate
            else -> fail()
        }
        val minimum = if (allowZero) 0L else 1L
        if (
            value < minimum ||
            value > MobileWalletProviderProtocol.MAX_SAFE_JSON_INTEGER
        ) {
            fail()
        }
        return value
    }

    private fun requireExactFields(candidate: JSONObject, vararg fields: String) {
        if (candidate.length() != fields.size || fields.any { !candidate.has(it) }) fail()
    }

    private fun fail(): Nothing =
        throw WalletProviderException("invalidApproval", "Native wallet approval prompt is invalid")
}
