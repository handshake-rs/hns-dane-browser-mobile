package com.denuoweb.hnsdane.ui

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnsResolutionPreferencesInstrumentationTest {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferences
        get() = context.getSharedPreferences(
            "hns_resolution_preferences",
            Context.MODE_PRIVATE,
        )

    @Before
    @After
    fun clearPreferences() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun migrationPermanentlyRemovesLegacyHnsFallbackSettings() {
        assertTrue(
            preferences.edit()
                .putBoolean("strict_hns_mode", false)
                .putBoolean("legacy_hns_doh_compatibility", true)
                .putString("doh_resolver_url", "https://resolver.example/dns-query")
                .commit(),
        )

        HnsResolutionPreferences.migrateProhibitedHnsFallbackSettings(context)

        assertTrue(preferences.getBoolean("strict_hns_mode", false))
        assertFalse(preferences.getBoolean("legacy_hns_doh_compatibility", true))
        assertFalse(preferences.contains("doh_resolver_url"))
        assertTrue(HnsResolutionPreferences.strictHnsMode(context))
        assertFalse(HnsResolutionPreferences.legacyHnsDohCompatibility(context))
        assertEquals("", HnsResolutionPreferences.dohResolverUrl(context))
        assertFalse(HnsResolutionPreferences.experimentalP2pDnsRelay(context))
    }

    @Test
    fun migrationPreservesAnIndependentRelayPreference() {
        assertTrue(
            preferences.edit()
                .putBoolean("legacy_hns_doh_compatibility", true)
                .putBoolean("experimental_p2p_dns_relay", true)
                .commit(),
        )

        HnsResolutionPreferences.migrateProhibitedHnsFallbackSettings(context)

        assertTrue(HnsResolutionPreferences.experimentalP2pDnsRelay(context))
    }

    @Test
    fun freshInstallRetainsRelayRequesterDefault() {
        HnsResolutionPreferences.migrateProhibitedHnsFallbackSettings(context)

        assertFalse(preferences.contains("experimental_p2p_dns_relay"))
        assertTrue(HnsResolutionPreferences.experimentalP2pDnsRelay(context))
    }
}
