package com.denuoweb.hnsdane.ui

import android.graphics.Insets
import android.view.View
import android.view.WindowInsets

private val SYSTEM_BAR_INSET_TYPES =
    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()

internal fun View.applySystemBarPadding() {
    val initialLeft = paddingLeft
    val initialTop = paddingTop
    val initialRight = paddingRight
    val initialBottom = paddingBottom

    setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(SYSTEM_BAR_INSET_TYPES)
        view.setPadding(
            initialLeft + bars.left,
            initialTop + bars.top,
            initialRight + bars.right,
            initialBottom + bars.bottom,
        )
        consumeAppliedSystemBarInsets(insets)
    }
    if (isAttachedToWindow) {
        requestApplyInsets()
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                view.requestApplyInsets()
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}

/**
 * The root has already applied system-bar and display-cutout insets as native
 * padding. Clear only those inset types before dispatching to descendants so
 * WebView reports CSS safe-area values relative to its already-inset viewport.
 * Preserve IME and all other inset types.
 */
internal fun consumeAppliedSystemBarInsets(insets: WindowInsets): WindowInsets =
    WindowInsets.Builder(insets)
        .setInsets(SYSTEM_BAR_INSET_TYPES, Insets.NONE)
        .setInsetsIgnoringVisibility(SYSTEM_BAR_INSET_TYPES, Insets.NONE)
        .setDisplayCutout(null)
        .build()
