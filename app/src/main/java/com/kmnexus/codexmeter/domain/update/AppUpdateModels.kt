package com.kmnexus.codexmeter.domain.update

data class AppUpdateInfo(
    val versionName: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
)

sealed interface AppUpdateCheckResult {
    data class UpdateAvailable(val update: AppUpdateInfo) : AppUpdateCheckResult
    data object UpToDate : AppUpdateCheckResult
    data object NoRelease : AppUpdateCheckResult
    data object NoApkAsset : AppUpdateCheckResult
    data class Failure(val safeReason: String? = null) : AppUpdateCheckResult
}

fun interface AppUpdateCheckUseCase {
    suspend fun checkForUpdate(currentVersionName: String): AppUpdateCheckResult
}

object NoopAppUpdateCheckUseCase : AppUpdateCheckUseCase {
    override suspend fun checkForUpdate(currentVersionName: String): AppUpdateCheckResult = AppUpdateCheckResult.NoRelease
}

sealed interface AppUpdateDownloadResult {
    data class Enqueued(val downloadId: Long) : AppUpdateDownloadResult
    data class Failure(val safeReason: String? = null) : AppUpdateDownloadResult
}

fun interface AppUpdateDownloadUseCase {
    suspend fun download(update: AppUpdateInfo): AppUpdateDownloadResult
}

object NoopAppUpdateDownloadUseCase : AppUpdateDownloadUseCase {
    override suspend fun download(update: AppUpdateInfo): AppUpdateDownloadResult =
        AppUpdateDownloadResult.Failure("app_update_downloader_not_wired")
}
