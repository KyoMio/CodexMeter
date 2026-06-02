package com.kmnexus.codexmeter.ui.settings

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.core.i18n.AppLocaleController
import com.kmnexus.codexmeter.domain.settings.LanguagePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class SettingsAboutCopyTest {
    @Test
    fun `english about copy avoids mvp and provider-specific emphasis`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.English,
        )

        val version = context.getString(R.string.settings_about_version_format, "0.1.0", "MVP")
        val dataSource = context.getString(R.string.settings_about_data_source)
        val privacy = context.getString(R.string.settings_about_privacy)

        assertEquals("CodexMeter 0.1.0", version)
        assertEquals("Quota data comes from each provider's official APIs.", dataSource)
        assertEquals(
            "Login credentials are stored locally on this device. Apart from calling official provider APIs, CodexMeter does not include any remote-connection feature.",
            privacy,
        )
        assertFalse(dataSource.contains("OpenAI", ignoreCase = true))
        assertFalse(privacy.contains("token usage", ignoreCase = true))
    }

    @Test
    fun `english about copy exposes github repository link`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.English,
        )

        assertEquals("GitHub repository", context.getString(R.string.settings_about_repository_label))
        assertEquals("Open GitHub repository", context.getString(R.string.settings_about_repository_content_description))
    }

    @Test
    fun `simplified chinese about copy avoids mvp and provider-specific emphasis`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.SimplifiedChinese,
        )

        val version = context.getString(R.string.settings_about_version_format, "0.1.0", "MVP")
        val dataSource = context.getString(R.string.settings_about_data_source)
        val privacy = context.getString(R.string.settings_about_privacy)

        assertEquals("CodexMeter 0.1.0", version)
        assertEquals("额度数据来自各供应商官方 API。", dataSource)
        assertEquals(
            "登录凭据仅存储在本设备本地。除访问各供应商官方 API 外，CodexMeter 不包含任何远程连接功能。",
            privacy,
        )
        assertFalse(dataSource.contains("OpenAI", ignoreCase = true))
        assertFalse(privacy.contains("token", ignoreCase = true))
        assertTrue(privacy.contains("本地"))
    }

    @Test
    fun `simplified chinese about copy exposes github repository link`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.SimplifiedChinese,
        )

        assertEquals("GitHub 仓库", context.getString(R.string.settings_about_repository_label))
        assertEquals("打开 GitHub 仓库", context.getString(R.string.settings_about_repository_content_description))
    }
}
