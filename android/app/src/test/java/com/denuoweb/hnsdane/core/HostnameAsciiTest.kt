package com.denuoweb.hnsdane.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostnameAsciiTest {
    @Test
    fun emojiFallbackStillProducesBoundedPunycode() {
        assertEquals("xn--5p9h", HostnameAscii.toAscii("🤝"))
        assertEquals("🤝", HostnameAscii.toUnicode("xn--5p9h"))
        assertEquals("⚪", HostnameAscii.toUnicode("xn--f8h"))
    }

    @Test
    fun oversizedAndMalformedUnicodeHostsFailClosed() {
        assertNull(HostnameAscii.toAscii("a".repeat(254)))
        assertNull(HostnameAscii.toAscii("🤝".repeat(300)))
        assertNull(HostnameAscii.toAscii("\uD800"))
        assertNull(HostnameAscii.toAscii("a".repeat(64) + ".welcome"))
    }
}
