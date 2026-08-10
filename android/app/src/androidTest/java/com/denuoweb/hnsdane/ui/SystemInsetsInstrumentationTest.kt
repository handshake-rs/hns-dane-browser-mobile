package com.denuoweb.hnsdane.ui

import android.graphics.Insets
import android.view.WindowInsets
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemInsetsInstrumentationTest {
    @Test
    fun appliedSystemBarsAreConsumedWithoutDiscardingImeInsets() {
        val source = WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, 51, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, 24))
            .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 320))
            .setVisible(WindowInsets.Type.statusBars(), true)
            .setVisible(WindowInsets.Type.navigationBars(), true)
            .setVisible(WindowInsets.Type.ime(), true)
            .build()

        val descendants = consumeAppliedSystemBarInsets(source)

        assertEquals(
            Insets.NONE,
            descendants.getInsets(WindowInsets.Type.systemBars()),
        )
        assertEquals(
            Insets.of(0, 0, 0, 320),
            descendants.getInsets(WindowInsets.Type.ime()),
        )
    }
}
