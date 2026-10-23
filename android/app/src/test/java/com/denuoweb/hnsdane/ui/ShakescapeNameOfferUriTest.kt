package com.denuoweb.hnsdane.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShakescapeNameOfferUriTest {
    @Test
    fun canonicalV1UriBindsExactListingAndNetwork() {
        val id = "ab".repeat(32)
        val valid = "shakescape://name-offer/v1/$id?network=mainnet"
        assertEquals(ShakescapeNameOfferLink(id, HandshakeNetwork.Mainnet),
            ShakescapeNameOfferUri.parse(valid))
        for (invalid in listOf(
            valid.replace("v1", "v2"),
            valid.replace("ab", "AB"),
            valid + "&fee=0",
            valid.replace("mainnet", "testnet "),
            valid.replace("name-offer", "other"),
            valid.replace(id, "0".repeat(64)),
            valid.replace("?", "#fragment?"),
        )) {
            assertNull(invalid, ShakescapeNameOfferUri.parse(invalid))
        }
    }

    @Test
    fun formatsOnlyLinksAcceptedByTheParser() {
        val id = "ab".repeat(32)
        val link = ShakescapeNameOfferUri.format(id, HandshakeNetwork.Mainnet)
        assertEquals("shakescape://name-offer/v1/$id?network=mainnet", link)
        assertEquals(ShakescapeNameOfferLink(id, HandshakeNetwork.Mainnet),
            ShakescapeNameOfferUri.parse(link!!))
        assertNull(ShakescapeNameOfferUri.format("0".repeat(64), HandshakeNetwork.Mainnet))
        assertNull(ShakescapeNameOfferUri.format("AB".repeat(32), HandshakeNetwork.Mainnet))
    }
}
