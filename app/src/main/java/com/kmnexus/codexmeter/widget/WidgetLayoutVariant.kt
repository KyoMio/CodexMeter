package com.kmnexus.codexmeter.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

enum class WidgetLayoutVariant(
    /**
     * Reference size handed to [androidx.glance.appwidget.SizeMode.Responsive]. Glance picks the
     * largest reference that still fits the available space, so these must be spaced to the actual
     * rendered sizes of each cell span — not the declared minimums. A 3-column box on a typical
     * launcher is ~250-280dp wide, so the 4-column references sit well above that (300dp) to avoid
     * a roomy 3-column widget being matched to a 4-column layout. Likewise a 1-row box is ~100dp
     * tall, so the 2-row references sit at 150dp.
     */
    val referenceSize: DpSize,
    /** 该尺寸最多展示的字段数（按供应商天然顺序取前 N 个）。 */
    val fieldCapacity: Int,
) {
    ThreeByOne(DpSize(width = 180.dp, height = 60.dp), fieldCapacity = 1),
    FourByOne(DpSize(width = 300.dp, height = 60.dp), fieldCapacity = 2),
    ThreeByTwo(DpSize(width = 180.dp, height = 150.dp), fieldCapacity = 3),
    FourByTwo(DpSize(width = 300.dp, height = 150.dp), fieldCapacity = 4);

    companion object {
        /** SizeMode.Responsive 的参考集合——每个变体一个。 */
        val referenceSizes: Set<DpSize> = entries.map { it.referenceSize }.toSet()

        // 阈值落在窄(180dp)与宽(300dp)参考宽度之间、短(60dp)与高(150dp)参考高度之间，
        // 使 fromSize() 能把每个参考尺寸还原回它自己的变体。
        private val WIDE_THRESHOLD = 250.dp
        private val TALL_THRESHOLD = 110.dp

        fun fromSize(size: DpSize): WidgetLayoutVariant {
            val wide = size.width >= WIDE_THRESHOLD
            val tall = size.height >= TALL_THRESHOLD
            return when {
                wide && tall -> FourByTwo
                wide && !tall -> FourByOne
                !wide && tall -> ThreeByTwo
                else -> ThreeByOne
            }
        }
    }
}
