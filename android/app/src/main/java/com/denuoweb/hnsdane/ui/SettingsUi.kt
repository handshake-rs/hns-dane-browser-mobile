package com.denuoweb.hnsdane.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * The small, shared vocabulary for settings-like screens.
 *
 * Navigation always ends with a chevron, toggles change state in place, and
 * visible labels are reserved for commands. Keeping that distinction here
 * prevents individual screens from drifting back into preference-list prose.
 */
internal fun ComponentActivity.setSettingsScreen(
    title: String,
    content: LinearLayout.() -> Unit,
) = setSecondaryScreen(title = title, content = content)

internal fun Context.settingsGroup(
    title: String? = null,
    content: LinearLayout.() -> Unit,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, uiDp(8), 0, uiDp(12))
        title?.takeIf(String::isNotBlank)?.let { addView(settingsGroupHeading(it)) }
        addView(LinearLayout(this@settingsGroup).apply {
            orientation = LinearLayout.VERTICAL
            background = settingsSurfaceDrawable()
            clipToOutline = true
            content()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

internal fun Context.navRow(
    title: String,
    summary: String? = null,
    action: () -> Unit,
): LinearLayout =
    settingsRow(
        title = title,
        summary = summary,
        trailing = "›",
        action = action,
    )

internal fun Context.navRow(
    title: String,
    summaryView: TextView,
    action: () -> Unit,
): LinearLayout =
    settingsRow(
        title = title,
        summaryView = summaryView,
        trailing = "›",
        action = action,
    )

internal fun Context.valueRow(
    title: String,
    value: String,
): LinearLayout =
    settingsRow(
        title = title,
        summary = value,
    )

internal fun Context.toggleRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = uiDp(64)
        setPadding(uiDp(16), uiDp(10), uiDp(12), uiDp(10))

        addView(LinearLayout(this@toggleRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(preferenceTitle(title))
            summary?.let { addView(preferenceSummary(it, maxLines = 1)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(CheckBox(this@toggleRow).apply {
            isChecked = checked
            contentDescription = title
            setOnCheckedChangeListener { _, value -> onCheckedChange(value) }
        })
    }

internal fun Context.actionRow(
    title: String,
    summary: String? = null,
    destructive: Boolean = false,
    action: () -> Unit,
): LinearLayout =
    settingsRow(
        title = title,
        summary = summary,
        actionColor = if (destructive) themeColors().destructive else themeColors().action,
        action = action,
    )

internal fun Context.statusCard(
    label: String,
    detail: TextView,
    healthy: Boolean = true,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = settingsSurfaceDrawable(accent = if (healthy) themeColors().action else themeColors().secondaryAction)
        setPadding(uiDp(16), uiDp(14), uiDp(16), uiDp(14))
        addView(TextView(this@statusCard).apply {
            text = label
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(if (healthy) themeColors().action else themeColors().secondaryAction)
        })
        addView(detail.apply { setPadding(0, uiDp(6), 0, 0) })
    }

internal fun Context.dashboardActionButton(
    text: String,
    secondary: Boolean = false,
    action: () -> Unit,
): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minimumHeight = uiDp(42)
        setPadding(uiDp(10), uiDp(6), uiDp(10), uiDp(6))
        setTextColor(if (secondary) themeColors().secondaryAction else themeColors().action)
        background = settingsSurfaceDrawable(
            accent = if (secondary) themeColors().secondaryAction else themeColors().action,
            fill = themeColors().background,
            cornerRadius = 12,
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

internal fun Context.dashboardTile(
    title: String,
    summary: String,
    action: () -> Unit,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        minimumHeight = uiDp(108)
        background = settingsSurfaceDrawable()
        setPadding(uiDp(14), uiDp(14), uiDp(14), uiDp(12))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        addView(TextView(this@dashboardTile).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(themeColors().primaryText)
        })
        addView(TextView(this@dashboardTile).apply {
            text = summary
            textSize = 13f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(themeColors().secondaryText)
            setPadding(0, uiDp(6), 0, 0)
        })
        addView(TextView(this@dashboardTile).apply {
            text = "›"
            textSize = 20f
            gravity = Gravity.END
            setTextColor(themeColors().action)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
    }

internal fun LinearLayout.addSettingsRow(row: View) {
    addView(row, LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ))
    addView(screenDivider())
}

private fun Context.settingsRow(
    title: String,
    summary: String? = null,
    summaryView: TextView? = null,
    trailing: String? = null,
    actionColor: Int? = null,
    action: (() -> Unit)? = null,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = uiDp(64)
        setPadding(uiDp(16), uiDp(10), uiDp(14), uiDp(10))
        if (action != null) {
            isClickable = true
            isFocusable = true
            applyScreenSelectableBackground()
            setOnClickListener { action() }
        }
        addView(LinearLayout(this@settingsRow).apply {
            orientation = LinearLayout.VERTICAL
            addView(preferenceTitle(title))
            summaryView?.let { addView(it.apply { maxLines = 1 }) }
                ?: summary?.let { addView(preferenceSummary(it, maxLines = 1)) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        trailing?.let {
            addView(TextView(this@settingsRow).apply {
                text = it
                textSize = 24f
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                minWidth = uiDp(28)
                setTextColor(actionColor ?: themeColors().action)
            })
        }
    }

private fun Context.settingsGroupHeading(text: String): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setTextColor(themeColors().secondaryText)
        setPadding(uiDp(4), uiDp(10), uiDp(4), uiDp(7))
    }

internal fun Context.settingsSurfaceDrawable(
    accent: Int? = null,
    fill: Int = themeColors().surface,
    cornerRadius: Int = 16,
): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        setStroke(uiDp(1), accent ?: themeColors().divider)
        setCornerRadius(uiDp(cornerRadius).toFloat())
    }
