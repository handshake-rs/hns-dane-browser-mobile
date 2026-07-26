package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HnsResolutionPreferencesTest {
    @Test
    fun relayControlsUseSafeIndependentDefaults() {
        assertEquals("mainnet", HnsResolutionPreferences.DEFAULT_HANDSHAKE_NETWORK)
        assertTrue(HnsResolutionPreferences.DEFAULT_STRICT_HNS_MODE)
        assertFalse(HnsResolutionPreferences.DEFAULT_EXPERIMENTAL_P2P_DNS_RELAY)
        assertFalse(HnsResolutionPreferences.DEFAULT_LEGACY_HNS_DOH_COMPATIBILITY)
    }

    @Test
    fun buildDefaultsMatchSelectedApplicationVariant() {
        val relayTestBuild = BuildConfig.APPLICATION_ID.endsWith(".relaytest")

        assertEquals(
            if (relayTestBuild) HandshakeNetwork.Regtest else HandshakeNetwork.Mainnet,
            HnsResolutionPreferences.buildDefaultHandshakeNetwork(),
        )
        assertTrue(HnsResolutionPreferences.buildDefaultStrictHnsMode())
        assertFalse(HnsResolutionPreferences.buildDefaultExperimentalP2pDnsRelay())
        assertFalse(HnsResolutionPreferences.buildDefaultLegacyHnsDohCompatibility())
    }

    @Test
    fun handshakeNetworkFromIdSupportsKnownNetworks() {
        assertEquals(HandshakeNetwork.Mainnet, HandshakeNetwork.fromId("mainnet"))
        assertEquals(HandshakeNetwork.Testnet, HandshakeNetwork.fromId("testnet"))
        assertEquals(HandshakeNetwork.Regtest, HandshakeNetwork.fromId("regtest"))
    }

    @Test
    fun handshakeNetworkFromIdDefaultsToMainnet() {
        assertEquals(HandshakeNetwork.Mainnet, HandshakeNetwork.fromId(null))
        assertEquals(HandshakeNetwork.Mainnet, HandshakeNetwork.fromId("unknown"))
    }

    @Test
    fun normalizeStaticRelayPeerEndpointAcceptsExplicitIpPorts() {
        assertEquals(
            "1.2.3.4:13038",
            HnsResolutionPreferences.normalizeStaticRelayPeerEndpoint("001.002.003.004:13038"),
        )
        val ipv6 = HnsResolutionPreferences.normalizeStaticRelayPeerEndpoint(
            "[2001:db8::1]:14038",
        )
        assertTrue(ipv6?.startsWith("[2001:db8:") == true)
        assertTrue(ipv6?.endsWith("]:14038") == true)
    }

    @Test
    fun normalizeStaticRelayPeerEndpointRejectsAmbiguousOrUnsafeSyntax() {
        for (endpoint in listOf(
            "",
            "relay.example",
            "relay.example:12038",
            "relay.example:0",
            "relay.example:65536",
            "https://relay.example:12038",
            "user@relay.example:12038",
            "relay_example:12038",
            "1.2.3.999:12038",
            "2001:db8::1:12038",
            "[fe80::1%2]:12038",
        )) {
            assertNull(endpoint, HnsResolutionPreferences.normalizeStaticRelayPeerEndpoint(endpoint))
        }
    }
}
