package com.kmnexus.codexmeter.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadResult
import com.kmnexus.codexmeter.domain.update.AppUpdateDownloadUseCase
import com.kmnexus.codexmeter.domain.update.AppUpdateInfo

class AndroidAppUpdateDownloader(
    context: Context,
) : AppUpdateDownloadUseCase {
    private val appContext = context.applicationContext

    override suspend fun download(update: AppUpdateInfo): AppUpdateDownloadResult {
        val downloadManager = appContext.getSystemService(DownloadManager::class.java)
            ?: return AppUpdateDownloadResult.Failure("download_manager_unavailable")

        return runCatching {
            val request = DownloadManager.Request(Uri.parse(update.apkDownloadUrl))
                .setTitle(update.apkFileName)
                .setDescription(
                    appContext.getString(R.string.settings_update_download_description, update.versionName),
                )
                .setMimeType(APK_MIME_TYPE)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, update.apkFileName)
            val downloadId = downloadManager.enqueue(request)
            AppUpdateDownloadResult.Enqueued(downloadId)
        }.getOrElse {
            AppUpdateDownloadResult.Failure("download_enqueue_failed")
        }
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
