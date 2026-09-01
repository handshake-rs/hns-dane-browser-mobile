package com.denuoweb.hnsdane.ui

import android.content.Intent
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.core.HnsHostPolicy
import com.denuoweb.hnsdane.core.NativeGatewayHostDecision
import com.denuoweb.hnsdane.net.NativeBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HnsProofDetailsActivityInstrumentationTest {
    @Test
    fun appliedSystemBarsAreConsumedWithoutDiscardingImeInsets() {
        val source = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 51, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 320))
            .setVisible(WindowInsetsCompat.Type.statusBars(), true)
            .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
            .setVisible(WindowInsetsCompat.Type.ime(), true)
            .build()

        val descendants = consumeAppliedSystemBarInsets(source)

        assertEquals(
            Insets.NONE,
            descendants.getInsets(WindowInsetsCompat.Type.systemBars()),
        )
        assertEquals(
            Insets.of(0, 0, 0, 320),
            descendants.getInsets(WindowInsetsCompat.Type.ime()),
        )
    }

    @Test
    fun retainedHnsSelectionUsesHnsProofDetailsEvenThoughDnsHostsUseNativeGateway() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(NativeBridge.isLoaded)
        assertEquals(
            NativeGatewayHostDecision.Required,
            HnsHostPolicy.nativeGatewayDecision("welcome", NativeBridge),
        )
        val trace =
            """{"host":"welcome","nameClass":"hns","namespaceResolution":{"outcome":"hnsOnly","selected":"hns","reason":"onlyAvailableRoot","hnsState":"present","icannState":"authenticatedAbsent","fingerprint":null}}"""
        val intent = Intent(context, HnsProofDetailsActivity::class.java)
            .putExtra(HnsProofDetailsActivity.EXTRA_URL, "https://welcome/")
            .putExtra(HnsProofDetailsActivity.EXTRA_TRACE_JSON, trace)

        val scenario = ActivityScenario.launch<HnsProofDetailsActivity>(intent)
        try {
            onView(withText(R.string.screen_hns_proof_details))
                .check(matches(isDisplayed()))
            onView(withText(R.string.screen_dnssec_details))
                .check(doesNotExist())
        } finally {
            scenario.close()
        }
    }

    @Test
    fun retainedIcannSelectionUsesDnssecDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val trace =
            """{"host":"example.com","nameClass":"icann","namespaceResolution":{"outcome":"icannOnly","selected":"icann","reason":"onlyAvailableRoot","hnsState":"authenticatedAbsent","icannState":"present","fingerprint":null}}"""
        val intent = Intent(context, HnsProofDetailsActivity::class.java)
            .putExtra(HnsProofDetailsActivity.EXTRA_URL, "https://example.com/")
            .putExtra(HnsProofDetailsActivity.EXTRA_TRACE_JSON, trace)

        val scenario = ActivityScenario.launch<HnsProofDetailsActivity>(intent)
        try {
            onView(withText(R.string.screen_dnssec_details))
                .check(matches(isDisplayed()))
            onView(withText(R.string.screen_hns_proof_details))
                .check(doesNotExist())
        } finally {
            scenario.close()
        }
    }
}
