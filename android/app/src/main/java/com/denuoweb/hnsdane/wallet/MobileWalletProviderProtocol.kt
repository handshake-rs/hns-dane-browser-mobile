package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject

internal data class WalletBrowserAuthority(
    val origin: String,
    val namespace: String,
    val browserAuthoritySession: String,
    val policyGeneration: Long,
    val navigationGeneration: Long,
)

internal data class WalletCapabilitiesV1(
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

internal interface MobileWalletAbiV1 {
    fun capabilities(authority: WalletBrowserAuthority): WalletCapabilitiesV1
    fun request(authority: WalletProviderAuthority, request: WalletProviderRequest): Any?
}

internal object UnavailableMobileWalletAbiV1 : MobileWalletAbiV1 {
    override fun capabilities(authority: WalletBrowserAuthority): WalletCapabilitiesV1 =
        WalletCapabilitiesV1(
            available = false,
            abiVersion = 1,
            walletSession = "",
            permissionGeneration = 0,
            methods = emptySet(),
        )

    override fun request(authority: WalletProviderAuthority, request: WalletProviderRequest): Any? =
        throw WalletProviderException("walletUnavailable", "Mobile wallet ABI v1 is unavailable")
}

internal class WalletProviderException(
    val code: String,
    message: String,
) : IllegalArgumentException(message)

internal object MobileWalletProviderProtocol {
    const val SCHEMA_VERSION = 1
    const val ABI_VERSION = 1
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
        "recoveryphrase", "mnemonic", "seed", "seedbytes", "privatekey", "passphrase",
        "databaseencryptionkey", "encryptionkey", "htlcpreimage", "preimage",
        "providercapabilitysecret", "sessionauthorizationtoken",
    )

    fun parseRequest(raw: String): WalletProviderRequest {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) {
            fail("requestTooLarge", "Wallet provider frame exceeds its byte limit")
        }
        val value = runCatching { JSONObject(raw) }.getOrElse {
            fail("invalidRequest", "Wallet provider frame is not a JSON object")
        }
        if (value.optInt("schemaVersion", -1) != SCHEMA_VERSION || value.optString("kind") != "request") {
            fail("unsupportedVersion", "Unsupported wallet provider frame")
        }
        val requestId = value.optString("requestId")
        if (!requestId.matches(Regex("[A-Za-z0-9._:-]{1,96}"))) {
            fail("invalidRequest", "Invalid wallet provider request identifier")
        }
        val rawSequence = value.opt("sequence") as? Number
        val sequenceDouble = rawSequence?.toDouble()
        if (
            sequenceDouble == null || !sequenceDouble.isFinite() ||
            sequenceDouble < 1.0 || sequenceDouble > MAX_SAFE_JSON_INTEGER.toDouble() ||
            sequenceDouble % 1.0 != 0.0
        ) {
            fail("invalidRequest", "Invalid wallet provider request sequence")
        }
        val sequence = sequenceDouble.toLong()
        val method = value.optString("method")
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
            val module = (params as? JSONObject)?.optString("module")
            if (module != "bitcoin" && module != "ethereum") {
                fail("invalidParams", "$method requires bitcoin or ethereum module")
            }
        }
        return WalletProviderRequest(requestId, sequence, method, params)
    }

    fun validateCapabilities(value: WalletCapabilitiesV1): WalletCapabilitiesV1 {
        if (
            !value.available || value.abiVersion != ABI_VERSION ||
            value.walletSession.isBlank() || value.walletSession.length > 160 ||
            value.permissionGeneration < 1 || !methods.containsAll(value.methods)
        ) {
            fail("walletUnavailable", "Native wallet ABI v1 is unavailable")
        }
        return value
    }

    fun response(request: WalletProviderRequest?, result: Any? = null, error: Throwable? = null): String {
        if (error == null) {
            validateJson(
                result,
                depth = 0,
                invalidCode = "invalidResult",
                sizeCode = "resultTooLarge",
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

    fun event(event: String, payload: Any?): String {
        if (event !in events) fail("invalidEvent", "Unsupported wallet provider event")
        validateJson(
            payload,
            depth = 0,
            invalidCode = "invalidEvent",
            sizeCode = "eventTooLarge",
        )
        val encoded = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("kind", "event")
            .put("event", event)
            .put("payload", payload ?: JSONObject.NULL)
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
    ) {
        if (depth > 12) fail(sizeCode, "Wallet provider frame is nested too deeply")
        when (value) {
            null, JSONObject.NULL, is Boolean -> Unit
            is String -> if (value.length > 16 * 1024) {
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
                    validateJson(value.get(it), depth + 1, invalidCode, sizeCode)
                }
            }
            is JSONObject -> {
                if (value.length() > 128) fail(sizeCode, "Too many object fields")
                for (key in value.keys()) {
                    val normalizedKey = key.lowercase().filter(Char::isLetterOrDigit)
                    if (
                        key in setOf("__proto__", "prototype", "constructor") ||
                        normalizedKey in sensitiveFields
                    ) {
                        fail(invalidCode, "Forbidden wallet provider field")
                    }
                    validateJson(value.get(key), depth + 1, invalidCode, sizeCode)
                }
            }
            else -> fail(invalidCode, "Wallet provider frame contains a non-JSON value")
        }
    }

    private val publicErrorCodes = setOf(
        "browserAuthorityDenied", "eventTooLarge", "forbiddenMethod", "internalError",
        "invalidEvent", "invalidOrigin", "invalidParams", "invalidRequest", "invalidResult",
        "originMismatch", "permissionDenied", "permissionGenerationChanged", "rateLimited",
        "replay", "requestTooLarge", "resultTooLarge", "staleContext", "unsupportedMethod",
        "unsupportedVersion", "userRejected", "walletLocked", "walletSessionChanged",
        "walletUnavailable",
    )

    private fun publicErrorMessage(code: String): String = when (code) {
        "browserAuthorityDenied" -> "Browser trust did not approve this document"
        "eventTooLarge" -> "Wallet event exceeded its byte limit"
        "forbiddenMethod" -> "The requested signing method is intentionally unavailable"
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
}
