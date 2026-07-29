package com.denuoweb.hnsdane.net

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.denuoweb.hnsdane.core.BrowserNamespaceClass
import com.denuoweb.hnsdane.core.HnsHostPolicy
import com.denuoweb.hnsdane.core.NativeGatewayHostDecision
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedBrowserNamespacePolicyInstrumentationTest {
    @Test
    fun freshAndroidStorageOpensTheNativeRuntime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataDir = context.filesDir.resolve("runtime-open-instrumentation-${UUID.randomUUID()}")
        assertTrue(dataDir.mkdirs())

        try {
            assertTrue(NativeBridge.isLoaded)
            val status = JSONObject(NativeBridge.syncStatus(dataDir.absolutePath, "regtest"))

            assertEquals(2, status.getInt("syncStatusSchemaVersion"))
            assertEquals("regtest", status.getString("network"))
            assertEquals("idle", status.getString("status"))
            assertEquals(0L, status.getLong("bestHeight"))
            assertTrue(status.has("error"))
            assertTrue(status.isNull("error"))
        } finally {
            NativeBridge.closeRuntimes()
            dataDir.deleteRecursively()
        }
    }

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
