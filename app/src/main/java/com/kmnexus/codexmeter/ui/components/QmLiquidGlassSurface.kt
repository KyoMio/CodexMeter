package com.kmnexus.codexmeter.ui.components

import android.content.Context
import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kmnexus.codexmeter.ui.theme.CodexMeterTheme
import com.kmnexus.codexmeter.ui.theme.CodexMeterShapes
import com.qmdeve.liquidglass.widget.LiquidGlassView

enum class LiquidGlassSurfaceRole {
    Hero,
    Card,
    Navigation,
}

@Composable
fun QmLiquidGlassSurface(
    modifier: Modifier = Modifier,
    role: LiquidGlassSurfaceRole,
    cornerRadius: Dp,
    contentPadding: PaddingValues,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = when (role) {
        LiquidGlassSurfaceRole.Hero -> CodexMeterShapes.xl
        LiquidGlassSurfaceRole.Card -> CodexMeterShapes.xl
        LiquidGlassSurfaceRole.Navigation -> CodexMeterShapes.pill
    }
    val radiusPx = with(LocalDensity.current) { cornerRadius.toPx() }
    Box(modifier = modifier.clip(shape)) {
        LiquidGlassFallback(
            role = role,
            radiusPx = radiusPx,
            modifier = Modifier.matchParentSize(),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AndroidView(
                factory = { context -> QmLiquidGlassHostView(context) },
                update = { view ->
                    view.configure(
                        role = role,
                        cornerRadiusPx = radiusPx,
                    )
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .border(
                    width = 1.dp,
                    color = CodexMeterTheme.colors.glassStrokeLight.copy(alpha = 0.82f),
                    shape = shape,
                )
                .padding(1.dp)
                .border(
                    width = 1.dp,
                    color = CodexMeterTheme.colors.glassStrokeCool.copy(alpha = 0.52f),
                    shape = shape,
                ),
        )
        Box(
            modifier = Modifier
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun CodexMeterBackdrop(modifier: Modifier = Modifier) {
    val surfaceSoft = CodexMeterTheme.colors.surfaceSoft
    val neutral = CodexMeterTheme.colors.neutral
    val neutralAlt = CodexMeterTheme.colors.neutralAlt
    val tintBlue = CodexMeterTheme.colors.glassTintBlue
    val tintCyan = CodexMeterTheme.colors.glassTintCyan
    Canvas(modifier = modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    surfaceSoft,
                    neutral,
                    neutralAlt,
                ),
            ),
        )
        drawCircle(
            color = tintBlue.copy(alpha = 0.18f),
            radius = size.minDimension * 0.46f,
            center = Offset(size.width * 0.92f, size.height * 0.03f),
        )
        drawCircle(
            color = tintCyan.copy(alpha = 0.10f),
            radius = size.minDimension * 0.35f,
            center = Offset(size.width * 0.08f, size.height * 0.22f),
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.62f),
            radius = size.minDimension * 0.28f,
            center = Offset(size.width * 0.12f, size.height * 0.02f),
        )
    }
}

@Composable
private fun LiquidGlassFallback(
    role: LiquidGlassSurfaceRole,
    radiusPx: Float,
    modifier: Modifier = Modifier,
) {
    val glassBase = CodexMeterTheme.colors.glassBase
    val surfaceSoft = CodexMeterTheme.colors.surfaceSoft
    val neutralAlt = CodexMeterTheme.colors.neutralAlt
    val tintBlue = CodexMeterTheme.colors.glassTintBlue
    val tintCyan = CodexMeterTheme.colors.glassTintCyan
    val tintViolet = CodexMeterTheme.colors.glassTintViolet
    val strokeLight = CodexMeterTheme.colors.glassStrokeLight
    val strokeCool = CodexMeterTheme.colors.glassStrokeCool
    Canvas(modifier = modifier) {
        val baseColors = when (role) {
            LiquidGlassSurfaceRole.Hero -> listOf(
                Color(0xFFE1EFFB).copy(alpha = 0.58f),
                glassBase.copy(alpha = 0.50f),
                Color(0xFFF8FCFF).copy(alpha = 0.46f),
            )
            LiquidGlassSurfaceRole.Card -> listOf(
                Color(0xFFF7FBFF).copy(alpha = 0.70f),
                glassBase.copy(alpha = 0.48f),
                Color(0xFFFFFFFF).copy(alpha = 0.56f),
            )
            LiquidGlassSurfaceRole.Navigation -> listOf(
                surfaceSoft.copy(alpha = 0.90f),
                glassBase.copy(alpha = 0.82f),
                neutralAlt.copy(alpha = 0.88f),
            )
        }
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = baseColors,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
        )
        if (role == LiquidGlassSurfaceRole.Navigation) {
            drawCircle(
                color = tintBlue.copy(alpha = 0.10f),
                radius = size.minDimension * 0.74f,
                center = Offset(size.width * 0.13f, size.height * 0.12f),
            )
            drawCircle(
                color = tintCyan.copy(alpha = 0.08f),
                radius = size.minDimension * 0.44f,
                center = Offset(size.width * 0.78f, size.height * 0.30f),
            )
            drawCircle(
                color = tintViolet.copy(alpha = 0.06f),
                radius = size.minDimension * 0.40f,
                center = Offset(size.width * 0.72f, size.height * 0.92f),
            )
        }
        drawRoundRect(
            color = Color.White.copy(alpha = if (role == LiquidGlassSurfaceRole.Hero) 0.16f else 0.18f),
            topLeft = Offset(1.2f, 1.2f),
            size = androidx.compose.ui.geometry.Size(size.width - 2.4f, size.height * 0.44f),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
        )
        if (role == LiquidGlassSurfaceRole.Navigation) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.20f),
                topLeft = Offset(1.8.dp.toPx(), 1.8.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    width = size.width - 3.6.dp.toPx(),
                    height = size.height - 3.6.dp.toPx(),
                ),
                cornerRadius = CornerRadius(radiusPx * 0.92f, radiusPx * 0.92f),
            )
        }
        drawRoundRect(
            color = strokeLight.copy(alpha = 0.62f),
            style = Stroke(width = 1.dp.toPx()),
            cornerRadius = CornerRadius(radiusPx, radiusPx),
        )
        if (role == LiquidGlassSurfaceRole.Hero) {
            drawRoundRect(
                color = strokeCool.copy(alpha = 0.48f),
                topLeft = Offset(1.6.dp.toPx(), 1.6.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(
                    width = size.width - 3.2.dp.toPx(),
                    height = size.height - 3.2.dp.toPx(),
                ),
                style = Stroke(width = 0.8.dp.toPx()),
                cornerRadius = CornerRadius(radiusPx * 0.94f, radiusPx * 0.94f),
            )
        }
    }
}

private class QmLiquidGlassHostView(context: Context) : FrameLayout(context) {
    private val sourceContainer = FrameLayout(context)
    private val sourceView = LiquidGlassSourceView(context)
    private val glassView = LiquidGlassView(context)
    private var bound = false

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        sourceContainer.isClickable = false
        sourceContainer.isFocusable = false
        sourceContainer.isEnabled = false
        sourceContainer.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        sourceView.isClickable = false
        sourceView.isFocusable = false
        sourceView.isEnabled = false
        sourceView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        glassView.isClickable = false
        glassView.isFocusable = false
        glassView.isEnabled = false
        glassView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        sourceContainer.addView(
            sourceView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            sourceContainer,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            glassView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun configure(
        role: LiquidGlassSurfaceRole,
        cornerRadiusPx: Float,
    ) {
        val density = resources.displayMetrics.density.coerceAtLeast(1f)
        alpha = when (role) {
            LiquidGlassSurfaceRole.Hero -> 0.54f
            LiquidGlassSurfaceRole.Card -> 0.46f
            LiquidGlassSurfaceRole.Navigation -> 0.52f
        }
        sourceView.role = role
        glassView.apply {
            setCornerRadius(cornerRadiusPx)
            setRefractionHeight(
                when (role) {
                    LiquidGlassSurfaceRole.Hero -> 28f * density
                    LiquidGlassSurfaceRole.Card -> 18f * density
                    LiquidGlassSurfaceRole.Navigation -> 20f * density
                },
            )
            setRefractionOffset(
                when (role) {
                    LiquidGlassSurfaceRole.Hero -> 70f * density
                    LiquidGlassSurfaceRole.Card -> 42f * density
                    LiquidGlassSurfaceRole.Navigation -> 50f * density
                },
            )
            setDispersion(
                when (role) {
                    LiquidGlassSurfaceRole.Hero -> 0.22f
                    LiquidGlassSurfaceRole.Card -> 0.14f
                    LiquidGlassSurfaceRole.Navigation -> 0.30f
                },
            )
            setBlurRadius(
                when (role) {
                    LiquidGlassSurfaceRole.Hero -> 7.2f * density
                    LiquidGlassSurfaceRole.Card -> 6.4f * density
                    LiquidGlassSurfaceRole.Navigation -> 5.8f * density
                },
            )
            setTintColorRed(0.94f)
            setTintColorGreen(0.98f)
            setTintColorBlue(1f)
            setTintAlpha(
                when (role) {
                    LiquidGlassSurfaceRole.Hero -> 0.15f
                    LiquidGlassSurfaceRole.Card -> 0.13f
                    LiquidGlassSurfaceRole.Navigation -> 0.18f
                },
            )
            setDraggableEnabled(false)
            setElasticEnabled(false)
            setTouchEffectEnabled(false)
        }
        bindWhenLaidOut()
    }

    private fun bindWhenLaidOut() {
        if (width > 0 && height > 0) {
            glassView.bind(sourceContainer)
            bound = true
        } else if (!bound) {
            post { bindWhenLaidOut() }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        bound = false
        bindWhenLaidOut()
    }
}

private class LiquidGlassSourceView(context: Context) : View(context) {
    var role: LiquidGlassSurfaceRole = LiquidGlassSurfaceRole.Hero
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: AndroidCanvas) {
        super.onDraw(canvas)
        val width = width.toFloat().coerceAtLeast(1f)
        val height = height.toFloat().coerceAtLeast(1f)
        paint.shader = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(
                android.graphics.Color.rgb(232, 243, 255),
                android.graphics.Color.rgb(216, 234, 251),
                android.graphics.Color.rgb(241, 248, 255),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        if (role == LiquidGlassSurfaceRole.Navigation) {
            drawLens(canvas, width * 0.18f, height * 0.18f, height * 0.86f, android.graphics.Color.argb(46, 93, 184, 255))
            drawLens(canvas, width * 0.74f, height * 0.22f, height * 0.64f, android.graphics.Color.argb(32, 126, 244, 232))
            drawLens(canvas, width * 0.76f, height * 0.86f, height * 0.56f, android.graphics.Color.argb(24, 157, 140, 255))
        }
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height * 0.56f,
            android.graphics.Color.argb(if (role == LiquidGlassSurfaceRole.Hero) 76 else 78, 255, 255, 255),
            android.graphics.Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height * 0.56f, paint)
        paint.shader = null
    }

    private fun drawLens(canvas: AndroidCanvas, x: Float, y: Float, radius: Float, color: Int) {
        paint.shader = RadialGradient(
            x,
            y,
            radius,
            color,
            android.graphics.Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(x, y, radius, paint)
    }
}
