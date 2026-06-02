package com.kmnexus.codexmeter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pre-renders the widget status rail as a bitmap so Glance/RemoteViews can show a soft glow.
 *
 * Glance cannot apply a runtime Gaussian blur modifier inside launcher widgets, so the rail uses a
 * widget-safe bitmap: a blurred halo underneath and a crisp semantic core line above it.
 */
internal object WidgetStatusGlowRenderer {
    fun render(
        context: Context,
        tone: WidgetQuotaTone,
        widthDp: Float = DEFAULT_WIDTH_DP,
        heightDp: Float = DEFAULT_HEIGHT_DP,
    ): Bitmap {
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val width = max(1, (widthDp * density).roundToInt())
        val height = max(1, (heightDp * density).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val style = styleSpec(tone)
        val centerX = width / 2f
        val top = style.verticalInsetDp * density
        val bottom = height - style.verticalInsetDp * density

        val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.outerGlowArgb
            maskFilter = BlurMaskFilter(style.outerBlurRadiusDp * density, BlurMaskFilter.Blur.NORMAL)
        }
        val outerGlowWidth = style.outerGlowWidthDp * density
        canvas.drawRoundRect(
            RectF(
                centerX - outerGlowWidth / 2f,
                top + 1.2f * density,
                centerX + outerGlowWidth / 2f,
                bottom - 1.2f * density,
            ),
            outerGlowWidth,
            outerGlowWidth,
            outerGlowPaint,
        )

        val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                centerX,
                top,
                centerX,
                bottom,
                intArrayOf(
                    withAlpha(style.accentArgb, style.innerGlowTopAlpha),
                    withAlpha(style.accentArgb, style.innerGlowCenterAlpha),
                    withAlpha(style.accentArgb, style.innerGlowBottomAlpha),
                ),
                floatArrayOf(0f, 0.44f, 1f),
                Shader.TileMode.CLAMP,
            )
            maskFilter = BlurMaskFilter(style.innerBlurRadiusDp * density, BlurMaskFilter.Blur.NORMAL)
        }
        val innerGlowWidth = style.innerGlowWidthDp * density
        canvas.drawRoundRect(
            RectF(
                centerX - innerGlowWidth / 2f,
                top,
                centerX + innerGlowWidth / 2f,
                bottom,
            ),
            innerGlowWidth,
            innerGlowWidth,
            innerGlowPaint,
        )

        val coreWidth = style.coreWidthDp * density
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                centerX,
                top,
                centerX,
                bottom,
                intArrayOf(
                    withAlpha(style.accentArgb, style.coreTopAlpha),
                    style.accentArgb,
                    withAlpha(style.accentArgb, style.coreBottomAlpha),
                ),
                floatArrayOf(0f, 0.46f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(
            RectF(
                centerX - coreWidth / 2f,
                top + 1.0f * density,
                centerX + coreWidth / 2f,
                bottom - 1.0f * density,
            ),
            coreWidth,
            coreWidth,
            corePaint,
        )

        val edgeGlintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(style.edgeGlintAlpha, 255, 255, 255)
        }
        canvas.drawRoundRect(
            RectF(
                centerX - coreWidth / 2f,
                top + 2.2f * density,
                centerX - coreWidth / 2f + 0.65f * density,
                bottom - 2.2f * density,
            ),
            0.65f * density,
            0.65f * density,
            edgeGlintPaint,
        )

        return bitmap
    }

    internal fun styleSpec(tone: WidgetQuotaTone): WidgetStatusGlowStyle =
        WidgetStatusGlowStyle(
            accentArgb = tone.statusAccentArgb(),
            outerGlowArgb = tone.statusGlowArgb(),
            outerGlowWidthDp = 7.4f,
            innerGlowWidthDp = 4.8f,
            coreWidthDp = 3.0f,
            verticalInsetDp = 2.0f,
            outerBlurRadiusDp = 2.8f,
            innerBlurRadiusDp = 1.4f,
            innerGlowTopAlpha = 52,
            innerGlowCenterAlpha = 76,
            innerGlowBottomAlpha = 48,
            coreTopAlpha = 210,
            coreBottomAlpha = 228,
            edgeGlintAlpha = 58,
        )

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private const val DEFAULT_WIDTH_DP = 9f
    private const val DEFAULT_HEIGHT_DP = 44f
}

internal data class WidgetStatusGlowStyle(
    val accentArgb: Int,
    val outerGlowArgb: Int,
    val outerGlowWidthDp: Float,
    val innerGlowWidthDp: Float,
    val coreWidthDp: Float,
    val verticalInsetDp: Float,
    val outerBlurRadiusDp: Float,
    val innerBlurRadiusDp: Float,
    val innerGlowTopAlpha: Int,
    val innerGlowCenterAlpha: Int,
    val innerGlowBottomAlpha: Int,
    val coreTopAlpha: Int,
    val coreBottomAlpha: Int,
    val edgeGlintAlpha: Int,
)
