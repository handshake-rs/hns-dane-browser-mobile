package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShakescapeCoinOfferUriTest {
    @Test
    fun canonicalV1UriBindsExactOfferAndNetwork() {
        val id = "ab".repeat(32)
        val valid = "shakescape://coin-offer/v1/$id?network=mainnet"
        assertEquals(ShakescapeCoinOfferLink(id, HandshakeNetwork.Mainnet),
            ShakescapeCoinOfferUri.parse(valid))
        for (invalid in listOf(
            valid.replace("v1", "v2"),
            valid.replace("ab", "AB"),
            valid + "&fee=0",
            valid.replace("mainnet", "testnet "),
            valid.replace("coin-offer", "name-offer"),
            valid.replace(id, "0".repeat(64)),
            valid.replace("?", "#fragment?"),
        )) assertNull(invalid, ShakescapeCoinOfferUri.parse(invalid))
        assertEquals(valid, ShakescapeCoinOfferUri.format(id, HandshakeNetwork.Mainnet))
        assertNull(ShakescapeCoinOfferUri.format("0".repeat(64), HandshakeNetwork.Mainnet))
    }
}
