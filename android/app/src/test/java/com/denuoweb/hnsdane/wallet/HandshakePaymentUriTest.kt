package com.denuoweb.hnsdane.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandshakePaymentUriTest {
    private val address = "hs1q5997733eq7f4yyk2vq2z8gz3yqyvpz422ypggh"

    @Test
    fun parses_established_handshake_payment_form() {
        assertEquals(
            HandshakePaymentRequest(address, "1.25", "Coffee shop", "Invoice 7"),
            HandshakePaymentUri.parse(
                "handshake:$address?amount=1.25&label=Coffee%20shop&message=Invoice%207",
            ),
        )
        assertEquals(address, HandshakePaymentUri.parse("handshake:$address")?.address)
    }

    @Test
    fun rejects_ambiguous_or_required_extensions() {
        assertNull(HandshakePaymentUri.parse("handshake:$address?amount=1&amount=2"))
        assertNull(HandshakePaymentUri.parse("handshake:$address?req-unknown=1"))
        assertNull(HandshakePaymentUri.parse("handshake:$address?amount=-1"))
        assertNull(HandshakePaymentUri.parse("handshake:$address#fragment"))
        assertNull(HandshakePaymentUri.parse("handshake://$address"))
    }
}
