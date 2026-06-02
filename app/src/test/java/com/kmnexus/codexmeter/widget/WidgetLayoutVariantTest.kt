package com.kmnexus.codexmeter.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutVariantTest {
    @Test
    fun `each reference size round-trips to its own variant`() {
        // The SizeMode.Responsive reference set and fromSize() must agree: whichever
        // reference Glance hands back, fromSize() must resolve to that same variant.
        WidgetLayoutVariant.entries.forEach { variant ->
            assertEquals(variant, WidgetLayoutVariant.fromSize(variant.referenceSize))
        }
    }

    @Test
    fun `field capacity matches variant`() {
        assertEquals(1, WidgetLayoutVariant.ThreeByOne.fieldCapacity)
        assertEquals(2, WidgetLayoutVariant.FourByOne.fieldCapacity)
        assertEquals(3, WidgetLayoutVariant.ThreeByTwo.fieldCapacity)
        assertEquals(4, WidgetLayoutVariant.FourByTwo.fieldCapacity)
    }

    @Test
    fun `four column reference is wide enough to exclude a roomy three column widget`() {
        // Regression: a 3-column widget's available width can comfortably exceed 250dp
        // (cells are often 80-90dp). The 4-column reference must sit above that so a
        // 3-column box is not matched to a 4-column layout (the "squished 4x1/4x2" bug).
        assertTrue(WidgetLayoutVariant.FourByOne.referenceSize.width >= 290.dp)
        assertTrue(WidgetLayoutVariant.FourByTwo.referenceSize.width >= 290.dp)
    }

    @Test
    fun `two row reference is tall enough to exclude a tall one row widget`() {
        // Same idea vertically: a single-row widget can be ~100dp tall, so the 2-row
        // reference must sit clearly above that.
        assertTrue(WidgetLayoutVariant.ThreeByTwo.referenceSize.height >= 140.dp)
        assertTrue(WidgetLayoutVariant.FourByTwo.referenceSize.height >= 140.dp)
    }

}
