package com.kmnexus.codexmeter.data.repository

import com.kmnexus.codexmeter.core.network.ProviderHttpClient
import com.kmnexus.codexmeter.domain.update.AppUpdateCheckResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubReleaseAppUpdateCheckerTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `newer latest release with apk asset returns update`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "html_url": "https://github.com/KyoMio/CodexMeter/releases/tag/v0.2.0",
                      "assets": [
                        {
                          "name": "CodexMeter-0.2.0.apk",
                          "browser_download_url": "https://github.com/KyoMio/CodexMeter/releases/download/v0.2.0/CodexMeter-0.2.0.apk"
                        },
                        {
                          "name": "source.zip",
                          "browser_download_url": "https://example.test/source.zip"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val checker = newChecker()

        val result = checker.checkForUpdate(currentVersionName = "0.1.0-debug")

        assertTrue(result is AppUpdateCheckResult.UpdateAvailable)
        val update = (result as AppUpdateCheckResult.UpdateAvailable).update
        assertEquals("v0.2.0", update.versionName)
        assertEquals("CodexMeter-0.2.0.apk", update.apkFileName)
        assertEquals(
            "https://github.com/KyoMio/CodexMeter/releases/download/v0.2.0/CodexMeter-0.2.0.apk",
            update.apkDownloadUrl,
        )
        assertEquals("/repos/KyoMio/CodexMeter/releases/latest", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `same latest release is up to date`() = runTest {
        server.enqueue(latestReleaseResponse(tagName = "v0.1.0"))
        val checker = newChecker()

        val result = checker.checkForUpdate(currentVersionName = "0.1.0-debug")

        assertEquals(AppUpdateCheckResult.UpToDate, result)
    }

    @Test
    fun `latest release without apk asset is reported separately`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "tag_name": "v0.2.0",
                      "html_url": "https://github.com/KyoMio/CodexMeter/releases/tag/v0.2.0",
                      "assets": [
                        {
                          "name": "CodexMeter-0.2.0.zip",
                          "browser_download_url": "https://example.test/CodexMeter-0.2.0.zip"
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )
        val checker = newChecker()

        val result = checker.checkForUpdate(currentVersionName = "0.1.0")

        assertEquals(AppUpdateCheckResult.NoApkAsset, result)
    }

    @Test
    fun `github latest release 404 is no release`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("{} ").build())
        val checker = newChecker()

        val result = checker.checkForUpdate(currentVersionName = "0.1.0")

        assertEquals(AppUpdateCheckResult.NoRelease, result)
    }

    private fun newChecker(): GitHubReleaseAppUpdateChecker =
        GitHubReleaseAppUpdateChecker(
            httpClient = ProviderHttpClient(),
            latestReleaseApiUrl = server.url("/repos/KyoMio/CodexMeter/releases/latest").toString(),
        )

    private fun latestReleaseResponse(tagName: String): MockResponse =
        MockResponse.Builder()
            .code(200)
            .body(
                """
                {
                  "tag_name": "$tagName",
                  "html_url": "https://github.com/KyoMio/CodexMeter/releases/tag/$tagName",
                  "assets": [
                    {
                      "name": "CodexMeter-$tagName.apk",
                      "browser_download_url": "https://github.com/KyoMio/CodexMeter/releases/download/$tagName/CodexMeter.apk"
                    }
                  ]
                }
                """.trimIndent(),
            )
            .build()
}
