package com.kmnexus.codexmeter.ui.settings

import android.content.Intent
import android.net.Uri

internal object SettingsRepositoryLinkTarget {
    private const val REPOSITORY_URL = "https://github.com/KyoMio/CodexMeter"

    fun openIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(REPOSITORY_URL))
}
