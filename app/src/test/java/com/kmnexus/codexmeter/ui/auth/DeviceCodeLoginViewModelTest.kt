package com.kmnexus.codexmeter.ui.auth

import com.kmnexus.codexmeter.R
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginController
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginNotifier
import com.kmnexus.codexmeter.domain.auth.DeviceCodeLoginResult
import com.kmnexus.codexmeter.domain.model.LocalAccountId
import com.kmnexus.codexmeter.domain.model.ProviderAccount
import com.kmnexus.codexmeter.domain.model.ProviderAccountId
import com.kmnexus.codexmeter.domain.model.ProviderId
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCodeLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login entry mode starts device code flow and exposes code row with external verification url`() = runTest {
        val controller = RecordingDeviceCodeLoginController(
            startResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 5,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                ),
            ),
        )
        val notifier = RecordingDeviceCodeLoginNotifier()
        val viewModel = DeviceCodeLoginViewModel(
            controller = controller,
            notifier = notifier,
        )

        viewModel.applyEntryMode(AddAccountEntryMode.LoginToCodex)

        val uiState = viewModel.uiState.value
        assertEquals(DeviceCodeLoginUiStatus.AwaitingUserAuthorization, uiState.status)
        assertEquals("ABCD-EFGH", uiState.userCode)
        assertEquals("https://auth.openai.com/codex/device", uiState.verificationUri)
        assertEquals(listOf("waiting:attempt-1:ABCD-EFGH:https://auth.openai.com/codex/device"), notifier.events)
        assertEquals(1, controller.startCount)
        viewModel.cancelLogin()
        runCurrent()
    }

    @Test
    fun `polling success saves account and updates notifier`() = runTest {
        val account = account("local-1", "Codex Main")
        val controller = RecordingDeviceCodeLoginController(
            startResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 1,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                ),
            ),
            pollResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.Saved(
                        attemptId = "attempt-1",
                        account = account,
                    ),
                ),
            ),
        )
        val notifier = RecordingDeviceCodeLoginNotifier()
        val viewModel = DeviceCodeLoginViewModel(
            controller = controller,
            notifier = notifier,
        )

        viewModel.startCodexDeviceCodeLogin()
        advanceTimeBy(1_000)
        runCurrent()

        val uiState = viewModel.uiState.value
        assertEquals(DeviceCodeLoginUiStatus.Saved, uiState.status)
        assertEquals(LocalAccountId("local-1"), uiState.connectedAccount?.localAccountId)
        assertEquals("Codex Main", uiState.connectedAccount?.displayName)
        assertNull(uiState.verificationUri)
        assertEquals(true, uiState.shouldNavigateHomeAfterSave)
        assertTrue(notifier.events.contains("connected:Codex Main"))
        assertEquals(1, controller.pollCount)
    }

    @Test
    fun `pending authorization keeps polling until saved`() = runTest {
        val account = account("local-2", "Codex Later")
        val controller = RecordingDeviceCodeLoginController(
            startResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 1,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                ),
            ),
            pollResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 1,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                    DeviceCodeLoginResult.Saved(
                        attemptId = "attempt-1",
                        account = account,
                    ),
                ),
            ),
        )
        val viewModel = DeviceCodeLoginViewModel(controller = controller)

        viewModel.startCodexDeviceCodeLogin()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(DeviceCodeLoginUiStatus.AwaitingUserAuthorization, viewModel.uiState.value.status)
        assertEquals(1, controller.pollCount)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(DeviceCodeLoginUiStatus.Saved, viewModel.uiState.value.status)
        assertEquals(LocalAccountId("local-2"), viewModel.uiState.value.connectedAccount?.localAccountId)
        assertEquals(2, controller.pollCount)
    }

    @Test
    fun `validation failure is retryable and does not clear code`() = runTest {
        val account = account("local-1", "Codex Main")
        val controller = RecordingDeviceCodeLoginController(
            startResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 1,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                ),
            ),
            pollResults = ArrayDeque(
                listOf(DeviceCodeLoginResult.ValidationFailed("attempt-1", "error_network")),
            ),
            retryResults = ArrayDeque(
                listOf(DeviceCodeLoginResult.Saved("attempt-1", account)),
            ),
        )
        val viewModel = DeviceCodeLoginViewModel(controller = controller)

        viewModel.startCodexDeviceCodeLogin()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(DeviceCodeLoginUiStatus.ValidationFailed, viewModel.uiState.value.status)
        assertEquals("ABCD-EFGH", viewModel.uiState.value.userCode)
        assertEquals(R.string.error_network, viewModel.uiState.value.errorMessageResId)

        viewModel.retryValidation()

        assertEquals(DeviceCodeLoginUiStatus.Saved, viewModel.uiState.value.status)
        assertEquals(LocalAccountId("local-1"), viewModel.uiState.value.connectedAccount?.localAccountId)
    }

    @Test
    fun `cancel login cancels polling and notification`() = runTest {
        val controller = RecordingDeviceCodeLoginController(
            startResults = ArrayDeque(
                listOf(
                    DeviceCodeLoginResult.AwaitingUserAuthorization(
                        attemptId = "attempt-1",
                        userCode = "ABCD-EFGH",
                        verificationUri = "https://auth.openai.com/codex/device",
                        pollIntervalSeconds = 1,
                        expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
                    ),
                ),
            ),
            cancelResults = ArrayDeque(listOf(DeviceCodeLoginResult.Cancelled("attempt-1"))),
        )
        val notifier = RecordingDeviceCodeLoginNotifier()
        val viewModel = DeviceCodeLoginViewModel(
            controller = controller,
            notifier = notifier,
        )

        viewModel.startCodexDeviceCodeLogin()
        viewModel.cancelLogin()
        advanceTimeBy(5_000)

        assertEquals(DeviceCodeLoginUiStatus.Cancelled, viewModel.uiState.value.status)
        assertNull(viewModel.uiState.value.userCode)
        assertNull(viewModel.uiState.value.verificationUri)
        assertNull(viewModel.uiState.value.expiresAt)
        assertNull(viewModel.uiState.value.pollIntervalSeconds)
        assertEquals(0, controller.pollCount)
        assertTrue(notifier.events.contains("cancelled"))
    }

    @Test
    fun `import route value is ignored instead of starting legacy file import`() {
        val controller = RecordingDeviceCodeLoginController()
        val viewModel = DeviceCodeLoginViewModel(controller = controller)

        viewModel.applyEntryMode(AddAccountEntryMode.fromRouteValue("import"))

        assertEquals(DeviceCodeLoginUiStatus.Idle, viewModel.uiState.value.status)
        assertEquals(0, controller.startCount)
        assertNull(viewModel.uiState.value.userCode)
    }

    private class RecordingDeviceCodeLoginController(
        private val startResults: ArrayDeque<DeviceCodeLoginResult> = ArrayDeque(),
        private val pollResults: ArrayDeque<DeviceCodeLoginResult> = ArrayDeque(),
        private val retryResults: ArrayDeque<DeviceCodeLoginResult> = ArrayDeque(),
        private val cancelResults: ArrayDeque<DeviceCodeLoginResult> = ArrayDeque(),
    ) : DeviceCodeLoginController {
        var startCount = 0
            private set
        var pollCount = 0
            private set

        override suspend fun startLogin(): DeviceCodeLoginResult {
            startCount += 1
            return startResults.removeFirstOrNull() ?: DeviceCodeLoginResult.Failed(null, "error_unknown")
        }

        override suspend fun pollLatest(): DeviceCodeLoginResult {
            pollCount += 1
            return pollResults.removeFirstOrNull() ?: DeviceCodeLoginResult.AwaitingUserAuthorization(
                attemptId = "attempt-1",
                userCode = "ABCD-EFGH",
                verificationUri = "https://auth.openai.com/codex/device",
                pollIntervalSeconds = 1,
                expiresAt = Instant.parse("2026-05-25T09:15:00Z"),
            )
        }

        override suspend fun retryValidation(): DeviceCodeLoginResult =
            retryResults.removeFirstOrNull() ?: DeviceCodeLoginResult.Failed("attempt-1", "error_unknown")

        override suspend fun cancelLatest(): DeviceCodeLoginResult =
            cancelResults.removeFirstOrNull() ?: DeviceCodeLoginResult.Cancelled("attempt-1")
    }

    private class RecordingDeviceCodeLoginNotifier : DeviceCodeLoginNotifier {
        val events = mutableListOf<String>()

        override fun waitingForAuthorization(attemptId: String, userCode: String, verificationUri: String) {
            events += "waiting:$attemptId:$userCode:$verificationUri"
        }

        override fun connected(accountDisplayName: String?) {
            events += "connected:$accountDisplayName"
        }

        override fun expired() {
            events += "expired"
        }

        override fun failed(safeMessageKey: String) {
            events += "failed:$safeMessageKey"
        }

        override fun validationFailed(safeMessageKey: String) {
            events += "validationFailed:$safeMessageKey"
        }

        override fun cancelled() {
            events += "cancelled"
        }
    }

    private fun account(id: String, displayName: String): ProviderAccount =
        ProviderAccount.createNew(
            localAccountId = LocalAccountId(id),
            providerId = ProviderId("codex"),
            providerAccountId = ProviderAccountId("provider-$id"),
            displayName = displayName,
            now = Instant.parse("2026-05-23T10:00:00Z"),
        )
}
