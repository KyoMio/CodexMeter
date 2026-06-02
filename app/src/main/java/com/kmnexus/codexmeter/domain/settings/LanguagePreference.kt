package com.kmnexus.codexmeter.domain.settings

enum class LanguagePreference(val storageName: String, val localeTag: String?) {
    System("system", null),
    SimplifiedChinese("zh_hans", "zh-CN"),
    English("en", "en"),
}
