package com.denuoweb.hnsdane.ui

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.wallet.NativeBitcoinWalletSnapshot
import com.denuoweb.hnsdane.wallet.NativeWalletReadSnapshot
import com.denuoweb.hnsdane.wallet.NativeWalletPaymentReceiveTarget
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * View-only fixtures: no native handle, keys, peer, signing, or wallet database.
 * Run in the dedicated .walletuxfixture application ID so no installed wallet is used.
 * Reflection keeps test fixture injection out of the production controller API.
 */
@RunWith(AndroidJUnit4::class)
class WalletOverviewInstrumentationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun recoverySetupAndSyncControlsChangeWhileTheSheetStaysOpen() = fixture { scenario ->
        scenario.onActivity { activity ->
            set(activity, "bitcoinSnapshot", snapshot("recoveryUnknown"))
            status(activity).text = ""
            invoke(activity, "showBitcoinDashboard")
        }
        capture("bitcoin-recovery-open")
        onView(withText(R.string.wallet_ux_recovery_start)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.wallet_dashboard_sync)).inRoot(isDialog()).check(doesNotExist())
        capture("bitcoin-recovery")

        scenario.onActivity { set(it, "bitcoinSnapshot", snapshot("recoveryPendingValidation")) }
        waitForMenuRefresh()
        onView(withText(R.string.wallet_ux_recovery_start)).inRoot(isDialog()).check(doesNotExist())
        onView(withText(R.string.wallet_dashboard_sync)).inRoot(isDialog()).check(matches(isDisplayed()))
        // A display snapshot must never manufacture signing authority.
        onView(withText(R.string.wallet_dashboard_send_bitcoin)).inRoot(isDialog()).check(matches(not(isEnabled())))

        scenario.onActivity { set(it, "walletBitcoinSyncInProgress", true) }
        waitForMenuRefresh()
        onView(withText(R.string.action_stop_bitcoin_sync)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.wallet_dashboard_sync)).inRoot(isDialog()).check(doesNotExist())
        capture("bitcoin-syncing")

        scenario.onActivity { set(it, "bitcoinSyncStopRequested", true) }
        waitForMenuRefresh()
        onView(withText(R.string.action_stop_bitcoin_sync)).inRoot(isDialog()).check(matches(not(isEnabled())))
    }

    @Test
    fun receiveCopiesTheCurrentAddressWithoutDerivingAnother() = fixture { scenario ->
        val snapshot = snapshot("validated")
        scenario.onActivity {
            set(it, "bitcoinSnapshot", snapshot)
            invoke(it, "showBitcoinReceiveAddress")
        }
        capture("bitcoin-receive-open")
        onView(withText(snapshot.receiveAddress)).inRoot(isDialog()).check(matches(isDisplayed()))
        capture("bitcoin-receive")
        onView(withText(R.string.wallet_dashboard_copy_address)).inRoot(isDialog()).perform(click())
        scenario.onActivity {
            assertEquals(snapshot, get(it, "bitcoinSnapshot"))
            val clipboard = it.getSystemService(ClipboardManager::class.java)
            assertEquals(snapshot.receiveAddress, clipboard.primaryClip?.getItemAt(0)?.text.toString())
        }
    }

    @Test
    fun disconnectedShakedexExplainsThePrerequisiteAndKeepsDetailsReachable() = fixture { scenario ->
        scenario.onActivity { invoke(it, "showShakedexDashboard") }
        onView(withText(R.string.row_wallet_pair_direct_shakescape)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.wallet_swap_available_offers)).inRoot(isDialog()).check(matches(not(isEnabled())))
        onView(withText(R.string.row_wallet_retry_direct_shakescape_host)).inRoot(isDialog()).check(doesNotExist())
        capture("shakedex-disconnected")
    }

    @Test
    fun homeAndEmptyNamesGiveFeaturesAndOnboardingDedicatedSpace() = fixture { scenario ->
        scenario.onActivity { activity ->
            set(activity, "latestReadSnapshot", NativeWalletReadSnapshot(
                balanceBaseUnits = "12345000",
                paymentReceiveTarget = NativeWalletPaymentReceiveTarget("fixture", "hs1qfixture", 0),
                nameReceiveTarget = null, height = 350_000, transactions = emptyList(), trackedNames = emptyList(),
            ))
            set(activity, "bitcoinSnapshot", snapshot("recoveryUnknown"))
            (get(activity, "statusView") as TextView).text = activity.getString(R.string.wallet_status_unlocked)
            (get(activity, "balanceView") as TextView).text = "12.345 HNS"
            (get(activity, "sendStatusView") as TextView).text = activity.getString(R.string.wallet_send_ready, 350_000)
            clearDashboard(activity)
            WalletActivity::class.java.getDeclaredMethod("renderUnlockedWalletDashboard",
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(activity, false, false, false)
        }
        onView(withText("12.345 HNS")).check(matches(isDisplayed()))
        onView(withText(R.string.wallet_ux_setup_required)).check(matches(isDisplayed()))
        capture("wallet-home")
        scenario.onActivity { activity ->
            clearDashboard(activity)
            WalletActivity::class.java.getDeclaredMethod("renderNamesPage", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, false)
        }
        onView(withText(R.string.wallet_ux_add_names)).check(matches(isDisplayed()))
        onView(withText(R.string.wallet_ux_names_empty)).check(matches(isDisplayed()))
        capture("names-empty")
    }

    private fun clearDashboard(activity: WalletActivity) {
        listOf("statusView", "readStatusView", "balanceView", "sendStatusView").forEach { name ->
            val view = get(activity, name) as View
            (view.parent as? ViewGroup)?.removeView(view)
        }
        (get(activity, "dashboardContent") as LinearLayout).removeAllViews()
    }

    private fun fixture(test: (ActivityScenario<WalletActivity>) -> Unit) {
        assumeTrue("Use the isolated walletuxfixture application ID", context.packageName.endsWith(".walletuxfixture"))
        val scenario = ActivityScenario.launch<WalletActivity>(Intent(context, WalletActivity::class.java))
        instrumentation.waitForIdleSync()
        try { test(scenario) } finally {
            scenario.onActivity {
                set(it, "walletBitcoinSyncInProgress", false)
                set(it, "bitcoinSyncStopRequested", false)
            }
            scenario.close()
        }
    }

    private fun snapshot(state: String) = NativeBitcoinWalletSnapshot(
        network = "mainnet", receiveAddress = "bc1qfixture000000000000000000000000000000000",
        confirmedSats = 125_000, trustedPendingSats = 0, untrustedPendingSats = 0,
        immatureSats = 0, totalSats = 125_000, birthdayHeight = 800_000,
        birthdayState = state, synchronizedHeight = if (state == "validated") 900_000 else 0,
        connectedPeerCount = 0, requiredPeerCount = 2, recentActivity = emptyList(), recentActivityTotal = 0,
    )

    private fun status(activity: WalletActivity) = get(activity, "bitcoinStatusView") as TextView
    private fun get(activity: WalletActivity, name: String): Any? =
        WalletActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun set(activity: WalletActivity, name: String, value: Any) {
        WalletActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
    private fun invoke(activity: WalletActivity, name: String) {
        WalletActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    }
    private fun waitForMenuRefresh() {
        Thread.sleep(650)
        instrumentation.waitForIdleSync()
    }
    private fun capture(name: String) {
        // Idle does not guarantee SurfaceFlinger has presented the new frame.
        Thread.sleep(250)
        instrumentation.waitForIdleSync()
        val directory = requireNotNull(context.getExternalFilesDir("wallet-ux-previews"))
        File(directory, "$name-${System.currentTimeMillis()}.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
