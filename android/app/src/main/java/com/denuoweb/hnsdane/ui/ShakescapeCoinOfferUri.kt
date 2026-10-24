package com.denuoweb.hnsdane.ui

/** Exact, versioned pointer to one public HNS/BTC offer. Never carries authority. */
internal data class ShakescapeCoinOfferLink(
    val offerId: String,
    val network: HandshakeNetwork,
)

internal object ShakescapeCoinOfferUri {
    private val canonical = Regex(
        "^shakescape://coin-offer/v1/([0-9a-f]{64})\\?network=(mainnet|testnet|regtest)$",
    )

    fun parse(raw: String): ShakescapeCoinOfferLink? {
        if (raw.length > 160) return null
        val match = canonical.matchEntire(raw) ?: return null
        val offerId = match.groupValues[1]
        if (offerId.all { it == '0' }) return null
        val network = HandshakeNetwork.entries.firstOrNull {
            it.id == match.groupValues[2]
        } ?: return null
        return ShakescapeCoinOfferLink(offerId, network)
    }

    fun format(offerId: String, network: HandshakeNetwork): String? {
        val uri = "shakescape://coin-offer/v1/$offerId?network=${network.id}"
        return uri.takeIf { parse(it) == ShakescapeCoinOfferLink(offerId, network) }
    }
}
