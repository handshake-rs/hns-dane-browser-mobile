package com.denuoweb.hnsdane.ui

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
        assertTrue(HnsResolutionPreferences.DEFAULT_STATELESS_DANE_CERTIFICATES)
        assertFalse(HnsResolutionPreferences.DEFAULT_EXPERIMENTAL_P2P_DNS_RELAY)
        assertFalse(HnsResolutionPreferences.DEFAULT_LEGACY_HNS_DOH_COMPATIBILITY)
    }

    @Test
    fun buildDefaultsMatchSelectedApplicationVariant() {
        assertEquals(
            HandshakeNetwork.Mainnet,
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
    fun directWalletAlwaysSuppliesPinnedBootstrapOnMainnetReinstall() {
        // The direct controller uses the encrypted checkpoint and Keystore
        // floor to resume.  Supplying this source again is required for a
        // checkpoint-born wallet and must not depend on whether that floor is
        // still zero after a prior scan.
        assertTrue(walletDirectHnsNeedsGenesisBootstrap(HandshakeNetwork.Mainnet))
        assertFalse(walletDirectHnsNeedsGenesisBootstrap(HandshakeNetwork.Testnet))
        assertFalse(walletDirectHnsNeedsGenesisBootstrap(HandshakeNetwork.Regtest))
    }

    @Test
    fun handshakeNetworkFromIdDefaultsToMainnet() {
        assertEquals(HandshakeNetwork.Mainnet, HandshakeNetwork.fromId(null))
        assertEquals(HandshakeNetwork.Mainnet, HandshakeNetwork.fromId("unknown"))
    }

    @Test
    fun normalizeHnsDohRecoveryUrlAcceptsOnlyBoundedPublicHttpsEndpoints() {
        assertEquals("", HnsResolutionPreferences.normalizeHnsDohRecoveryUrl("  "))
        assertEquals(
            "https://resolver.example.net/dns-query?profile=hns",
            HnsResolutionPreferences.normalizeHnsDohRecoveryUrl(
                " HTTPS://Resolver.Example.NET.:443/dns-query?profile=hns ",
            ),
        )
        assertEquals(
            "https://resolver.example.net:8443/dns-query",
            HnsResolutionPreferences.normalizeHnsDohRecoveryUrl(
                "https://resolver.example.net:8443/dns-query",
            ),
        )

        for (endpoint in listOf(
            "http://resolver.example.net/dns-query",
            "https://user@resolver.example.net/dns-query",
            "https://resolver.example.net/dns-query#fragment",
            "https://resolver.example.net",
            "https://127.0.0.1/dns-query",
            "https://resolver.local/dns-query",
            "https://resolver.example.net:53/dns-query",
            "https://resolver.example.net:6000/dns-query",
            "https://resolver.example.net/{?dns}",
            "https://resolver.example.net/${"x".repeat(2_048)}",
        )) {
            assertNull(endpoint, HnsResolutionPreferences.normalizeHnsDohRecoveryUrl(endpoint))
        }
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
