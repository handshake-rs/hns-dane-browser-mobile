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
 * The activity root already keeps every child inside the system bars. Do not
 * forward those same window-relative insets to descendants: WebView exposes
 * forwarded values as CSS safe-area insets even though its own viewport starts
 * below the browser toolbar, causing pages to reserve the status bar twice.
 */
internal fun consumeAppliedSystemBarInsets(insets: WindowInsets): WindowInsets =
    WindowInsets.Builder(insets)
        .setInsets(SYSTEM_BAR_INSET_TYPES, Insets.NONE)
        .setInsetsIgnoringVisibility(SYSTEM_BAR_INSET_TYPES, Insets.NONE)
        .setDisplayCutout(null)
        .build()
