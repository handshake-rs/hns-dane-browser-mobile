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
