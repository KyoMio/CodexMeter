package com.kmnexus.codexmeter.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Verifies that choosing a notification account selection dismisses the
 * account-selection choice dialog (pendingChoiceDialog becomes null).
 *
 * Regression test for: updatePersistentNotificationAccount did not clear
 * pendingChoiceDialog, leaving the dialog open after the user tapped a choice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsNotificationAccountDialogTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting notification account clears the choice dialog`() {
        val viewModel = SettingsViewModel()

        // Open the NotificationAccount choice dialog.
        viewModel.requestChoiceDialog(SettingsChoiceDialog.NotificationAccount)

        // Confirm the dialog is open before making a selection.
        assert(viewModel.uiState.value.pendingChoiceDialog == SettingsChoiceDialog.NotificationAccount) {
            "Expected dialog to be open before selection, was ${viewModel.uiState.value.pendingChoiceDialog}"
        }

        // Perform the selection — this should close the dialog.
        viewModel.updatePersistentNotificationAccount(
            SettingsNotificationAccountSelection.FollowCurrent,
        )

        // The dialog must be dismissed.
        assertNull(
            "pendingChoiceDialog should be null after account selection, but was " +
                "${viewModel.uiState.value.pendingChoiceDialog}",
            viewModel.uiState.value.pendingChoiceDialog,
        )
    }
}
