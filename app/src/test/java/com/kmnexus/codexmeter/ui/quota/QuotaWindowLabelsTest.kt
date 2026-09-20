package com.kmnexus.codexmeter.ui.quota

import com.kmnexus.codexmeter.R
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaWindowLabelsTest {

    private val fiveHourSeconds = 5 * 60 * 60
    private val daySeconds = 24 * 60 * 60
    private val weekSeconds = 7 * daySeconds

    @Test
    fun `codex primary window reporting a weekly duration is labelled weekly`() {
        // Codex temporarily dropped the 5-hour window, so `primary_window` now carries the weekly
        // quota while the mapper still assigns it the positional id `five_hour`.
        assertEquals(
            R.string.account_quota_weekly_label,
            quotaWindowLabelRes(windowId = "five_hour", limitWindowSeconds = weekSeconds),
        )
    }

    @Test
    fun `codex slots are labelled five hour and weekly again once both durations are reported`() {
        assertEquals(
            R.string.account_quota_five_hour_label,
            quotaWindowLabelRes(windowId = "five_hour", limitWindowSeconds = fiveHourSeconds),
        )
        assertEquals(
            R.string.account_quota_weekly_label,
            quotaWindowLabelRes(windowId = "weekly", limitWindowSeconds = weekSeconds),
        )
    }

    @Test
    fun `codex secondary window reporting a five hour duration is labelled five hour`() {
        assertEquals(
            R.string.account_quota_five_hour_label,
            quotaWindowLabelRes(windowId = "weekly", limitWindowSeconds = fiveHourSeconds),
        )
    }

    @Test
    fun `codex window reporting a daily duration is labelled daily`() {
        assertEquals(
            R.string.window_label_daily,
            quotaWindowLabelRes(windowId = "five_hour", limitWindowSeconds = daySeconds),
        )
    }

    @Test
    fun `codex window reporting an unnameable duration falls back to the generic label`() {
        assertEquals(
            R.string.window_label_generic,
            quotaWindowLabelRes(windowId = "five_hour", limitWindowSeconds = 3 * 60 * 60),
        )
    }

    @Test
    fun `codex window without a reported duration falls back to the generic label`() {
        // A missing secondary window carries no duration. Keeping its positional "7-day" label made
        // Home show two "7-day quota" cards once the primary slot started reporting a weekly duration
        // (#4 follow-up), so an unnamed positional window must not claim a length either.
        assertEquals(
            R.string.window_label_generic,
            quotaWindowLabelRes(windowId = "five_hour", limitWindowSeconds = null),
        )
        assertEquals(
            R.string.window_label_generic,
            quotaWindowLabelRes(windowId = "weekly", limitWindowSeconds = null),
        )
    }

    @Test
    fun `provider named windows keep their own label even when the duration collides`() {
        // Claude names its windows itself: the Opus and Sonnet windows both last seven days but must
        // stay distinguishable, so a duration must never override a provider-named id.
        assertEquals(
            R.string.window_label_7d_opus,
            quotaWindowLabelRes(windowId = "claude_7d_opus_window", limitWindowSeconds = weekSeconds),
        )
        assertEquals(
            R.string.window_label_7d_sonnet,
            quotaWindowLabelRes(windowId = "claude_7d_sonnet_window", limitWindowSeconds = weekSeconds),
        )
        assertEquals(
            R.string.account_quota_five_hour_label,
            quotaWindowLabelRes(windowId = "claude_5h_window", limitWindowSeconds = weekSeconds),
        )
    }

    @Test
    fun `unknown window id still falls back to the generic label`() {
        assertEquals(
            R.string.window_label_generic,
            quotaWindowLabelRes(windowId = "brand_new_window", limitWindowSeconds = null),
        )
    }
}
