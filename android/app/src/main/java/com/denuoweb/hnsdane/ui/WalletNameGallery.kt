package com.denuoweb.hnsdane.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

internal data class WalletNameCardStat(
    val label: String,
    val value: String,
    val monospace: Boolean = false,
)

internal data class WalletNameCardRow(val stats: List<WalletNameCardStat>) {
    init {
        require(stats.size in 1..2)
    }
}

internal data class WalletNameCardSection(
    val title: String,
    val rows: List<WalletNameCardRow>,
)

internal fun walletNamePageOffsetForIndex(index: Int, pageSize: Int, total: Int): Int? {
    if (index !in 0 until total || pageSize <= 0) return null
    return (index / pageSize) * pageSize
}

internal fun canonicalTrackedNameSearchText(raw: String): String? {
    val candidate = raw.trim().lowercase().removeSuffix(".")
    return candidate.takeIf(::isCanonicalHandshakeNameText)
}

/** A stable on-card fingerprint; the full value remains available to the records editor. */
internal fun compactRawResourceHex(raw: String, maxCharacters: Int = 72): String {
    require(maxCharacters >= 16)
    if (raw.length <= maxCharacters) return raw
    val suffixLength = maxCharacters / 4
    val prefixLength = maxCharacters - suffixLength - 1
    return raw.take(prefixLength) + "…" + raw.takeLast(suffixLength)
}

/** A native metallic collectible that follows a two-axis drag in perspective. */
internal class MetallicNameCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    // shakescape.com/brand — keep these values literal so the rendered card
    // remains auditable against the published brand palette.
    private val brandNavy = Color.parseColor("#0A0E17")
    private val brandIce = Color.parseColor("#E6F3FF")
    private val brandCyan = Color.parseColor("#00FFE0")
    private val brandViolet = Color.parseColor("#785CFF")
    private val brandMagenta = Color.parseColor("#FF2E9E")
    private val brandYellow = Color.parseColor("#FFD35C")
    private val orbitron = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/Orbitron-VariableFont_wght.ttf")
    }.getOrElse {
        Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = brandCyan.withAlpha(220)
    }
    private val fineLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = brandIce.withAlpha(135)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandIce
        typeface = orbitron
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.055f
    }
    private val titleEffectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeMiter = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandIce.withAlpha(225)
        typeface = orbitron
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandYellow
        typeface = orbitron
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        letterSpacing = 0.08f
    }
    private val statLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandCyan
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.06f
    }
    private val statValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandIce
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val statPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = brandNavy.withAlpha(155)
    }
    private var displayedName = "—"
    private var displayedState = "NO TRACKED NAME"
    private var displayedSections: List<WalletNameCardSection> = emptyList()
    private var highlightX = 0.5f
    private var highlightY = 0.5f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var tiltGestureClaimed = false

    init {
        cameraDistance = 8_000f * density
        setLayerType(LAYER_TYPE_HARDWARE, null)
        minimumHeight = (230f * density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(
        name: String?,
        state: String,
        sections: List<WalletNameCardSection> = emptyList(),
    ) {
        displayedName = name ?: "—"
        displayedState = state.uppercase()
        displayedSections = sections
        contentDescription = if (name == null) {
            "No tracked Handshake name"
        } else {
            "Tracked Handshake name $name. $state"
        }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        labelPaint.textSize = 9f * density
        configureStatPaints()
        val textWidth = (width - 48f * density).coerceAtLeast(1f)
        val statusLineCount = fittedStatusLines(displayedState, textWidth).size
        val bodyHeight = cardSectionsHeight(textWidth)
        val desiredHeight = maxOf(
            minimumHeight,
            (width * 1.4f).toInt(),
            (112f * density + statusLineCount * 12f * density + bodyHeight).toInt(),
        )
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Perspective projects the far corner beyond its unrotated position
        // when X and Y tilt are combined. Keep the laminated edge well inside
        // the view's hardware texture so neither projected vertical edge can
        // be clipped at the maximum gesture angle.
        val horizontalInset = 12f * density
        val verticalInset = 22f * density
        val left = horizontalInset
        val top = verticalInset
        val right = width - horizontalInset
        val bottom = height - verticalInset
        val radius = 22f * density
        val cardPath = Path().apply {
            addRoundRect(left, top, right, bottom, radius, radius, Path.Direction.CW)
        }

        // The dark substrate keeps the card recognizable at every viewing
        // angle. The foil laminate drawn over it supplies the moving color.
        fill.shader = LinearGradient(
            left,
            top,
            right,
            bottom,
            intArrayOf(
                brandNavy,
                brandViolet,
                brandNavy,
                brandMagenta,
                brandNavy,
            ),
            floatArrayOf(0f, 0.28f, 0.53f, 0.76f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(cardPath, fill)
        drawHolographicLaminate(canvas, cardPath, left, top, right, bottom)

        // A three-stage edge reads like a physically laminated card: violet
        // bloom, cyan foil edge, then a hairline ice specular.
        outline.shader = null
        outline.color = brandViolet.withAlpha(95)
        outline.strokeWidth = 7f * density
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, outline)
        outline.color = brandCyan.withAlpha(235)
        outline.strokeWidth = 2.2f * density
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, outline)
        fineLine.color = brandIce.withAlpha(180)
        canvas.drawRoundRect(
            left + 8f * density,
            top + 8f * density,
            right - 8f * density,
            bottom - 8f * density,
            radius - 7f * density,
            radius - 7f * density,
            fineLine,
        )

        val cut = 27f * density
        val path = Path().apply {
            moveTo(left + cut, top + 16f * density)
            lineTo(left + 16f * density, top + 16f * density)
            lineTo(left + 16f * density, top + cut)
            moveTo(right - cut, bottom - 16f * density)
            lineTo(right - 16f * density, bottom - 16f * density)
            lineTo(right - 16f * density, bottom - cut)
        }
        outline.color = brandCyan.withAlpha(235)
        outline.strokeWidth = 2.2f * density
        canvas.drawPath(path, outline)
        fill.shader = null

        titlePaint.textSize = 34f * density
        val available = width - 72f * density
        if (titlePaint.measureText(displayedName) > available) {
            titlePaint.textSize *= available / titlePaint.measureText(displayedName)
        }
        val titleX = width / 2f
        val titleCenterY = top + 43f * density
        val titleY = titleCenterY - (titlePaint.ascent() + titlePaint.descent()) / 2f
        titlePaint.shader = LinearGradient(
            width * (highlightX - 0.32f),
            titleY,
            width * (highlightX + 0.32f),
            titleY,
            intArrayOf(
                brandViolet,
                brandCyan,
                brandIce,
                brandCyan,
                brandMagenta,
            ),
            floatArrayOf(0f, 0.28f, 0.5f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        drawTronTitle(canvas, titleX, titleY)
        titlePaint.shader = null

        labelPaint.textSize = 8.7f * density
        val statusLines = fittedStatusLines(displayedState, right - left - 52f * density)
        val statusLineHeight = 12f * density
        val firstBaseline = top + 80f * density
        statusLines.forEachIndexed { index, line ->
            drawOutlinedText(
                canvas,
                line,
                width / 2f,
                firstBaseline + index * statusLineHeight,
                labelPaint,
                1.8f * density,
            )
        }

        val dividerY = firstBaseline + statusLines.size * statusLineHeight + 5f * density
        fineLine.shader = null
        fineLine.color = brandCyan.withAlpha(150)
        fineLine.strokeWidth = 0.8f * density
        canvas.drawLine(left + 20f * density, dividerY, right - 20f * density, dividerY, fineLine)

        configureStatPaints()
        canvas.save()
        canvas.clipPath(cardPath)
        drawCardSections(
            canvas = canvas,
            left = left + 18f * density,
            top = dividerY + 7f * density,
            right = right - 18f * density,
        )
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || width == 0 || height == 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animate().cancel()
                touchDownX = event.x
                touchDownY = event.y
                tiltGestureClaimed = false
                // Scaling a viewport-fitted card makes its transformed layer
                // cross the reserved Search/footer boundaries and clips the
                // foil edge. Tilt and glare provide the tactile response
                // without changing the card's measured footprint.
                scaleX = 1f
                scaleY = 1f
                updateTilt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tiltGestureClaimed) {
                    val deltaX = kotlin.math.abs(event.x - touchDownX)
                    val deltaY = kotlin.math.abs(event.y - touchDownY)
                    if (deltaX > touchSlop && deltaX > deltaY) {
                        tiltGestureClaimed = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                updateTilt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                tiltGestureClaimed = false
                animate()
                    .rotationX(0f)
                    .rotationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .start()
                highlightX = 0.5f
                highlightY = 0.5f
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateTilt(x: Float, y: Float) {
        val normalizedX = (x / width).coerceIn(0f, 1f)
        val normalizedY = (y / height).coerceIn(0f, 1f)
        rotationY = (normalizedX - 0.5f) * 30f
        rotationX = (0.5f - normalizedY) * 24f
        highlightX = normalizedX
        highlightY = normalizedY
        invalidate()
    }

    private fun drawHolographicLaminate(
        canvas: Canvas,
        cardPath: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val cardWidth = right - left
        val cardHeight = bottom - top
        val lightX = left + cardWidth * highlightX
        val lightY = top + cardHeight * highlightY
        canvas.save()
        canvas.clipPath(cardPath)

        // This broad spectral band crosses the card opposite the physical
        // tilt, producing the color travel expected from diffraction foil.
        val travel = (highlightX - highlightY) * cardWidth * 0.42f
        fill.shader = LinearGradient(
            left - cardWidth * 0.45f + travel,
            bottom,
            right + cardWidth * 0.45f + travel,
            top,
            intArrayOf(
                brandCyan.withAlpha(12),
                brandCyan.withAlpha(145),
                brandIce.withAlpha(185),
                brandYellow.withAlpha(125),
                brandMagenta.withAlpha(155),
                brandViolet.withAlpha(130),
                brandCyan.withAlpha(12),
            ),
            floatArrayOf(0f, 0.18f, 0.36f, 0.5f, 0.66f, 0.82f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, right, bottom, fill)

        // Faceted shards behave like different planes in a holographic
        // laminate. Their displacement is deliberately smaller than the
        // card rotation so the surface feels layered instead of painted on.
        val facetShiftX = (highlightX - 0.5f) * cardWidth * 0.09f
        val facetShiftY = (highlightY - 0.5f) * cardHeight * 0.09f
        drawFoilFacet(
            canvas,
            floatArrayOf(
                left, top + cardHeight * 0.2f,
                left + cardWidth * 0.42f + facetShiftX, top,
                left + cardWidth * 0.28f - facetShiftX, bottom,
            ),
            brandCyan.withAlpha(46),
        )
        drawFoilFacet(
            canvas,
            floatArrayOf(
                left + cardWidth * 0.24f, top,
                right, top + cardHeight * 0.12f,
                left + cardWidth * 0.64f + facetShiftX, bottom + facetShiftY,
            ),
            brandViolet.withAlpha(54),
        )
        drawFoilFacet(
            canvas,
            floatArrayOf(
                right, top + cardHeight * 0.3f,
                right, bottom,
                left + cardWidth * 0.34f - facetShiftX, bottom,
                left + cardWidth * 0.72f, top - facetShiftY,
            ),
            brandMagenta.withAlpha(43),
        )
        drawFoilFacet(
            canvas,
            floatArrayOf(
                left, top + cardHeight * 0.68f,
                left + cardWidth * 0.48f, top + cardHeight * 0.3f + facetShiftY,
                right, bottom,
                left + cardWidth * 0.18f, bottom,
            ),
            brandYellow.withAlpha(30),
        )

        // Fine interference grooves and their counter-angle are only obvious
        // while the card is moving, which avoids a static striped texture.
        fineLine.shader = null
        fineLine.strokeWidth = 0.55f * density
        val grooveSpacing = 11f * density
        val grooveOffset = ((highlightX + highlightY) * grooveSpacing * 4f) % grooveSpacing
        var grooveX = left - cardHeight + grooveOffset
        var grooveIndex = 0
        while (grooveX < right + cardHeight) {
            fineLine.color = if (grooveIndex % 3 == 0) {
                brandMagenta.withAlpha(36)
            } else {
                brandCyan.withAlpha(27)
            }
            canvas.drawLine(grooveX, bottom, grooveX + cardHeight, top, fineLine)
            grooveX += grooveSpacing
            grooveIndex += 1
        }
        fineLine.color = brandIce.withAlpha(20)
        var crossX = left - cardHeight - grooveOffset
        while (crossX < right + cardHeight) {
            canvas.drawLine(crossX, top, crossX + cardHeight, bottom, fineLine)
            crossX += grooveSpacing * 2.4f
        }

        // The local specular hotspot is tied directly to the finger position.
        // A tight core plus long anamorphic rays makes it read as foil glare.
        fill.shader = RadialGradient(
            lightX,
            lightY,
            cardWidth * 0.32f,
            intArrayOf(
                brandIce.withAlpha(185),
                brandCyan.withAlpha(75),
                brandViolet.withAlpha(28),
                brandViolet.withAlpha(0),
            ),
            floatArrayOf(0f, 0.18f, 0.53f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(lightX, lightY, cardWidth * 0.32f, fill)
        drawHolographicFlare(canvas, lightX, lightY, cardWidth, cardHeight)

        // A narrow white reflection travels across the laminate and makes the
        // resting card retain a premium foil sheen without washing out text.
        fill.shader = LinearGradient(
            lightX - cardWidth * 0.23f,
            top,
            lightX + cardWidth * 0.23f,
            bottom,
            intArrayOf(
                brandIce.withAlpha(0),
                brandIce.withAlpha(28),
                brandIce.withAlpha(115),
                brandIce.withAlpha(25),
                brandIce.withAlpha(0),
            ),
            floatArrayOf(0f, 0.34f, 0.5f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, top, right, bottom, fill)
        fill.shader = null
        fineLine.shader = null
        canvas.restore()
    }

    private fun drawFoilFacet(canvas: Canvas, points: FloatArray, color: Int) {
        if (points.size < 6 || points.size % 2 != 0) return
        val facet = Path().apply {
            moveTo(points[0], points[1])
            var index = 2
            while (index < points.size) {
                lineTo(points[index], points[index + 1])
                index += 2
            }
            close()
        }
        fill.shader = null
        fill.color = color
        canvas.drawPath(facet, fill)
    }

    private fun drawHolographicFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        cardWidth: Float,
        cardHeight: Float,
    ) {
        fineLine.shader = null
        fineLine.strokeCap = Paint.Cap.ROUND
        fineLine.color = brandIce.withAlpha(190)
        fineLine.strokeWidth = 0.8f * density
        canvas.drawLine(x - cardWidth * 0.16f, y, x + cardWidth * 0.16f, y, fineLine)
        canvas.drawLine(x, y - cardHeight * 0.21f, x, y + cardHeight * 0.21f, fineLine)
        fineLine.color = brandCyan.withAlpha(125)
        canvas.drawLine(
            x - cardWidth * 0.07f,
            y - cardHeight * 0.11f,
            x + cardWidth * 0.07f,
            y + cardHeight * 0.11f,
            fineLine,
        )
        fineLine.color = brandMagenta.withAlpha(105)
        canvas.drawLine(
            x - cardWidth * 0.07f,
            y + cardHeight * 0.11f,
            x + cardWidth * 0.07f,
            y - cardHeight * 0.11f,
            fineLine,
        )
        fill.shader = null
        fill.color = brandIce
        canvas.drawCircle(x, y, 1.7f * density, fill)
    }

    private fun drawTronTitle(canvas: Canvas, x: Float, baseline: Float) {
        val glyphs = Path().also { path ->
            titlePaint.getTextPath(displayedName, 0, displayedName.length, x, baseline, path)
        }

        // Wide translucent strokes form an energy aura without a soft drop
        // shadow, keeping the lettering crisp and electronic.
        titleEffectPaint.shader = null
        titleEffectPaint.style = Paint.Style.STROKE
        titleEffectPaint.color = brandCyan.withAlpha(36)
        titleEffectPaint.strokeWidth = 11f * density
        canvas.drawPath(glyphs, titleEffectPaint)
        titleEffectPaint.color = brandViolet.withAlpha(70)
        titleEffectPaint.strokeWidth = 7f * density
        canvas.drawPath(glyphs, titleEffectPaint)

        // A navy separator prevents the neon edge from dissolving into the
        // metallic card, then cyan and white cores create the energized tube.
        titleEffectPaint.color = brandNavy.withAlpha(245)
        titleEffectPaint.strokeWidth = 5.1f * density
        canvas.drawPath(glyphs, titleEffectPaint)
        titleEffectPaint.color = brandCyan
        titleEffectPaint.strokeWidth = 3f * density
        canvas.drawPath(glyphs, titleEffectPaint)
        titleEffectPaint.color = brandIce
        titleEffectPaint.strokeWidth = 0.9f * density
        canvas.drawPath(glyphs, titleEffectPaint)

        titlePaint.style = Paint.Style.FILL
        canvas.drawPath(glyphs, titlePaint)

        // Thin clipped traces interrupt the fill like illuminated circuitry
        // while remaining inside the actual letter silhouettes.
        canvas.save()
        canvas.clipPath(glyphs)
        titleEffectPaint.style = Paint.Style.STROKE
        titleEffectPaint.color = brandIce.withAlpha(210)
        titleEffectPaint.strokeWidth = 0.7f * density
        val topTrace = baseline + titlePaint.ascent() * 0.58f
        val lowerTrace = baseline + titlePaint.ascent() * 0.2f
        canvas.drawLine(x - width * 0.42f, topTrace, x + width * 0.42f, topTrace, titleEffectPaint)
        titleEffectPaint.color = brandMagenta.withAlpha(175)
        canvas.drawLine(
            x - width * 0.42f,
            lowerTrace,
            x + width * 0.42f,
            lowerTrace,
            titleEffectPaint,
        )
        canvas.restore()
    }

    private fun drawOutlinedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        paint: Paint,
        outlineWidth: Float,
    ) {
        textOutlinePaint.set(paint)
        textOutlinePaint.shader = null
        textOutlinePaint.color = Color.BLACK
        textOutlinePaint.style = Paint.Style.STROKE
        textOutlinePaint.strokeWidth = outlineWidth
        textOutlinePaint.strokeJoin = Paint.Join.ROUND
        canvas.drawText(text, x, y, textOutlinePaint)
        canvas.drawText(text, x, y, paint)
    }

    private fun fittedStatusLines(text: String, maxWidth: Float): List<String> {
        if (labelPaint.measureText(text) <= maxWidth) return listOf(text)
        val parts = text.split(" · ")
        if (parts.size == 1) return listOf(ellipsizeToWidth(text, maxWidth))

        val lines = mutableListOf<String>()
        var partIndex = 0
        while (partIndex < parts.size && lines.size < 2) {
            var line = parts[partIndex]
            partIndex += 1
            while (partIndex < parts.size) {
                val candidate = "$line · ${parts[partIndex]}"
                if (labelPaint.measureText(candidate) > maxWidth) break
                line = candidate
                partIndex += 1
            }
            if (lines.size == 1 && partIndex < parts.size) {
                line += " · " + parts.subList(partIndex, parts.size).joinToString(" · ")
                partIndex = parts.size
            }
            lines += ellipsizeToWidth(line, maxWidth)
        }
        return lines.ifEmpty { listOf(ellipsizeToWidth(text, maxWidth)) }
    }

    private fun configureStatPaints() {
        sectionPaint.textSize = 10.5f * density
        statLabelPaint.textSize = 9.5f * density
        statValuePaint.textSize = 12.5f * density
    }

    private fun cardSectionsHeight(maxWidth: Float): Float {
        if (displayedSections.isEmpty()) return 0f
        var height = 0f
        displayedSections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) height += 7f * density
            height += 24f * density
            section.rows.forEach { row ->
                height += cardStatRowHeight(row, maxWidth) + 5f * density
            }
        }
        return height + 7f * density
    }

    private fun cardStatRowHeight(row: WalletNameCardRow, availableWidth: Float): Float {
        val gap = 7f * density
        val cellWidth = (availableWidth - gap * (row.stats.size - 1)) / row.stats.size
        val valueWidth = cellWidth - 20f * density
        val valueLines = row.stats.maxOf { stat ->
            statValuePaint.typeface = if (stat.monospace) Typeface.MONOSPACE else {
                Typeface.create("sans-serif", Typeface.BOLD)
            }
            wrapToWidth(stat.value, valueWidth, statValuePaint).size
        }
        return 36f * density + (valueLines - 1) * 16f * density
    }

    private fun drawCardSections(canvas: Canvas, left: Float, top: Float, right: Float) {
        val availableWidth = right - left
        val gap = 7f * density
        var cursorY = top
        displayedSections.forEachIndexed { sectionIndex, section ->
            if (sectionIndex > 0) cursorY += 7f * density
            drawOutlinedText(
                canvas,
                section.title,
                left + 3f * density,
                cursorY + 14f * density,
                sectionPaint,
                1.7f * density,
            )
            fineLine.color = brandYellow.withAlpha(150)
            fineLine.strokeWidth = 0.8f * density
            canvas.drawLine(
                left,
                cursorY + 20f * density,
                right,
                cursorY + 20f * density,
                fineLine,
            )
            cursorY += 24f * density

            section.rows.forEach { row ->
                val rowHeight = cardStatRowHeight(row, availableWidth)
                val cellWidth = (availableWidth - gap * (row.stats.size - 1)) / row.stats.size
                row.stats.forEachIndexed { statIndex, stat ->
                    val cellLeft = left + statIndex * (cellWidth + gap)
                    val cellRight = cellLeft + cellWidth
                    canvas.drawRoundRect(
                        cellLeft,
                        cursorY,
                        cellRight,
                        cursorY + rowHeight,
                        9f * density,
                        9f * density,
                        statPanelPaint,
                    )
                    drawOutlinedText(
                        canvas,
                        stat.label,
                        cellLeft + 10f * density,
                        cursorY + 12f * density,
                        statLabelPaint,
                        1.45f * density,
                    )
                    statValuePaint.typeface = if (stat.monospace) Typeface.MONOSPACE else {
                        Typeface.create("sans-serif", Typeface.BOLD)
                    }
                    val valueLines = wrapToWidth(
                        stat.value,
                        cellWidth - 20f * density,
                        statValuePaint,
                    )
                    valueLines.forEachIndexed { lineIndex, line ->
                        drawOutlinedText(
                            canvas,
                            line,
                            cellLeft + 10f * density,
                            cursorY + 31f * density + lineIndex * 16f * density,
                            statValuePaint,
                            1.55f * density,
                        )
                    }
                }
                cursorY += rowHeight + 5f * density
            }
        }
    }

    private fun wrapToWidth(text: String, maxWidth: Float, paint: Paint): List<String> {
        if (text.isEmpty() || paint.measureText(text) <= maxWidth) return listOf(text)
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            var count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            if (count < remaining.length) {
                val breakAt = remaining.lastIndexOf(' ', count - 1)
                if (breakAt > 0) count = breakAt
            }
            lines += remaining.substring(0, count).trim()
            remaining = remaining.substring(count).trimStart()
        }
        return lines
    }

    private fun ellipsizeToWidth(text: String, maxWidth: Float): String {
        if (labelPaint.measureText(text) <= maxWidth) return text
        val suffix = "…"
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (labelPaint.measureText(text.substring(0, middle) + suffix) <= maxWidth) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return text.substring(0, low).trimEnd() + suffix
    }

    private fun Int.withAlpha(alpha: Int): Int =
        Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))

}
