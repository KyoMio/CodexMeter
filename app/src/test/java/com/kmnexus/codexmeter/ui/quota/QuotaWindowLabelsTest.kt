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
    fun `kimi cycle never trusts the historical weekly id or cached hardcoded duration`() {
        listOf(null, weekSeconds, 30 * daySeconds).forEach { seconds ->
            assertEquals(R.string.window_label_cycle, quotaWindowLabelRes("kimi_weekly_window", seconds))
        }
    }

    @Test
    fun `kimi rate label follows API duration and is neutral when unknown`() {
        assertEquals(R.string.account_quota_five_hour_label, quotaWindowLabelRes("kimi_rate_window", fiveHourSeconds))
        assertEquals(R.string.window_label_daily, quotaWindowLabelRes("kimi_rate_window", daySeconds))
        listOf(null, 3 * 60 * 60).forEach { seconds ->
            assertEquals(R.string.window_label_cycle, quotaWindowLabelRes("kimi_rate_window", seconds))
        }
    }

    @Test
    fun `kimi code named windows keep fixed labels regardless of duration`() {
        assertEquals(R.string.account_quota_five_hour_label, quotaWindowLabelRes("kimi_code_5h", fiveHourSeconds))
        assertEquals(R.string.account_quota_weekly_label, quotaWindowLabelRes("kimi_code_7d", weekSeconds))
        assertEquals(R.string.window_label_monthly, quotaWindowLabelRes("kimi_code_month_total", null))
        // The cycle fallback window must never claim a period.
        assertEquals(R.string.window_label_cycle, quotaWindowLabelRes("kimi_code_usage", null))
        assertEquals(R.string.window_label_cycle, quotaWindowLabelRes("kimi_code_usage", weekSeconds))
    }

    @Test
    fun `kimi code rate label follows API duration and is neutral when unknown`() {
        assertEquals(R.string.account_quota_five_hour_label, quotaWindowLabelRes("kimi_code_rate", fiveHourSeconds))
        assertEquals(R.string.window_label_daily, quotaWindowLabelRes("kimi_code_rate", daySeconds))
        listOf(null, 3 * 60 * 60).forEach { seconds ->
            assertEquals(R.string.window_label_cycle, quotaWindowLabelRes("kimi_code_rate", seconds))
        }
    }

    @Test
    fun `antigravity per-model family windows keep their named labels`() {
        // Only two families remain (T-505): the claude_gpt window carries Claude, GPT-OSS and every
        // unknown model under one label; the gemini window keeps its own.
        assertEquals(R.string.window_label_family_claude_gpt, quotaWindowLabelRes("antigravity_claude_gpt_window", null))
        assertEquals(R.string.window_label_family_gemini, quotaWindowLabelRes("antigravity_gemini_window", null))
    }

    @Test
    fun `antigravity summary family x lane windows keep their named labels`() {
        assertEquals(
            R.string.window_label_family_gemini_5h,
            quotaWindowLabelRes("antigravity_gemini_5h_window", fiveHourSeconds),
        )
        assertEquals(
            R.string.window_label_family_gemini_weekly,
            quotaWindowLabelRes("antigravity_gemini_weekly_window", weekSeconds),
        )
        assertEquals(
            R.string.window_label_family_claude_gpt_5h,
            quotaWindowLabelRes("antigravity_claude_gpt_5h_window", fiveHourSeconds),
        )
        assertEquals(
            R.string.window_label_family_claude_gpt_weekly,
            quotaWindowLabelRes("antigravity_claude_gpt_weekly_window", weekSeconds),
        )
        // The cycle lane keeps its own family labels (T-506): no Antigravity window falls back to
        // the bare generic label.
        assertEquals(
            R.string.window_label_family_gemini_cycle,
            quotaWindowLabelRes("antigravity_gemini_cycle_window", null),
        )
        assertEquals(
            R.string.window_label_family_claude_gpt_cycle,
            quotaWindowLabelRes("antigravity_claude_gpt_cycle_window", null),
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
