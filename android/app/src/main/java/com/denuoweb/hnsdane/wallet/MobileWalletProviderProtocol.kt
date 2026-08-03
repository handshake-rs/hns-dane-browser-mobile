package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject

internal data class WalletBrowserAuthority(
    val origin: String,
    val namespace: String,
    val browserAuthoritySession: String,
    val runtimeGeneration: Long,
    val policyGeneration: Long,
    val navigationGeneration: Long,
    val decisionFingerprint: String,
    val validUntilUnixMs: Long,
    val engineContext: WalletEngineAuthorityContext,
) {
    fun isCurrent(nowUnixMs: Long): Boolean =
        (namespace == "hns" || namespace == "icann") && browserAuthoritySession.isNotBlank() &&
            browserAuthoritySession.length <= 160 && runtimeGeneration > 0 &&
            policyGeneration > 0 && navigationGeneration > 0 &&
            decisionFingerprint.length == 64 &&
            decisionFingerprint.all { it in '0'..'9' || it in 'a'..'f' } &&
            decisionFingerprint.any { it != '0' } &&
            validUntilUnixMs > nowUnixMs &&
            validUntilUnixMs <= MobileWalletProviderProtocol.MAX_SAFE_JSON_INTEGER
}

/**
 * Opaque browser-engine authority capability. This final token deliberately retains identity
 * equality so an implementation cannot make two unrelated authority contexts compare equal.
 */
internal class WalletEngineAuthorityContext

internal data class WalletCapabilitiesV2(
    val available: Boolean,
    val abiVersion: Int,
    val walletSession: String,
    val permissionGeneration: Long,
    val methods: Set<String>,
)

internal data class WalletProviderAuthority(
    val browser: WalletBrowserAuthority,
    val walletSession: String,
    val permissionGeneration: Long,
)

internal data class WalletProviderRequest(
    val requestId: String,
    val sequence: Long,
    val method: String,
    val params: Any?,
)

internal interface MobileWalletAbiV2 {
    fun capabilities(authority: WalletBrowserAuthority): WalletCapabilitiesV2
    fun request(authority: WalletProviderAuthority, request: WalletProviderRequest): Any?
}

internal object UnavailableMobileWalletAbiV2 : MobileWalletAbiV2 {
    override fun capabilities(authority: WalletBrowserAuthority): WalletCapabilitiesV2 =
        WalletCapabilitiesV2(
            available = false,
            abiVersion = MobileWalletProviderProtocol.WALLET_NATIVE_ABI_VERSION,
            walletSession = "",
            permissionGeneration = 0,
            methods = emptySet(),
        )

    override fun request(authority: WalletProviderAuthority, request: WalletProviderRequest): Any? =
        throw WalletProviderException("walletUnavailable", "Mobile wallet ABI v2 is unavailable")
}

internal class WalletProviderException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

internal enum class WalletMethodReleaseClass {
    NoApproval,
    ApprovalOnly,
    ApprovalAndValue,
}

internal object MobileWalletProviderProtocol {
    /** Website-facing provider frames and the announced API deliberately remain version 1. */
    const val SCHEMA_VERSION = 1
    const val WALLET_NATIVE_ABI_VERSION = 2

    // Production wiring may replace these only after the corresponding release gates are
    // qualified together. Caller-provided availability booleans cannot override them.
    const val PROVIDER_BRIDGE_RELEASE_QUALIFIED = false
    const val WALLET_RUNTIME_RELEASE_QUALIFIED = false
    const val APPROVAL_RUNTIME_RELEASE_QUALIFIED = false
    const val VALUE_RUNTIME_RELEASE_QUALIFIED = false

    const val MAX_MESSAGE_BYTES = 64 * 1024
    const val MAX_RESULT_BYTES = 256 * 1024
    const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L

    val methods: Set<String> = setOf(
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_enableModule",
        "wallet_disableModule", "wallet_requestPermissions", "wallet_getPermissions",
        "wallet_revokePermissions", "wallet_lock", "wallet_getStatus",
        "hns_requestAccounts", "hns_accounts", "hns_getBalance", "hns_getTransactions",
        "hns_getReceiveAddress", "hns_send", "hns_getNames", "hns_getName",
        "hns_importKnownName", "hns_transferName", "hns_finalizeName", "hns_signTypedMessage",
        "asset_getAccount", "asset_getBalance", "asset_getTransactions",
        "asset_getReceiveTarget", "asset_send", "nameMarket_listOffers",
        "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
        "nameMarket_acceptOffer", "nameMarket_getSession", "nameMarket_finalizePurchase",
        "nameMarket_recoverName", "swap_getSupportedPairs", "swap_getPriceRound",
        "swap_listMarketIntents", "swap_publishMarketIntent", "swap_cancelMarketIntent",
        "swap_requestMatch", "swap_acceptFill", "swap_getSession", "swap_redeem", "swap_refund",
    )

    val events: Set<String> = setOf(
        "connect", "disconnect", "permissionsChanged", "modulesChanged", "accountsChanged",
        "balancesChanged", "transactionsChanged", "namesChanged", "nameMarketChanged",
        "priceRoundChanged", "marketIntentChanged", "swapSessionChanged", "walletLocked",
    )

    val forbiddenMethods: Set<String> = setOf(
        "eth_sendTransaction", "eth_call", "eth_estimateGas", "eth_sign", "personal_sign",
        "wallet_addEthereumChain", "wallet_switchEthereumChain", "bitcoin_signPsbt",
        "signRawTransaction",
    )

    private val assetMethods = setOf(
        "asset_getAccount", "asset_getBalance", "asset_getTransactions",
        "asset_getReceiveTarget", "asset_send",
    )
    private val noParameterMethods = setOf(
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_getPermissions",
        "wallet_lock", "wallet_getStatus", "hns_requestAccounts", "hns_accounts",
        "hns_getBalance", "hns_getReceiveAddress", "hns_getNames",
        "swap_getSupportedPairs", "swap_listMarketIntents",
    )
    private val sensitiveFields = setOf(
        "protocolversion", "requestnonce", "walletsession",
        "authorityhandle", "authorityrevision", "hostsessionid", "servicesessionid",
        "runtimesessionid", "browserruntimesessionid", "browserauthoritysession",
        "restartgeneration", "channelsequence", "eventsequence",
        "runtimegeneration", "policygeneration", "navigationgeneration",
        "decisionfingerprint", "validuntilunixms", "enginecontext", "approvalrequired",
        "recoveryphrase", "mnemonic", "seed", "seedbytes", "privatekey", "passphrase",
        "databaseencryptionkey", "encryptionkey", "htlcpreimage", "preimage",
        "providercapabilitysecret", "sessionauthorizationtoken",
    )
    private val nativeRoutingFields = setOf("event", "events", "approvalrequired")
    private val approvalRoutingFields = setOf("approvalid", "expiresatunixms", "summary")
    private val permissionResultMethods = setOf(
        "wallet_getPermissions", "wallet_revokePermissions",
        "wallet_requestPermissions", "hns_requestAccounts",
    )

    fun parseRequest(raw: String): WalletProviderRequest {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) {
            fail("requestTooLarge", "Wallet provider frame exceeds its byte limit")
        }
        val value = runCatching { JSONObject(raw) }.getOrElse {
            fail("invalidRequest", "Wallet provider frame is not a JSON object")
        }
        if (value.opt("schemaVersion") != SCHEMA_VERSION) {
            fail("unsupportedVersion", "Unsupported wallet provider frame")
        }
        if (value.opt("kind") !is String || value.opt("kind") != "request") {
            fail("invalidRequest", "Wallet provider frame kind is invalid")
        }
        val requestId = value.opt("requestId") as? String
            ?: fail("invalidRequest", "Invalid wallet provider request identifier")
        if (!requestId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) {
            fail("invalidRequest", "Invalid wallet provider request identifier")
        }
        val sequence = positiveSafeInteger(
            value.opt("sequence"),
            "invalidRequest",
            "Invalid wallet provider request sequence",
        )
        val method = value.opt("method") as? String
            ?: fail("invalidRequest", "Wallet provider method is invalid")
        if (method in forbiddenMethods) {
            fail("forbiddenMethod", "$method is intentionally unavailable")
        }
        if (method !in methods) fail("unsupportedMethod", "Unsupported wallet provider method")
        val params = value.opt("params").takeUnless { it == null || it === JSONObject.NULL }
        validateJson(
            params,
            depth = 0,
            invalidCode = "invalidRequest",
            sizeCode = "requestTooLarge",
        )
        if (method in noParameterMethods && params != null && (params !is JSONObject || params.length() != 0)) {
            fail("invalidParams", "$method does not accept parameters")
        }
        if (method in assetMethods) {
            val module = (params as? JSONObject)?.opt("module") as? String
            if (module != "bitcoin" && module != "ethereum") {
                fail("invalidParams", "$method requires bitcoin or ethereum module")
            }
        }
        return WalletProviderRequest(requestId, sequence, method, params)
    }

    fun validateCapabilities(value: WalletCapabilitiesV2): WalletCapabilitiesV2 {
        if (
            !WALLET_RUNTIME_RELEASE_QUALIFIED || !hasValidCapabilitySnapshot(value)
        ) {
            fail("walletUnavailable", "Native wallet ABI v2 is unavailable")
        }
        return value
    }

    internal fun hasValidCapabilitySnapshot(value: WalletCapabilitiesV2): Boolean =
        value.available && value.abiVersion == WALLET_NATIVE_ABI_VERSION &&
            value.walletSession.isNotBlank() && value.walletSession.length <= 160 &&
            value.permissionGeneration >= 0 && methods.containsAll(value.methods)

    fun methodReleaseClass(method: String): WalletMethodReleaseClass = when (method) {
        "wallet_getCapabilities", "wallet_getEnabledModules", "wallet_getPermissions",
        "wallet_revokePermissions", "wallet_lock", "wallet_getStatus", "hns_accounts",
        "hns_getBalance", "hns_getTransactions", "hns_getReceiveAddress", "hns_getNames",
        "hns_getName", "hns_importKnownName", "asset_getAccount", "asset_getBalance",
        "asset_getTransactions", "asset_getReceiveTarget", "nameMarket_listOffers",
        "nameMarket_getSession", "swap_getSupportedPairs", "swap_getPriceRound",
        "swap_listMarketIntents", "swap_getSession" -> WalletMethodReleaseClass.NoApproval

        "wallet_enableModule", "wallet_disableModule", "wallet_requestPermissions",
        "hns_requestAccounts", "hns_signTypedMessage" -> WalletMethodReleaseClass.ApprovalOnly

        "hns_send", "hns_transferName", "hns_finalizeName", "asset_send",
        "nameMarket_createFixedPriceOffer", "nameMarket_cancelOffer",
        "nameMarket_acceptOffer", "nameMarket_finalizePurchase", "nameMarket_recoverName",
        "swap_publishMarketIntent", "swap_cancelMarketIntent", "swap_requestMatch",
        "swap_acceptFill", "swap_redeem", "swap_refund" ->
            WalletMethodReleaseClass.ApprovalAndValue

        else -> fail("unsupportedMethod", "Unsupported wallet provider method")
    }

    fun requireMethodReleaseQualified(method: String) {
        val releaseClass = methodReleaseClass(method)
        if (!WALLET_RUNTIME_RELEASE_QUALIFIED) {
            fail("walletUnavailable", "Mobile wallet runtime is unavailable")
        }
        if (
            releaseClass != WalletMethodReleaseClass.NoApproval &&
            !APPROVAL_RUNTIME_RELEASE_QUALIFIED
        ) {
            fail("walletUnavailable", "Mobile wallet approval runtime is unavailable")
        }
        if (
            releaseClass == WalletMethodReleaseClass.ApprovalAndValue &&
            !VALUE_RUNTIME_RELEASE_QUALIFIED
        ) {
            fail("walletUnavailable", "Mobile wallet value runtime is unavailable")
        }
    }

    fun response(request: WalletProviderRequest?, result: Any? = null, error: Throwable? = null): String {
        if (error == null) {
            validateJson(
                result,
                depth = 0,
                invalidCode = "invalidResult",
                sizeCode = "resultTooLarge",
                rejectNativeRouting = true,
                allowPermissionGenerationAtRoot =
                    request?.method?.let(permissionResultMethods::contains) == true,
            )
        }
        val value = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("kind", if (request == null) "initialized" else "response")
        request?.let {
            value.put("requestId", it.requestId)
            value.put("sequence", it.sequence)
        }
        if (error == null) {
            value.put("ok", true).put("result", result ?: JSONObject.NULL)
        } else {
            val providerError = error as? WalletProviderException
            val code = providerError?.code?.takeIf(publicErrorCodes::contains) ?: "internalError"
            value.put("ok", false).put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", publicErrorMessage(code)),
            )
        }
        val encoded = value.toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_RESULT_BYTES) {
            fail("resultTooLarge", "Native wallet result exceeds its byte limit")
        }
        return encoded
    }

    fun event(nativePayload: JSONObject): String {
        val projected = MobileWalletEventProjection.project(nativePayload)
        if (projected.event !in events) fail("invalidEvent", "Unsupported wallet provider event")
        validateJson(
            projected.payload,
            depth = 0,
            invalidCode = "invalidEvent",
            sizeCode = "eventTooLarge",
            allowPermissionGenerationAtRoot =
                projected.event == "connect" || projected.event == "permissionsChanged",
        )
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("kind", "event")
            .put("event", projected.event)
            .put("payload", projected.payload)
            .toString()
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) {
            fail("eventTooLarge", "Native wallet event exceeds its byte limit")
        }
        return encoded
    }

    private fun validateJson(
        value: Any?,
        depth: Int,
        invalidCode: String,
        sizeCode: String,
        rejectNativeRouting: Boolean = false,
        allowPermissionGenerationAtRoot: Boolean = false,
    ) {
        if (depth > 12) fail(sizeCode, "Wallet provider frame is nested too deeply")
        when (value) {
            null, JSONObject.NULL, is Boolean -> Unit
            is String -> if (value.toByteArray(Charsets.UTF_8).size > 16 * 1024) {
                fail(sizeCode, "Wallet provider string exceeds its limit")
            }
            is Byte, is Short, is Int, is Long -> if (
                value.toLong() < -MAX_SAFE_JSON_INTEGER || value.toLong() > MAX_SAFE_JSON_INTEGER
            ) {
                fail(invalidCode, "JSON integer exceeds the safe interoperable range")
            }
            is Number -> fail(invalidCode, "Use integers or decimal base-unit strings")
            is JSONArray -> {
                if (value.length() > 128) fail(sizeCode, "Too many array values")
                repeat(value.length()) {
                    validateJson(
                        value.get(it),
                        depth + 1,
                        invalidCode,
                        sizeCode,
                        rejectNativeRouting,
                        allowPermissionGenerationAtRoot,
                    )
                }
            }
            is JSONObject -> {
                if (value.length() > 128) fail(sizeCode, "Too many object fields")
                val normalizedFields = mutableSetOf<String>()
                for (key in value.keys()) {
                    normalizedFields += key.lowercase().filter(Char::isLetterOrDigit)
                }
                if (
                    rejectNativeRouting &&
                    (
                        normalizedFields.any { it in nativeRoutingFields } ||
                            approvalRoutingFields.all(normalizedFields::contains)
                    )
                ) {
                    fail(invalidCode, "Native events and approval prompts require private routing")
                }
                for (key in value.keys()) {
                    val normalizedKey = key.lowercase().filter(Char::isLetterOrDigit)
                    if (
                        key in setOf("__proto__", "prototype", "constructor") ||
                        normalizedKey in sensitiveFields ||
                        (
                            normalizedKey == "permissiongeneration" &&
                                (!allowPermissionGenerationAtRoot || depth != 0)
                        )
                    ) {
                        fail(invalidCode, "Forbidden wallet provider field")
                    }
                    validateJson(
                        value.get(key),
                        depth + 1,
                        invalidCode,
                        sizeCode,
                        rejectNativeRouting,
                        allowPermissionGenerationAtRoot,
                    )
                }
            }
            else -> fail(invalidCode, "Wallet provider frame contains a non-JSON value")
        }
    }

    private val publicErrorCodes = setOf(
        "approvalTooLarge", "browserAuthorityDenied", "eventTooLarge", "forbiddenMethod",
        "internalError", "invalidApproval", "invalidEvent", "invalidOrigin", "invalidParams",
        "invalidRequest", "invalidResult",
        "originMismatch", "permissionDenied", "permissionGenerationChanged", "rateLimited",
        "replay", "requestTooLarge", "resultTooLarge", "staleContext", "unsupportedMethod",
        "unsupportedVersion", "userRejected", "walletLocked", "walletSessionChanged",
        "walletUnavailable",
    )

    private fun publicErrorMessage(code: String): String = when (code) {
        "approvalTooLarge" -> "Wallet approval exceeded its byte limit"
        "browserAuthorityDenied" -> "Browser trust did not approve this document"
        "eventTooLarge" -> "Wallet event exceeded its byte limit"
        "forbiddenMethod" -> "The requested signing method is intentionally unavailable"
        "invalidApproval" -> "Native wallet approval prompt was invalid"
        "invalidEvent" -> "Native wallet event was invalid"
        "invalidOrigin" -> "Wallet provider requires an exact HTTPS main frame"
        "invalidParams" -> "Wallet provider parameters were invalid"
        "invalidRequest" -> "Wallet provider request was invalid"
        "invalidResult" -> "Native wallet result was invalid"
        "originMismatch" -> "Wallet frame origin did not match its source"
        "permissionDenied" -> "Wallet permission was denied"
        "permissionGenerationChanged" -> "Wallet permissions changed during the request"
        "rateLimited" -> "Wallet provider request rate was exceeded"
        "replay" -> "Wallet provider request was already observed"
        "requestTooLarge" -> "Wallet provider request exceeded its byte limit"
        "resultTooLarge" -> "Native wallet result exceeded its byte limit"
        "staleContext" -> "Wallet provider document binding is stale"
        "unsupportedMethod" -> "Wallet provider method is unsupported"
        "unsupportedVersion" -> "Wallet provider version is unsupported"
        "userRejected" -> "Wallet request was rejected"
        "walletLocked" -> "Wallet is locked"
        "walletSessionChanged" -> "Wallet session changed during the request"
        "walletUnavailable" -> "Mobile wallet is unavailable"
        else -> "Wallet request failed"
    }

    private fun fail(code: String, message: String): Nothing =
        throw WalletProviderException(code, message)

    private fun positiveSafeInteger(candidate: Any?, code: String, message: String): Long {
        val value = when (candidate) {
            is Byte -> candidate.toLong()
            is Short -> candidate.toLong()
            is Int -> candidate.toLong()
            is Long -> candidate
            else -> fail(code, message)
        }
        if (value < 1L || value > MAX_SAFE_JSON_INTEGER) fail(code, message)
        return value
    }
}
