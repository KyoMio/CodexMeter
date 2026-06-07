package com.kmnexus.codexmeter.domain.theme

/** Global appearance preference. Persisted as a stable lowercase string. */
enum class ThemeMode(val storageValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system"),
    ;

    companion object {
        /** Unknown / null -> SYSTEM so corrupt storage degrades safely. */
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}
