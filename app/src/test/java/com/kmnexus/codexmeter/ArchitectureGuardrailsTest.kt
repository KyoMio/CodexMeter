package com.kmnexus.codexmeter

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Deterministic source-scanning architecture guardrails.
 *
 * These tests encode the load-bearing boundaries of docs/ARCHITECTURE.md and
 * RULES.md as executable checks so they cannot silently rot. Every failure
 * message names the file, the violated boundary, and the exact fix.
 *
 * Pure JVM tests: no Android runtime, no mocks, just the source tree.
 */
class ArchitectureGuardrailsTest {

    // region tree helpers

    private val moduleDir: File = run {
        // Gradle unit tests run with the module dir as the working directory;
        // tolerate being launched from the repo root as well.
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        if (File(cwd, "src/main/java").isDirectory) cwd else File(cwd, "app").canonicalFile
    }

    private val mainSourceRoot: File =
        File(moduleDir, "src/main/java/com/kmnexus/codexmeter")

    private val repoRoot: File = moduleDir.parentFile.canonicalFile

    private fun kotlinFilesUnder(dir: File): List<File> =
        if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } else {
            emptyList()
        }

    private fun packageDir(name: String): File = File(mainSourceRoot, name)

    // endregion

    // region CM-2.1 package boundary: ui/

    @Test
    fun `ui layer never imports persistence networking keystore or provider internals`() {
        val forbiddenImport = Regex(
            "^import\\s+(androidx\\.room|androidx\\.datastore|okhttp3|java\\.security\\.KeyStore|android\\.security\\.keystore|com\\.kmnexus\\.codexmeter\\.providers\\.)",
        )
        // ProviderRegistry is the single, reviewed metadata seam the UI is
        // allowed to read (display names, auth kind). Any deeper providers.*
        // import drags networking/session code into the composition layer.
        val allowlist = setOf(
            "com.kmnexus.codexmeter.providers.ProviderRegistry",
            "com.kmnexus.codexmeter.providers.ProviderAuthKind",
            "com.kmnexus.codexmeter.providers.ProviderConfig",
        )
        val violations = scanImports(packageDir("ui")) { file, import ->
            if (forbiddenImport.containsMatchIn(import) && import.removePrefix("import ").trim() !in allowlist) {
                "$file imports `$import`"
            } else {
                null
            }
        }
        assertTrue(
            buildString {
                appendLine("ui/ must stay a pure composition layer (ARCHITECTURE.md §surfaces).")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: move the interaction behind a domain/app-layer use case or repository")
                appendLine("(e.g. expose the data through a ViewModel state flow). If the UI genuinely")
                appendLine("needs provider metadata, read it through ProviderRegistry, never through")
                appendLine("provider implementation classes. Room/DataStore/OkHttp/Keystore belong to")
                appendLine("data/ and providers/ only.")
            },
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.2 package boundary: widget/

    @Test
    fun `widget layer never imports networking provider internals or session decryption`() {
        val forbiddenImport = Regex(
            "^import\\s+(okhttp3|com\\.kmnexus\\.codexmeter\\.providers\\.)",
        )
        val allowlist = setOf(
            "com.kmnexus.codexmeter.providers.ProviderRegistry",
        )
        val importViolations = scanImports(packageDir("widget")) { file, import ->
            if (forbiddenImport.containsMatchIn(import) && import.removePrefix("import ").trim() !in allowlist) {
                "$file imports `$import`"
            } else {
                null
            }
        }
        // Decrypted sessions must never cross into the widget process surface:
        // the widget renders already-aggregated CurrentQuotaState only.
        val decryptionUsage = mutableListOf<String>()
        kotlinFilesUnder(packageDir("widget")).forEach { file ->
            val text = file.readText()
            listOf("SessionCipher", "decryptSession", "decryptedSession", "AndroidKeyStore", "keystoreDecrypt").forEach { needle ->
                if (text.contains(needle)) {
                    decryptionUsage += "${file.relativeTo(moduleDir)} references `$needle`"
                }
            }
        }
        val violations = importViolations + decryptionUsage
        assertTrue(
            buildString {
                appendLine("widget/ renders pre-aggregated quota state; it must never reach the network,")
                appendLine("provider implementations, or decrypted sessions (ARCHITECTURE.md security boundary).")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: read quota state through the app-layer CurrentQuotaStatePublisher/")
                appendLine("WidgetQuotaStateUpdater pipeline. Provider metadata may come only from")
                appendLine("ProviderRegistry. If the widget seems to need live network data, the refresh")
                appendLine("pipeline is incomplete — extend refresh/ instead of punching a hole in widget/.")
            },
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.3 package boundary: refresh/

    @Test
    fun `refresh layer never touches NotificationManager directly`() {
        val violations = mutableListOf<String>()
        kotlinFilesUnder(packageDir("refresh")).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (Regex("NotificationManager|NotificationCompat|notify\\(").containsMatchIn(line) &&
                    !line.trimStart().startsWith("//")
                ) {
                    violations += "${file.relativeTo(moduleDir)}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("refresh/ is a background pipeline: it publishes state and must not decide")
                appendLine("how the user gets told about it. Notifications are owned by notification/.")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: emit the state change through CurrentQuotaStatePublisher (or a dedicated")
                appendLine("event) and let notification/AndroidNotificationSink decide what to post.")
                appendLine("See docs/ARCHITECTURE.md — refresh writes state, notification renders it.")
            },
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.4 no new *Manager/*Helper/*Utils classes

    @Test
    fun `no new Manager Helper or Utils classes beyond the shrinking whitelist`() {
        val declaration = Regex(
            "^\\s*(internal\\s+|private\\s+|public\\s+)?(abstract\\s+|open\\s+|sealed\\s+|final\\s+)?(class|object)\\s+([A-Za-z0-9_]*(?:Manager|Helper|Utils))\\b",
        )
        // Existing offenders, grandfathered. This list may ONLY shrink:
        // remove entries as classes get renamed or deleted, never add new ones.
        // (Baseline scan 2026-11-27: the codebase currently has zero such classes.)
        val whitelist = setOf<String>()

        val offenders = mutableListOf<String>()
        kotlinFilesUnder(mainSourceRoot).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val match = declaration.find(line) ?: return@forEachIndexed
                val className = match.groupValues[4]
                if (className !in whitelist) {
                    offenders += "${file.relativeTo(moduleDir)}:${index + 1} declares `$className`"
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("`*Manager` / `*Helper` / `*Utils` classes are grab-bags that bypass the")
                appendLine("package ownership model (RULES.md). New ones are banned; the whitelist above")
                appendLine("can only shrink as legacy offenders are renamed.")
                appendLine("Violations:")
                offenders.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: name the class after the thing it DOES or OWNS in its package")
                appendLine("(e.g. `RefreshWorkScheduler`, `QuotaSnapshotDao`, `SessionCipher`). If it")
                appendLine("coordinates writes, make it a Repository/Store/Coordinator inside the")
                appendLine("owning package — not a cross-cutting helper.")
            },
            offenders.isEmpty(),
        )
    }

    // endregion

    // region CM-2.5 no Hilt/Retrofit/Ktor in gradle files

    @Test
    fun `gradle files never add Hilt Retrofit or Ktor`() {
        val gradleFiles = listOf(
            File(repoRoot, "build.gradle.kts"),
            File(repoRoot, "settings.gradle.kts"),
            File(repoRoot, "app/build.gradle.kts"),
            File(repoRoot, "gradle/libs.versions.toml"),
        )
        val banned = Regex("\\bhilt\\b|\\bdagger\\b|\\bretrofit\\b|\\bktor\\b", RegexOption.IGNORE_CASE)
        val violations = mutableListOf<String>()
        gradleFiles.filter { it.isFile }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (banned.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("#")) {
                    violations += "${file.relativeTo(repoRoot)}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("Hard red line (AGENTS.md): single-module Gradle, hand-written AppContainer,")
                appendLine("OkHttp + kotlinx.serialization only. Hilt/Retrofit/Ktor must never enter")
                appendLine("the dependency graph.")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: remove the dependency. Wire dependencies manually in AppContainer")
                appendLine("(app/AppContainer.kt) and use OkHttp + kotlinx.serialization for HTTP —")
                appendLine("see any existing provider network client for the pattern.")
            },
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.6 redaction scan: no raw credentials in logs/diagnostics

    @Test
    fun `sensitive credential fields never reach Log or diagnostics output`() {
        val sensitiveTokens = Regex(
            "\\b(accessToken|refreshToken|idToken|cookieHeader|authJson|rawResponseBody)\\b",
        )
        val violations = mutableListOf<String>()

        kotlinFilesUnder(mainSourceRoot).forEach { file ->
            val text = file.readText()
            val relative = file.relativeTo(moduleDir).toString()

            // 1) Log.x(...) statements must not mention credential fields.
            var searchFrom = 0
            while (true) {
                val logStart = Regex("Log\\.(d|e|i|w|v)\\s*\\(").find(text, searchFrom) ?: break
                val openParen = text.indexOf('(', logStart.range.first)
                var depth = 0
                var i = openParen
                while (i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    i++
                }
                val statement = text.substring(logStart.range.first, minOf(i + 1, text.length))
                if (sensitiveTokens.containsMatchIn(statement)) {
                    violations += "$relative passes a credential field into Log.* near offset ${logStart.range.first}"
                }
                searchFrom = i + 1
            }

            // 2) Diagnostics builders must never read credential fields at all —
            //    DiagnosticsRedactor is the single sanctioned exception because
            //    redaction itself has to know the field names.
            val isDiagnosticsFile =
                relative.contains("iagnostics") || relative.startsWith("app/SettingsDiagnosticsRepository")
            val isRedactor = file.name == "DiagnosticsRedactor.kt"
            if (isDiagnosticsFile && !isRedactor && sensitiveTokens.containsMatchIn(text)) {
                violations += "$relative references credential fields inside diagnostics construction"
            }
        }

        assertTrue(
            buildString {
                appendLine("Raw credentials must never appear in logs or diagnostics output")
                appendLine("(ARCHITECTURE.md security boundary, RULES.md redaction rules).")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: route every diagnostic/log rendering of sensitive data through")
                appendLine("domain/diagnostics/DiagnosticsRedactor.kt, and log only redacted digests.")
                appendLine("If you need a token presence indicator, log `<present>`/`<absent>` or a")
                appendLine("4-character redacted suffix — never the field value itself.")
            },
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.7 strings.xml key symmetry

    @Test
    fun `values strings xml and values-zh-rCN strings xml key sets are strictly equal`() {
        val english = File(moduleDir, "src/main/res/values/strings.xml")
        val chinese = File(moduleDir, "src/main/res/values-zh-rCN/strings.xml")
        assertTrue("Missing ${english.path}", english.isFile)
        assertTrue("Missing ${chinese.path}", chinese.isFile)

        fun keys(file: File): Set<String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            val result = LinkedHashSet<String>()
            for (i in 0 until nodes.length) {
                val element = nodes.item(i) as Element
                result += element.getAttribute("name")
            }
            return result
        }

        val englishKeys = keys(english)
        val chineseKeys = keys(chinese)
        val missingInChinese = englishKeys - chineseKeys
        val missingInEnglish = chineseKeys - englishKeys

        assertTrue(
            buildString {
                appendLine("values/strings.xml and values-zh-rCN/strings.xml must carry identical")
                appendLine("key sets so neither locale silently falls back or drops copy.")
                if (missingInChinese.isNotEmpty()) {
                    appendLine("Keys present in values/strings.xml but missing in values-zh-rCN:")
                    missingInChinese.forEach { appendLine("  - $it") }
                }
                if (missingInEnglish.isNotEmpty()) {
                    appendLine("Keys present in values-zh-rCN but missing in values/strings.xml:")
                    missingInEnglish.forEach { appendLine("  - $it") }
                }
                appendLine()
                appendLine("Fix: add the missing <string name=\"...\"> entry to the file that lacks it")
                appendLine("(translate the copy, or copy the English string as a placeholder and file a")
                appendLine("follow-up), or delete the key from the file that should not have it.")
            },
            missingInChinese.isEmpty() && missingInEnglish.isEmpty(),
        )
    }

    // endregion

    // region shared import-scanning helper

    private fun scanImports(dir: File, check: (relativeFile: String, import: String) -> String?): List<String> {
        val violations = mutableListOf<String>()
        kotlinFilesUnder(dir).forEach { file ->
            file.readLines().forEach { line ->
                if (line.startsWith("import ")) {
                    check(file.relativeTo(moduleDir).toString(), line)?.let { violations += it }
                }
            }
        }
        return violations
    }

    // endregion
}
