package com.denuoweb.hnsdane.ui

import android.content.Context
import android.graphics.Color

internal data class ThemeColors(
    val background: Int,
    val surface: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val action: Int,
    val actionContainer: Int,
    val onAction: Int,
    val destructive: Int,
    val divider: Int,
    val securityText: Int,
    val secondaryAction: Int,
)

internal fun Context.themeColors(): ThemeColors =
    if (BrowserThemePreferences.effectiveDark(this)) {
        ThemeColors(
            // Shakescape's night palette: deep navy keeps the browser calm,
            // while cyan is reserved for a healthy state or a deliberate action.
            background = Color.rgb(10, 14, 23),
            surface = Color.rgb(14, 21, 37),
            primaryText = Color.rgb(230, 243, 255),
            secondaryText = Color.rgb(145, 164, 184),
            action = Color.rgb(0, 255, 224),
            actionContainer = Color.rgb(22, 48, 63),
            onAction = Color.rgb(10, 14, 23),
            destructive = Color.rgb(255, 105, 148),
            divider = Color.rgb(38, 53, 72),
            securityText = Color.rgb(0, 255, 224),
            secondaryAction = Color.rgb(120, 92, 255),
        )
    } else {
        ThemeColors(
            background = Color.WHITE,
            surface = Color.WHITE,
            primaryText = Color.rgb(32, 33, 36),
            secondaryText = Color.rgb(95, 99, 104),
            action = Color.rgb(21, 101, 192),
            actionContainer = Color.rgb(232, 240, 254),
            onAction = Color.WHITE,
            destructive = Color.rgb(183, 28, 28),
            divider = Color.rgb(218, 220, 224),
            securityText = Color.rgb(28, 71, 75),
            secondaryAction = Color.rgb(82, 70, 168),
        )
    }
