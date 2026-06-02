package com.kmnexus.codexmeter.core.i18n

import android.app.LocaleManager
import android.os.LocaleList
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.settings.LanguagePreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class AppLocaleControllerTest {
    @Test
    fun `localized context resolves simplified chinese resources`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.SimplifiedChinese,
        )

        assertEquals("设置", context.getString(R.string.settings_title))
    }

    @Test
    fun `localized context resolves english resources`() {
        val context = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = LanguagePreference.English,
        )

        assertEquals("Settings", context.getString(R.string.settings_title))
    }

    @Test
    fun `localized context for system locale does not reuse previous override`() {
        val systemLocale = AppLocaleController.supportedSystemLocaleList(LocaleList.getDefault())[0]
        val previousOverride = if (systemLocale.language == "en") {
            LanguagePreference.SimplifiedChinese
        } else {
            LanguagePreference.English
        }
        val overriddenContext = AppLocaleController.localizedContext(
            context = RuntimeEnvironment.getApplication(),
            preference = previousOverride,
        )

        val systemContext = AppLocaleController.localizedContext(
            context = overriddenContext,
            preference = LanguagePreference.System,
        )

        assertNotEquals(systemLocale.language, overriddenContext.resources.configuration.locales[0].language)
        assertEquals(systemLocale.language, systemContext.resources.configuration.locales[0].language)
    }

    @Test
    fun `system language resolution falls back to english when unsupported`() {
        assertEquals(
            "en",
            AppLocaleController.supportedSystemLocaleList(LocaleList.forLanguageTags("ja-JP"))
                .toLanguageTags(),
        )
    }

    @Test
    fun `system language resolution keeps supported simplified chinese`() {
        assertEquals(
            "zh-CN",
            AppLocaleController.supportedSystemLocaleList(LocaleList.forLanguageTags("zh-CN,en"))
                .toLanguageTags(),
        )
    }

    @Test
    fun `ensure follows system clears persisted platform locale override`() {
        val context = RuntimeEnvironment.getApplication()
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager.applicationLocales = LocaleList.forLanguageTags("en")

        AppLocaleController.ensureFollowsSystemLocale(context)

        assertEquals("", localeManager.applicationLocales.toLanguageTags())
    }
}