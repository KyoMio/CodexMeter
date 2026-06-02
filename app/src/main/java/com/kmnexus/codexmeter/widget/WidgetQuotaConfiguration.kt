package com.kmnexus.codexmeter.widget

/** 微件可展示字段上限（与最大尺寸 4×2 的容量一致）。 */
const val WIDGET_MAX_FIELDS = 4

data class WidgetQuotaConfiguration(
    val providerId: String? = null,
    val localAccountId: String? = null,
    // 已选要展示的窗口 id，按供应商天然顺序，最多 WIDGET_MAX_FIELDS 个。
    val selectedWindowIds: List<String> = emptyList(),
) {
    val hasAccountOverride: Boolean
        get() = !providerId.isNullOrBlank() && !localAccountId.isNullOrBlank()

    val isDefault: Boolean
        get() = providerId.isNullOrBlank() && localAccountId.isNullOrBlank() && selectedWindowIds.isEmpty()

    fun accountMatches(providerId: String, localAccountId: String): Boolean =
        this.providerId == providerId && this.localAccountId == localAccountId
}

internal enum class WidgetConfigurationSection {
    Account,
    DisplayFields,
}

fun interface WidgetQuotaStateLoader {
    suspend fun loadWidgetQuotaState(configuration: WidgetQuotaConfiguration): WidgetQuotaState
}
