package com.denuoweb.hnsdane.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Checks the actual dialog layout using dummy words in the isolated fixture package. */
@RunWith(AndroidJUnit4::class)
class RecoveryPhraseDialogInstrumentationTest {
    @Test
    fun attachedDialogRendersEveryLineOfSyntheticRecoveryWords() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".walletuxfixture"))
        val scenario = ActivityScenario.launch<WalletActivity>(Intent(context, WalletActivity::class.java))
        var recoveryView: View? = null
        var recoveryDialog: android.app.AlertDialog? = null
        try {
            scenario.onActivity { activity ->
                val words = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima " +
                    "mike november oscar papa quebec romeo sierra tango uniform victor whiskey xray"
                WalletActivity::class.java.getDeclaredMethod("showRecoveryPhraseDialog", CharArray::class.java)
                    .apply { isAccessible = true }.invoke(activity, words.toCharArray())
                @Suppress("UNCHECKED_CAST")
                val dialogs = WalletActivity::class.java.getDeclaredField("walletPopupDialogs")
                    .apply { isAccessible = true }.get(activity) as Set<android.app.AlertDialog>
                recoveryDialog = dialogs.last()
                recoveryView = findRecoveryView(requireNotNull(recoveryDialog?.window?.decorView))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                val view = requireNotNull(recoveryView)
                try {
                    assertTrue("Recovery view must have room for multiple lines: ${view.height}", view.height > 130)
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val background = bitmap.getPixel(0, 0)
                    val inkPixels = (0 until bitmap.height).sumOf { y ->
                        (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) != background }
                    }
                    assertTrue("Recovery view rendered no text pixels", inkPixels > 100)
                    bitmap.recycle()
                } finally {
                    recoveryDialog?.dismiss()
                }
            }
        } finally {
            scenario.close()
        }
    }

    private fun findRecoveryView(view: View): View? {
        if (view.javaClass.simpleName == "RecoveryPhraseView") return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecoveryView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
