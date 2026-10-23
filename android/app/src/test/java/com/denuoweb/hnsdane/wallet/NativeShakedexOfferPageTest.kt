package com.denuoweb.hnsdane.wallet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeShakedexOfferPageTest {
    @Test
    fun exactAuthenticatedOfferPageBecomesASelectableProjection() {
        val page = requireNotNull(NativeShakedexQueryResult(validPage().toString()).offerPage())

        assertEquals(7L, page.boardRevision)
        assertNull(page.nextCursor)
        assertEquals(1, page.offers.size)
        with(page.offers.single()) {
            assertEquals("24hour", name)
            assertEquals("100000", priceBaseUnits)
            assertEquals("0", marketplaceFeeBaseUnits)
            assertEquals("ab".repeat(32), listingId)
            assertEquals(1_789_280_076L, createdAtUnix)
            assertEquals(1_789_884_876L, expiresAtUnix)
        }
    }

    @Test
    fun malformedOrShapeDriftedPagesNeverBecomeSelectable() {
        fun rejected(mutator: (JSONObject) -> Unit) {
            val value = validPage()
            mutator(value)
            assertNull(NativeShakedexQueryResult(value.toString()).offerPage())
        }

        rejected { it.put("untrusted", true) }
        rejected { it.getJSONObject("offers", 0).put("listingId", "AB".repeat(32)) }
        rejected { it.getJSONObject("offers", 0).put("price", "0100000") }
        rejected { it.getJSONObject("offers", 0).put("name", "contains space") }
        rejected { it.getJSONObject("offers", 0).put("expiresAtUnix", 1_789_280_076L) }
        rejected { it.put("nextCursor", "0".repeat(64)) }

        val tooMany = validPage().apply {
            val offer = getJSONArray("offers").getJSONObject(0)
            put("offers", JSONArray().apply {
                repeat(65) { put(JSONObject(offer.toString())) }
            })
        }
        assertNull(NativeShakedexQueryResult(tooMany.toString()).offerPage())
        assertTrue(NativeShakedexQueryResult("[]").offerPage() == null)
    }

    @Test
    fun sellerPageKeepsOnlyAccountScopedNativeProjectionSelectable() {
        val offer = JSONObject()
            .put("revision", 1)
            .put("sessionId", "ab".repeat(32))
            .put("requestNonce", "1")
            .put("stage", "publicationQueued")
            .put("name", "24hour")
            .put("price", "100000")
            .put("lockAddress", "hs1qfixture")
            .put("listingLifetimeSeconds", 86400)
            .put("listingId", "cd".repeat(32))
            .put("cancellationId", JSONObject.NULL)
            .put("publicationState", "relayAccepted")
        val page = JSONObject().put("offers", JSONArray().put(offer))
            .put("nextCursor", JSONObject.NULL)
        val decoded = requireNotNull(NativeShakedexQueryResult(page.toString()).myOfferPage())
        assertEquals("ab".repeat(32), decoded.offers.single().sessionId)
        assertEquals("relayAccepted", decoded.offers.single().publicationState)

        val extra = JSONObject(page.toString()).apply {
            getJSONArray("offers").getJSONObject(0).put("account", "caller-controlled")
        }
        assertNull(NativeShakedexQueryResult(extra.toString()).myOfferPage())
        val invalidStage = JSONObject(page.toString()).apply {
            getJSONArray("offers").getJSONObject(0).put("stage", "settled")
        }
        assertNull(NativeShakedexQueryResult(invalidStage.toString()).myOfferPage())
    }

    @Test
    fun exactOfferLookupReusesAuthenticatedRowValidation() {
        val offer = validPage().getJSONArray("offers").getJSONObject(0)
        val detail = JSONObject().put("offer", offer)
        assertEquals("ab".repeat(32),
            NativeShakedexQueryResult(detail.toString()).offerDetail()?.listingId)
        detail.put("untrusted", true)
        assertNull(NativeShakedexQueryResult(detail.toString()).offerDetail())
    }

    private fun validPage(): JSONObject = JSONObject()
        .put("boardRevision", 7L)
        .put(
            "offers",
            JSONArray().put(
                JSONObject()
                    .put("listingId", "ab".repeat(32))
                    .put("name", "24hour")
                    .put("price", "100000")
                    .put("marketplaceFee", "0")
                    .put("sellerPaymentAddress", "hs1qfixture")
                    .put("createdAtUnix", 1_789_280_076L)
                    .put("expiresAtUnix", 1_789_884_876L),
            ),
        )
        .put("nextCursor", JSONObject.NULL)

    private fun JSONObject.getJSONObject(arrayName: String, index: Int): JSONObject =
        getJSONArray(arrayName).getJSONObject(index)
}
