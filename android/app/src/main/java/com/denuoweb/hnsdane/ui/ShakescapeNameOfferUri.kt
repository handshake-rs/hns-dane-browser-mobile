package com.denuoweb.hnsdane.ui

/** Exact, versioned handoff for one public name listing. Never carries authority. */
internal data class ShakescapeNameOfferLink(
    val listingId: String,
    val network: HandshakeNetwork,
)

internal object ShakescapeNameOfferUri {
    private val canonical = Regex(
        "^shakescape://name-offer/v1/([0-9a-f]{64})\\?network=(mainnet|testnet|regtest)$",
    )

    fun parse(raw: String): ShakescapeNameOfferLink? {
        if (raw.length > 160) return null
        val match = canonical.matchEntire(raw) ?: return null
        val listingId = match.groupValues[1]
        if (listingId.all { it == '0' }) return null
        val network = HandshakeNetwork.entries.firstOrNull {
            it.id == match.groupValues[2]
        } ?: return null
        return ShakescapeNameOfferLink(listingId, network)
    }
}
