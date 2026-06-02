package com.kmnexus.codexmeter

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric SDK 36 currently requires Java 21, while this project is pinned to Java 17.
@Config(sdk = [35])
class MainActivityCompositionLocalsTest {
    @Test
    fun `activity content keeps activity result registry owner available`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.setContent {
            assertNotNull(LocalActivityResultRegistryOwner.current)
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            }
        }
    }
}
