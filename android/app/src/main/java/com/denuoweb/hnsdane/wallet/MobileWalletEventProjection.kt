package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject

internal data class ProjectedWalletProviderEvent(
    val event: String,
    val payload: JSONObject,
)

/**
 * Converts only the typed ABI-v2 event payload into the website provider-schema-v1 event.
 *
 * The private ABI envelope is intentionally not an accepted input shape. Host/service sessions,
 * restart and channel sequences, authority handles/revisions, and event sequences therefore
 * cannot survive this projection.
 */
internal object MobileWalletEventProjection {
    private const val MAX_PUBLIC_ITEMS = 128
    private const val MAX_PUBLIC_STRING_BYTES = 4_096

    private val permissionCapabilities = listOf(
        "accounts", "balance", "transactions", "receive_target", "send", "names",
        "name_transfer", "name_finalize", "typed_identity_signature", "name_market",
        "cross_chain_market", "swap_settlement",
    )
    private val modules = listOf("handshake", "bitcoin", "ethereum")
    private val disconnectReasons = setOf(
        "authorityRevoked", "authorityExpired", "navigationChanged", "policyChanged",
        "walletSessionChanged", "serviceRestarted",
    )

    fun project(candidate: JSONObject): ProjectedWalletProviderEvent {
        if (candidate.toString().toByteArray(Charsets.UTF_8).size > MobileWalletProviderProtocol.MAX_MESSAGE_BYTES) {
            fail("eventTooLarge", "Native wallet event exceeds its byte limit")
        }
        val event = candidate.opt("event") as? String
            ?: fail("invalidEvent", "Native wallet event discriminator is missing")
        val payload = when (event) {
            "connect" -> {
                requireExactFields(candidate, "event", "permissionGeneration")
                JSONObject().put(
                    "permissionGeneration",
                    positiveSafeInteger(candidate.opt("permissionGeneration")),
                )
            }
            "disconnect" -> {
                requireExactFields(candidate, "event", "reason")
                val reason = enumValue(candidate.opt("reason"), disconnectReasons)
                JSONObject().put("reason", reason)
            }
            "permissionsChanged" -> {
                requireExactFields(candidate, "event", "permissionGeneration", "capabilities")
                val capabilities = canonicalEnumList(
                    candidate.opt("capabilities"),
                    permissionCapabilities,
                )
                JSONObject()
                    .put(
                        "permissionGeneration",
                        positiveSafeInteger(candidate.opt("permissionGeneration")),
                    )
                    .put("capabilities", jsonArray(capabilities))
            }
            "modulesChanged", "accountsChanged", "balancesChanged", "transactionsChanged" -> {
                requireExactFields(candidate, "event", "modules")
                JSONObject().put(
                    "modules",
                    jsonArray(canonicalEnumList(candidate.opt("modules"), modules)),
                )
            }
            "namesChanged" -> {
                requireExactFields(candidate, "event", "names")
                JSONObject().put("names", jsonArray(publicStringList(candidate.opt("names"))))
            }
            "nameMarketChanged" -> {
                requireExactFields(candidate, "event", "listingIds")
                JSONObject().put(
                    "listingIds",
                    jsonArray(publicStringList(candidate.opt("listingIds"))),
                )
            }
            "directOfferChanged" -> {
                requireExactFields(candidate, "event", "directOfferIds")
                JSONObject().put(
                    "directOfferIds",
                    jsonArray(publicStringList(candidate.opt("directOfferIds"))),
                )
            }
            "swapSessionChanged" -> {
                requireExactFields(candidate, "event", "swapSessionIds")
                JSONObject().put(
                    "swapSessionIds",
                    jsonArray(publicStringList(candidate.opt("swapSessionIds"))),
                )
            }
            "walletLocked" -> {
                requireExactFields(candidate, "event")
                JSONObject()
            }
            else -> fail("invalidEvent", "Unsupported wallet provider event")
        }
        return ProjectedWalletProviderEvent(event, payload)
    }

    private fun requireExactFields(candidate: JSONObject, vararg fields: String) {
        if (candidate.length() != fields.size || fields.any { !candidate.has(it) }) {
            fail("invalidEvent", "Native wallet event contains an unexpected field")
        }
    }

    private fun positiveSafeInteger(candidate: Any?): Long {
        val value = when (candidate) {
            is Byte -> candidate.toLong()
            is Short -> candidate.toLong()
            is Int -> candidate.toLong()
            is Long -> candidate
            else -> fail("invalidEvent", "Native wallet event sequence value is invalid")
        }
        if (value < 1L || value > MobileWalletProviderProtocol.MAX_SAFE_JSON_INTEGER) {
            fail("invalidEvent", "Native wallet event sequence value is invalid")
        }
        return value
    }

    private fun canonicalEnumList(candidate: Any?, ordered: List<String>): List<String> {
        val values = stringArray(candidate)
        if (values.size > ordered.size || values.toSet().size != values.size) {
            fail("invalidEvent", "Native wallet event enum set is invalid")
        }
        val allowed = ordered.toSet()
        if (values.any { it !in allowed } || values != ordered.filter(values::contains)) {
            fail("invalidEvent", "Native wallet event enum set is not canonical")
        }
        return values
    }

    private fun publicStringList(candidate: Any?): List<String> {
        val values = stringArray(candidate)
        if (values.size > MAX_PUBLIC_ITEMS) {
            fail("invalidEvent", "Native wallet event contains too many public values")
        }
        values.forEach { publicString(it) }
        return values
    }

    private fun stringArray(candidate: Any?): List<String> {
        val array = candidate as? JSONArray
            ?: fail("invalidEvent", "Native wallet event list is invalid")
        if (array.length() > MAX_PUBLIC_ITEMS) {
            fail("invalidEvent", "Native wallet event contains too many values")
        }
        return List(array.length()) { index ->
            array.opt(index) as? String
                ?: fail("invalidEvent", "Native wallet event list contains a non-string value")
        }
    }

    private fun publicString(candidate: String): String {
        if (
            candidate.isEmpty() || candidate.toByteArray(Charsets.UTF_8).size > MAX_PUBLIC_STRING_BYTES ||
            candidate.any { it.code !in 0x20..0x7e }
        ) {
            fail("invalidEvent", "Native wallet event public string is invalid")
        }
        return candidate
    }

    private fun enumValue(candidate: Any?, allowed: Set<String>): String {
        val value = candidate as? String
            ?: fail("invalidEvent", "Native wallet event enum value is invalid")
        if (value !in allowed) fail("invalidEvent", "Native wallet event enum value is invalid")
        return value
    }

    private fun jsonArray(values: List<String>): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    private fun fail(code: String, message: String): Nothing =
        throw WalletProviderException(code, message)
}
