package com.kmnexus.codexmeter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.unit.DpSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders a widget-safe frosted liquid-glass backdrop into a bitmap.
 *
 * Launcher widgets are Glance/RemoteViews surfaces. They cannot host Haze, RuntimeShader, or an
 * arbitrary in-process LiquidGlassView, so the widget uses a pre-rendered bitmap backdrop and keeps
 * quota text as native widget text for crispness and accessibility.
 */
internal object LiquidGlassWidgetBackdropRenderer {
    fun render(
        context: Context,
        size: DpSize,
        tone: WidgetQuotaTone,
    ): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = max(MIN_WIDTH_PX, (size.width.value * density).roundToInt())
        val height = max(MIN_HEIGHT_PX, (size.height.value * density).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = density.coerceAtLeast(1f)
        val inset = 4f * scale
        val radius = 26f * scale
        val bounds = RectF(inset, inset, width - inset, height - inset)
        val style = styleSpec(tone)

        drawAttachedShadow(canvas, bounds, radius, style, scale)
        drawThinFrostedBody(canvas, bounds, radius, tone, style, scale)
        drawSubtleRefraction(canvas, bounds, radius, tone, style, scale)
        drawNarrowTopGlint(canvas, bounds, radius, style, scale)
        drawHairlineRims(canvas, bounds, radius, style, scale)

        return bitmap
    }

    internal fun styleSpec(tone: WidgetQuotaTone): LiquidGlassWidgetBackdropStyle {
        val (shadowRed, shadowGreen, shadowBlue) = when (tone) {
            WidgetQuotaTone.Success -> Triple(78, 124, 174)
            WidgetQuotaTone.Warning -> Triple(102, 116, 156)
            WidgetQuotaTone.Danger -> Triple(108, 110, 154)
            WidgetQuotaTone.Neutral -> Triple(76, 122, 182)
        }
        return LiquidGlassWidgetBackdropStyle(
            bottomShadowRed = shadowRed,
            bottomShadowGreen = shadowGreen,
            bottomShadowBlue = shadowBlue,
            bodyTopAlpha = 112,
            bodyMiddleAlpha = 96,
            bodyToneAlpha = 88,
            bodyBottomAlpha = 94,
            frostVeilPeakAlpha = 8,
            innerAirPeakAlpha = 6,
            lensBluePeakAlpha = 5,
            lensCyanPeakAlpha = 3,
            bottomShadowPeakAlpha = 5,
            bottomShadowContactAlpha = 2,
            topEdgeRimAlpha = 18,
            topHighlightPeakAlpha = 18,
            sideRimPeakAlpha = 10,
            bottomRimBlueAlpha = 2,
            outerBorderStrokeScale = 0.22f,
            innerBorderStrokeScale = 0f,
            topHighlightTopInsetScale = 1.0f,
            topHighlightHeightFraction = 0.055f,
            topHighlightSideInsetScale = 3f,
            topHighlightMaxHeightScale = 2.2f,
            bottomShadowCenterOffsetScale = -0.05f,
            bottomShadowTopOffsetScale = 0.05f,
            bottomShadowBottomOffsetScale = 0.75f,
            bottomShadowContactTopOffsetScale = 0.05f,
            bottomShadowContactBottomOffsetScale = 0.45f,
        )
    }

    private fun drawAttachedShadow(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        style: LiquidGlassWidgetBackdropStyle,
        scale: Float,
    ) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bounds.centerX(),
                bounds.bottom + style.bottomShadowCenterOffsetScale * scale,
                bounds.width() * 0.42f,
                intArrayOf(
                    style.shadowColor(style.bottomShadowPeakAlpha),
                    style.shadowColor(style.bottomShadowContactAlpha),
                    style.shadowColor(0),
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawOval(
            RectF(
                bounds.left + 26f * scale,
                bounds.bottom + style.bottomShadowTopOffsetScale * scale,
                bounds.right - 26f * scale,
                bounds.bottom + style.bottomShadowBottomOffsetScale * scale,
            ),
            shadow,
        )

        val contact = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.shadowColor(style.bottomShadowContactAlpha)
        }
        canvas.drawRoundRect(
            RectF(
                bounds.left + 32f * scale,
                bounds.bottom + style.bottomShadowContactTopOffsetScale * scale,
                bounds.right - 32f * scale,
                bounds.bottom + style.bottomShadowContactBottomOffsetScale * scale,
            ),
            radius * 0.58f,
            radius * 0.58f,
            contact,
        )
    }

    private fun drawThinFrostedBody(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        tone: WidgetQuotaTone,
        style: LiquidGlassWidgetBackdropStyle,
        scale: Float,
    ) {
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                intArrayOf(
                    Color.argb(style.bodyTopAlpha, 62, 84, 112),
                    Color.argb(style.bodyMiddleAlpha, 38, 56, 82),
                    tone.darkSurfaceColor(alpha = style.bodyToneAlpha),
                    Color.argb(style.bodyBottomAlpha, 46, 64, 92),
                ),
                floatArrayOf(0f, 0.34f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds, radius, radius, body)

        val frostVeil = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                intArrayOf(
                    Color.argb(style.frostVeilPeakAlpha, 255, 255, 255),
                    Color.argb(4, 247, 252, 255),
                    Color.argb(6, 233, 244, 255),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds.insetCopy(1.1f * scale), radius * 0.96f, radius * 0.96f, frostVeil)

        val innerAir = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bounds.left + bounds.width() * 0.42f,
                bounds.top + bounds.height() * 0.14f,
                bounds.width() * 0.72f,
                intArrayOf(
                    Color.argb(style.innerAirPeakAlpha, 255, 255, 255),
                    Color.argb(4, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(bounds.insetCopy(1.4f * scale), radius * 0.95f, radius * 0.95f, innerAir)
    }

    private fun drawSubtleRefraction(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        tone: WidgetQuotaTone,
        style: LiquidGlassWidgetBackdropStyle,
        scale: Float,
    ) {
        val saved = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })

        val leftCool = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bounds.left + bounds.width() * 0.10f,
                bounds.top + bounds.height() * 0.48f,
                bounds.height() * 0.86f,
                intArrayOf(
                    Color.argb(style.lensBluePeakAlpha, 93, 184, 255),
                    Color.argb(2, 93, 184, 255),
                    Color.argb(0, 93, 184, 255),
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawOval(
            RectF(
                bounds.left - 12f * scale,
                bounds.top + 10f * scale,
                bounds.left + bounds.width() * 0.42f,
                bounds.bottom - 4f * scale,
            ),
            leftCool,
        )

        val topRightAir = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                bounds.right - bounds.width() * 0.22f,
                bounds.top + bounds.height() * 0.22f,
                bounds.height() * 0.58f,
                intArrayOf(
                    Color.argb(style.lensCyanPeakAlpha, 126, 244, 232),
                    tone.statusLensColor(alpha = 2),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawOval(
            RectF(
                bounds.left + bounds.width() * 0.58f,
                bounds.top + 2f * scale,
                bounds.right + 6f * scale,
                bounds.top + bounds.height() * 0.54f,
            ),
            topRightAir,
        )

        canvas.restoreToCount(saved)
    }

    private fun drawNarrowTopGlint(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        style: LiquidGlassWidgetBackdropStyle,
        scale: Float,
    ) {
        val saved = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) })

        val topEdgeRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.top,
                intArrayOf(
                    Color.argb(style.topEdgeRimAlpha, 255, 255, 255),
                    Color.argb(style.topEdgeRimAlpha + 4, 255, 255, 255),
                    Color.argb(style.topEdgeRimAlpha, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP,
            )
            this.style = Paint.Style.STROKE
            strokeWidth = 0.56f * scale
        }
        canvas.save()
        canvas.clipRect(bounds.left, bounds.top, bounds.right, bounds.top + radius * 0.82f)
        canvas.drawRoundRect(bounds.insetCopy(0.45f * scale), radius * 0.99f, radius * 0.99f, topEdgeRim)
        canvas.restore()

        val top = bounds.top + style.topHighlightTopInsetScale * scale
        val height = minOf(
            bounds.height() * style.topHighlightHeightFraction,
            style.topHighlightMaxHeightScale * scale,
        )
        val bottom = top + height
        val sideInset = style.topHighlightSideInsetScale * scale
        val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                top,
                bounds.left,
                bottom,
                intArrayOf(
                    Color.argb(style.topHighlightPeakAlpha, 255, 255, 255),
                    Color.argb(10, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.40f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(
            RectF(bounds.left + sideInset, top, bounds.right - sideInset, bottom),
            height * 0.48f,
            height * 0.48f,
            highlight,
        )
        canvas.restoreToCount(saved)
    }

    private fun drawHairlineRims(
        canvas: Canvas,
        bounds: RectF,
        radius: Float,
        style: LiquidGlassWidgetBackdropStyle,
        scale: Float,
    ) {
        val saved = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(bounds.insetCopy(1.1f * scale), radius * 0.95f, radius * 0.95f, Path.Direction.CW) })

        val leftRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.left + bounds.width() * 0.12f,
                bounds.bottom,
                intArrayOf(
                    Color.argb(style.sideRimPeakAlpha, 255, 255, 255),
                    Color.argb(3, 255, 255, 255),
                    Color.argb(0, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(
            RectF(bounds.left + 1.0f * scale, bounds.top + 10f * scale, bounds.left + 5.2f * scale, bounds.bottom - 10f * scale),
            radius * 0.36f,
            radius * 0.36f,
            leftRim,
        )

        val bottomRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.bottom - bounds.height() * 0.10f,
                bounds.left,
                bounds.bottom,
                intArrayOf(
                    Color.argb(0, 255, 255, 255),
                    Color.argb(3, 255, 255, 255),
                    Color.argb(style.bottomRimBlueAlpha, 183, 216, 255),
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(
            RectF(bounds.left + 8f * scale, bounds.bottom - 5f * scale, bounds.right - 8f * scale, bounds.bottom - 1.2f * scale),
            radius * 0.38f,
            radius * 0.38f,
            bottomRim,
        )
        canvas.restoreToCount(saved)

        val outerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                intArrayOf(
                    Color.argb(18, 255, 255, 255),
                    Color.argb(4, 208, 228, 252),
                    Color.argb(8, 255, 255, 255),
                ),
                floatArrayOf(0f, 0.58f, 1f),
                Shader.TileMode.CLAMP,
            )
            strokeWidth = style.outerBorderStrokeScale * scale
            this.style = Paint.Style.STROKE
        }
        canvas.drawRoundRect(bounds.insetCopy(0.75f * scale), radius, radius, outerBorder)
    }

    private fun WidgetQuotaTone.darkSurfaceColor(alpha: Int): Int {
        val (red, green, blue) = when (this) {
            WidgetQuotaTone.Success -> Triple(38, 70, 72)
            WidgetQuotaTone.Warning -> Triple(76, 66, 48)
            WidgetQuotaTone.Danger -> Triple(78, 52, 62)
            WidgetQuotaTone.Neutral -> Triple(42, 62, 90)
        }
        return Color.argb(alpha, red, green, blue)
    }

    private fun WidgetQuotaTone.statusLensColor(alpha: Int): Int {
        val (red, green, blue) = when (this) {
            WidgetQuotaTone.Success -> Triple(136, 230, 180)
            WidgetQuotaTone.Warning -> Triple(255, 196, 106)
            WidgetQuotaTone.Danger -> Triple(255, 136, 148)
            WidgetQuotaTone.Neutral -> Triple(126, 183, 255)
        }
        return Color.argb(alpha, red, green, blue)
    }

    private fun LiquidGlassWidgetBackdropStyle.shadowColor(alpha: Int): Int =
        Color.argb(alpha, bottomShadowRed, bottomShadowGreen, bottomShadowBlue)

    private fun RectF.insetCopy(inset: Float): RectF =
        RectF(left + inset, top + inset, right - inset, bottom - inset)

    private const val MIN_WIDTH_PX = 260
    private const val MIN_HEIGHT_PX = 120
}

internal data class LiquidGlassWidgetBackdropStyle(
    val bottomShadowRed: Int,
    val bottomShadowGreen: Int,
    val bottomShadowBlue: Int,
    val bodyTopAlpha: Int,
    val bodyMiddleAlpha: Int,
    val bodyToneAlpha: Int,
    val bodyBottomAlpha: Int,
    val frostVeilPeakAlpha: Int,
    val innerAirPeakAlpha: Int,
    val lensBluePeakAlpha: Int,
    val lensCyanPeakAlpha: Int,
    val bottomShadowPeakAlpha: Int,
    val bottomShadowContactAlpha: Int,
    val topEdgeRimAlpha: Int,
    val topHighlightPeakAlpha: Int,
    val sideRimPeakAlpha: Int,
    val bottomRimBlueAlpha: Int,
    val outerBorderStrokeScale: Float,
    val innerBorderStrokeScale: Float,
    val topHighlightTopInsetScale: Float,
    val topHighlightHeightFraction: Float,
    val topHighlightSideInsetScale: Float,
    val topHighlightMaxHeightScale: Float,
    val bottomShadowCenterOffsetScale: Float,
    val bottomShadowTopOffsetScale: Float,
    val bottomShadowBottomOffsetScale: Float,
    val bottomShadowContactTopOffsetScale: Float,
    val bottomShadowContactBottomOffsetScale: Float,
)
