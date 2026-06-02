package com.kmnexus.codexmeter.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.kmnexus.codexmeter.BuildConfig
import com.kmnexus.codexmeter.data.local.dao.ProviderAccountDao
import com.kmnexus.codexmeter.data.local.dao.QuotaSnapshotDao
import com.kmnexus.codexmeter.data.local.dao.RefreshAttemptDao
import com.kmnexus.codexmeter.data.local.entity.ProviderAccountEntity
import com.kmnexus.codexmeter.data.local.entity.QuotaSnapshotEntity
import com.kmnexus.codexmeter.data.local.entity.RefreshAttemptEntity
import com.kmnexus.codexmeter.data.preferences.CurrentAccountReader
import com.kmnexus.codexmeter.data.secure.SecureSessionStore
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginDiagnosticsReader
import com.kmnexus.codexmeter.refresh.RefreshWorkScheduler
import com.kmnexus.codexmeter.ui.settings.SettingsDiagnosticsInput
import com.kmnexus.codexmeter.ui.settings.SettingsDiagnosticsReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SettingsDiagnosticsRepository(
    private val context: Context,
    private val currentAccountReader: CurrentAccountReader,
    private val providerAccountDao: ProviderAccountDao,
    private val quotaSnapshotDao: QuotaSnapshotDao,
    private val refreshAttemptDao: RefreshAttemptDao,
    private val sessionStore: SecureSessionStore,
    private val deviceCodeLoginDiagnosticsReader: DeviceCodeLoginDiagnosticsReader,
    private val workManagerStatusReader: WorkManagerStatusReader = AndroidWorkManagerStatusReader(context),
    private val notificationPermissionReader: NotificationPermissionReader = AndroidNotificationPermissionReader(context),
) : SettingsDiagnosticsReader {
    override suspend fun diagnosticsInput(): SettingsDiagnosticsInput {
        val selection = currentAccountReader.currentAccountSelection()
        val account = selection?.let { providerAccountDao.getById(it.localAccountId.value) }
        val accountMatchesSelection = account != null && account.providerId == selection?.providerId?.value
        val selectedProviderId = selection?.providerId?.value ?: CODEX_PROVIDER_ID
        val selectedLocalAccountId = selection?.localAccountId?.value
        val latestSnapshot = if (accountMatchesSelection && selectedLocalAccountId != null) {
            quotaSnapshotDao.getLatestForAccount(
                providerId = selectedProviderId,
                localAccountId = selectedLocalAccountId,
            )
        } else {
            null
        }
        val latestAttempt = if (accountMatchesSelection && selectedLocalAccountId != null) {
            refreshAttemptDao.getLatestForAccount(
                providerId = selectedProviderId,
                localAccountId = selectedLocalAccountId,
            )
        } else {
            null
        }
        val sessionEnvelope = if (accountMatchesSelection && selectedLocalAccountId != null) {
            runCatching {
                sessionStore.load(
                    providerId = selectedProviderId,
                    localAccountId = selectedLocalAccountId,
                )
            }.getOrNull()
        } else {
            null
        }
        val accountCount = providerAccountDao.listByProvider(CODEX_PROVIDER_ID).size
        val loginDiagnostics = deviceCodeLoginDiagnosticsReader.latestDeviceCodeLoginDiagnostics()

        return SettingsDiagnosticsInput(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            androidSdk = Build.VERSION.SDK_INT.toString(),
            providerId = selectedProviderId,
            accountDiagnosticId = selectedLocalAccountId?.let { "sha256:${it.safeHash()}" } ?: "not_connected",
            currentAccountSelectionStatus = selectionStatus(
                hasSelection = selection != null,
                account = account,
                accountMatchesSelection = accountMatchesSelection,
            ),
            accountStatus = account?.status,
            accountCount = accountCount,
            sessionEnvelopeStatus = when {
                !accountMatchesSelection -> "not_checked"
                sessionEnvelope == null -> "missing_or_unreadable"
                else -> "present"
            },
            sessionProviderAccountIdStatus = when {
                sessionEnvelope == null -> null
                sessionEnvelope.providerAccountId.isNullOrBlank() -> "missing"
                else -> "present"
            },
            currentState = currentState(
                account = account,
                accountMatchesSelection = accountMatchesSelection,
                latestSnapshot = latestSnapshot,
                latestAttempt = latestAttempt,
            ),
            latestSnapshotStatus = if (latestSnapshot == null) "missing" else "present",
            latestSnapshotSource = latestSnapshot?.source,
            latestSnapshotFetchedAt = latestSnapshot?.fetchedAt?.toString(),
            latestSnapshotDigestStatus = latestSnapshot?.responseDigest?.let { "present" } ?: "missing",
            lastSuccessfulRefreshAt = account?.lastSuccessfulRefreshAt?.toString() ?: "unavailable",
            lastAttemptStatus = latestAttempt?.status ?: "none",
            lastAttemptTrigger = latestAttempt?.trigger,
            lastAttemptStartedAt = latestAttempt?.startedAt?.toString(),
            lastAttemptFinishedAt = latestAttempt?.finishedAt?.toString(),
            safeErrorCode = latestAttempt?.errorCode,
            httpStatus = latestAttempt?.httpStatus,
            retryable = latestAttempt?.retryable,
            userActionRequired = latestAttempt?.userActionRequired,
            diagnosticsDigest = latestAttempt?.diagnosticsDigest,
            deviceCodeLoginStatus = loginDiagnostics.status,
            deviceCodeLoginAttemptId = loginDiagnostics.attemptId?.let { "sha256:${it.safeHash()}" },
            deviceCodeLoginSafeErrorCode = loginDiagnostics.safeErrorCode,
            deviceCodeLoginVerificationUriStatus = loginDiagnostics.verificationUriStatus,
            deviceCodeLoginPollIntervalSeconds = loginDiagnostics.pollIntervalSeconds,
            deviceCodeLoginExpiresAt = loginDiagnostics.expiresAt?.toString(),
            workManagerStatus = workManagerStatusReader.status(),
            notificationPermissionStatus = notificationPermissionReader.status(),
        )
    }

    private fun selectionStatus(
        hasSelection: Boolean,
        account: ProviderAccountEntity?,
        accountMatchesSelection: Boolean,
    ): String =
        when {
            !hasSelection -> "no_selection"
            account == null -> "selected_account_missing"
            !accountMatchesSelection -> "selected_account_provider_mismatch"
            else -> "selected_account_present"
        }

    private fun currentState(
        account: ProviderAccountEntity?,
        accountMatchesSelection: Boolean,
        latestSnapshot: QuotaSnapshotEntity?,
        latestAttempt: RefreshAttemptEntity?,
    ): String =
        when {
            account == null -> "noData"
            !accountMatchesSelection -> "accountSelectionMismatch"
            latestAttempt?.status == "failed" -> "failed"
            latestAttempt?.status == "cancelled" -> "cancelled"
            latestSnapshot != null -> "ready"
            else -> "noData"
        }

    private companion object {
        const val CODEX_PROVIDER_ID = "codex"
    }
}

internal fun interface WorkManagerStatusReader {
    suspend fun status(): String
}

internal fun interface NotificationPermissionReader {
    fun status(): String
}

private class AndroidWorkManagerStatusReader(
    context: Context,
) : WorkManagerStatusReader {
    private val appContext = context.applicationContext

    override suspend fun status(): String = withContext(Dispatchers.IO) {
        runCatching {
            val infos = WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(RefreshWorkScheduler.UNIQUE_PERIODIC_WORK_NAME)
                .get(750, TimeUnit.MILLISECONDS)
            if (infos.isEmpty()) {
                "not_enqueued:${RefreshWorkScheduler.UNIQUE_PERIODIC_WORK_NAME}"
            } else {
                "${RefreshWorkScheduler.UNIQUE_PERIODIC_WORK_NAME}:" + infos
                    .joinToString(separator = ",") { it.state.name.lowercase() }
            }
        }.getOrElse { throwable ->
            "query_failed:${throwable.javaClass.simpleName}"
        }
    }
}

private class AndroidNotificationPermissionReader(
    context: Context,
) : NotificationPermissionReader {
    private val appContext = context.applicationContext

    override fun status(): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            "not_required"
        } else if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            "granted"
        } else {
            "denied"
        }
}

private fun String.safeHash(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }.take(12)
}
