package com.denuoweb.hnsdane.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.denuoweb.hnsdane.core.BrowserNamespaceClass
import com.denuoweb.hnsdane.core.HnsHostPolicy
import com.denuoweb.hnsdane.core.NativeGatewayHostDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedBrowserNamespacePolicyInstrumentationTest {
    @Test
    fun jniRoutesEveryDnsHostToSharedDualRootGateway() {
        assertTrue(NativeBridge.isLoaded)
        assertEquals(BrowserNamespaceClass.NativeGateway, NativeBridge.classifyHost("welcome"))
        assertEquals(BrowserNamespaceClass.NativeGateway, NativeBridge.classifyHost("sub.welcome"))
        assertEquals(BrowserNamespaceClass.NativeGateway, NativeBridge.classifyHost("example.com"))
        assertEquals(BrowserNamespaceClass.NativeGateway, NativeBridge.classifyHost("printer.local"))
        assertEquals(BrowserNamespaceClass.NativeGateway, NativeBridge.classifyHost("example.com."))
        assertEquals(BrowserNamespaceClass.Icann, NativeBridge.classifyHost("192.0.2.1"))
        assertEquals(BrowserNamespaceClass.Invalid, NativeBridge.classifyHost("two words"))
        assertEquals(
            NativeGatewayHostDecision.Required,
            HnsHostPolicy.nativeGatewayDecision("example.com", NativeBridge),
        )
    }

    @Test
    fun jniReturnsTheCompleteRustWebSocketScopePolicy() {
        val script = NativeBridge.webSocketScopePolicyScript()

        assertNotNull(script)
        requireNotNull(script)
        assertTrue(script.contains("window.__hnsRustNamespacePolicyVersion = 2"))
        assertTrue(script.contains("process-wide authenticated proxy"))
        assertFalse(script.contains("requiresHnsResolution"))
        assertFalse(script.contains("icannTlds"))
        assertFalse(script.contains("hnsWebSocketBridge"))
        assertFalse(script.contains("postMessage"))
    }
}
