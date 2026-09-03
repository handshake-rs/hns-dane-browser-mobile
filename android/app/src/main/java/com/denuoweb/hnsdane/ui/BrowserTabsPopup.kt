package com.denuoweb.hnsdane.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.denuoweb.hnsdane.R

/** Stateless renderer for the bounded metadata-only tab collection. */
internal object BrowserTabsPopup {
    fun show(
        context: Context,
        anchor: View,
        tabs: List<BrowserTab>,
        activeTabId: Long,
        primaryTextColor: Int,
        secondaryTextColor: Int,
        background: () -> Drawable,
        onSelect: (Long) -> Unit,
        onClose: (Long) -> Unit,
        onDismiss: (PopupWindow) -> Unit,
    ): PopupWindow {
        val popup = PopupWindow(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            this.background = background()
            addView(TextView(context).apply {
                text = context.getString(R.string.tabs_title)
                textSize = 14f
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(16), 0, context.dp(16), 0)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.dp(HEADER_HEIGHT_DP),
            ))
            tabs.forEach { tab ->
                addView(
                    tabRow(
                        context = context,
                        tab = tab,
                        isActive = tab.id == activeTabId,
                        primaryTextColor = primaryTextColor,
                        popup = popup,
                        onSelect = onSelect,
                        onClose = onClose,
                    ),
                )
            }
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(rows)
        }
        val popupWidth = minOf(
            context.dp(WIDTH_DP),
            context.resources.displayMetrics.widthPixels - context.dp(16),
        )
        val desiredHeight = context.dp(HEADER_HEIGHT_DP + tabs.size * ROW_HEIGHT_DP)
        popup.apply {
            contentView = scroll
            width = popupWidth
            height = minOf(desiredHeight, context.resources.displayMetrics.heightPixels * 2 / 3)
            isFocusable = true
            isOutsideTouchable = true
            setBackgroundDrawable(background())
            elevation = context.dp(8).toFloat()
            setOnDismissListener { onDismiss(this) }
        }
        popup.showAsDropDown(anchor, anchor.width - popupWidth, 0)
        return popup
    }

    private fun tabRow(
        context: Context,
        tab: BrowserTab,
        isActive: Boolean,
        primaryTextColor: Int,
        popup: PopupWindow,
        onSelect: (Long) -> Unit,
        onClose: (Long) -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            val title = tab.title.ifBlank { OmniboxDisplay.displayText(tab.url) }
            text = if (isActive) "• $title" else title
            textSize = 16f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(primaryTextColor)
            setPadding(context.dp(16), 0, context.dp(8), 0)
            contentDescription = context.getString(
                if (isActive) {
                    R.string.tab_current_content_description
                } else {
                    R.string.tab_switch_content_description
                },
                title,
            )
            isClickable = true
            isFocusable = true
            applyScreenSelectableBackground()
            setOnClickListener {
                popup.dismiss()
                onSelect(tab.id)
            }
        }, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f,
        ))
        addView(TextView(context).apply {
            text = "×"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(primaryTextColor)
            contentDescription = context.getString(R.string.tab_close_content_description)
            isClickable = true
            isFocusable = true
            applyScreenSelectableBackground()
            setOnClickListener {
                popup.dismiss()
                onClose(tab.id)
            }
        }, LinearLayout.LayoutParams(
            context.dp(CLOSE_BUTTON_WIDTH_DP),
            LinearLayout.LayoutParams.MATCH_PARENT,
        ))
    }.also { row ->
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            context.dp(ROW_HEIGHT_DP),
        )
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private const val WIDTH_DP = 320
    private const val HEADER_HEIGHT_DP = 40
    private const val ROW_HEIGHT_DP = 58
    private const val CLOSE_BUTTON_WIDTH_DP = 48
}
