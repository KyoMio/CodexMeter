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

    private val repoRoot: File = moduleDir.parentFile!!.canonicalFile

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
        val forbiddenPrefix = Regex(
            "(?:androidx\\.room|androidx\\.datastore|okhttp3|" +
                "java\\.security\\.KeyStore|android\\.security\\.keystore|" +
                "com\\.kmnexus\\.codexmeter\\.providers\\.|" +
                "com\\.kmnexus\\.codexmeter\\.data\\.local\\.|" +
                "com\\.kmnexus\\.codexmeter\\.data\\.secure\\.|" +
                "com\\.kmnexus\\.codexmeter\\.data\\.preferences\\.|" +
                "com\\.kmnexus\\.codexmeter\\.data\\.repository\\.)",
        )
        // ProviderRegistry is the single, reviewed metadata seam the UI is
        // allowed to read (display names, auth kind). Any deeper providers.*
        // import drags networking/session code into the composition layer.
        val allowlist = setOf(
            "com.kmnexus.codexmeter.providers.ProviderRegistry",
            "com.kmnexus.codexmeter.providers.ProviderAuthKind",
            "com.kmnexus.codexmeter.providers.ProviderConfig",
        )
        val violations = mutableListOf<String>()
        kotlinFilesUnder(packageDir("ui")).forEach { file ->
            val rel = file.relativeTo(moduleDir).toString()
            file.readLines().forEachIndexed { index, rawLine ->
                val line = rawLine.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                if (line.startsWith("import ")) {
                    val target = line.removePrefix("import ").substringBefore(" as ").trim()
                    if (forbiddenPrefix.containsMatchIn(target) && target !in allowlist) {
                        violations += "$rel:${index + 1} imports `$target`"
                    }
                    return@forEachIndexed
                }
                val fqnMatches = Regex(
                    "(?:androidx\\.room|androidx\\.datastore|okhttp3|" +
                        "java\\.security\\.KeyStore|android\\.security\\.keystore|" +
                        "com\\.kmnexus\\.codexmeter\\.(?:providers|data\\.(?:local|secure|preferences|repository)))" +
                        "\\.[A-Za-z0-9_.]+",
                ).findAll(line)
                fqnMatches.forEach { match ->
                    val ref = match.value
                    val isAllowlisted = allowlist.any { allowed ->
                        ref == allowed || ref.startsWith("$allowed.")
                    }
                    if (!isAllowlisted) {
                        violations += "$rel:${index + 1} references `$ref`"
                    }
                }
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
                appendLine("provider implementation classes. Room/DataStore/OkHttp/Keystore/data.* belong")
                appendLine("to data/, app/, and providers/ only.")
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
        // Decrypted sessions and raw session/cipher primitives must never cross into the widget surface:
        // the widget renders already-aggregated CurrentQuotaState only.
        val decryptionUsage = mutableListOf<String>()
        val decryptionNeedles = listOf(
            "SessionCipher",
            "decryptSession",
            "decryptedSession",
            "AndroidKeyStore",
            "keystoreDecrypt",
            "PayloadCipher",
            "AesGcmPayloadCipher",
            "SecureSessionStore",
            "FileSecureSessionStore",
            "ProviderSessionEnvelope",
        )
        kotlinFilesUnder(packageDir("widget")).forEach { file ->
            val text = file.readText()
            decryptionNeedles.forEach { needle ->
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

    // region CM-2.4 no new *Manager/*Helper/*Utils classes or files

    @Test
    fun `no new Manager Helper or Utils classes or files beyond the shrinking whitelist`() {
        val declaration = Regex(
            "^\\s*(?:(?:internal|private|public|protected)\\s+)?" +
                "(?:(?:abstract|open|sealed|final|data|enum|value|annotation)\\s+)*" +
                "(class|object|interface)\\s+([A-Za-z0-9_]*(?:Manager|Helper|Utils))\\b",
        )
        // Existing offenders, grandfathered. This list may ONLY shrink:
        // remove entries as classes get renamed or deleted, never add new ones.
        val whitelist = setOf<String>()
        val forbiddenFileSuffixes = listOf("Manager.kt", "Helper.kt", "Utils.kt")

        val offenders = mutableListOf<String>()
        kotlinFilesUnder(mainSourceRoot).forEach { file ->
            if (forbiddenFileSuffixes.any { file.name.endsWith(it) } && file.nameWithoutExtension !in whitelist) {
                offenders += "${file.relativeTo(moduleDir)} uses forbidden file suffix"
            }
            file.readLines().forEachIndexed { index, line ->
                val match = declaration.find(line) ?: return@forEachIndexed
                val className = match.groupValues[2]
                if (className !in whitelist) {
                    offenders += "${file.relativeTo(moduleDir)}:${index + 1} declares `$className`"
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("`*Manager` / `*Helper` / `*Utils` types and files are grab-bags that bypass")
                appendLine("the package ownership model (RULES.md). New ones are banned; the whitelist")
                appendLine("can only shrink as legacy offenders are renamed.")
                appendLine("Violations:")
                offenders.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Fix: name the file/type after the thing it DOES or OWNS in its package")
                appendLine("(e.g. `RefreshWorkScheduler`, `QuotaSnapshotDao`, `SessionCipher`).")
            },
            offenders.isEmpty(),
        )
    }

    // endregion

    // region CM-2.5 hard boundaries: gradle deps, single module, foreground service, release manifest

    @Test
    fun `gradle files never add Hilt Retrofit Ktor Firebase or Crashlytics`() {
        val gradleFiles = listOf(
            File(repoRoot, "build.gradle.kts"),
            File(repoRoot, "settings.gradle.kts"),
            File(repoRoot, "app/build.gradle.kts"),
            File(repoRoot, "gradle/libs.versions.toml"),
        )
        val banned = Regex("\\b(?:hilt|dagger|retrofit|ktor|firebase|crashlytics)", RegexOption.IGNORE_CASE)
        val violations = mutableListOf<String>()
        gradleFiles.filter { it.isFile }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val isComment = line.trimStart().startsWith("//") || line.trimStart().startsWith("#")
                if (banned.containsMatchIn(line) && !isComment) {
                    violations += "${file.relativeTo(repoRoot)}:${index + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            buildString {
                appendLine("Hard red line (AGENTS.md): single-module Gradle, hand-written AppContainer,")
                appendLine("OkHttp + kotlinx.serialization only, zero analytics/crash SDKs.")
                appendLine("Violations:")
                violations.forEach { appendLine("  - $it") }
            },
            violations.isEmpty(),
        )
    }

    @Test
    fun `settings gradle stays single-module app only`() {
        val settingsFile = File(repoRoot, "settings.gradle.kts")
        assertTrue("Missing ${settingsFile.path}", settingsFile.isFile)
        val includes = Regex("include\\s*\\(([^)]+)\\)")
            .findAll(settingsFile.readText())
            .flatMap { m ->
                Regex("\"([^\"]+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] }
            }
            .toList()
        assertTrue(
            "AGENTS.md requires single-module Gradle (include(\":app\") only), found: $includes",
            includes == listOf(":app"),
        )
    }

    @Test
    fun `main sources and manifest never introduce ForegroundService or DebugFixture`() {
        val mainManifest = File(moduleDir, "src/main/AndroidManifest.xml")
        assertTrue("Missing ${mainManifest.path}", mainManifest.isFile)
        val manifestText = mainManifest.readText()
        val violations = mutableListOf<String>()

        listOf("FOREGROUND_SERVICE", "foregroundServiceType", "DebugFixture", "SEED_FIXTURE").forEach { needle ->
            if (manifestText.contains(needle)) {
                violations += "src/main/AndroidManifest.xml contains forbidden `$needle`"
            }
        }
        val fgCodePattern = Regex("\\b(ForegroundService|startForeground|startForegroundService|FOREGROUND_SERVICE)\\b")
        kotlinFilesUnder(mainSourceRoot).forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!line.trimStart().startsWith("//") && fgCodePattern.containsMatchIn(line)) {
                    violations += "${file.relativeTo(moduleDir)}:${index + 1} references `${line.trim()}`"
                }
            }
        }
        assertTrue(
            "AGENTS.md hard boundary violated (ForegroundService / DebugFixture in main):\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // endregion

    // region CM-2.6 redaction scan: no raw credentials in logs/diagnostics

    @Test
    fun `sensitive credential fields never reach Log or diagnostics output`() {
        val sensitiveTokens = Regex(
            "\\b(accessToken|refreshToken|idToken|cookieHeader|authJson|rawResponseBody|" +
                "apiKey|cookieValue|codeVerifier|access_token|refresh_token|id_token)\\b",
        )
        val violations = mutableListOf<String>()

        kotlinFilesUnder(mainSourceRoot).forEach { file ->
            val text = file.readText()
            val relative = file.relativeTo(moduleDir).toString()

            // 1) Log.* / println(...) statements must not mention credential fields.
            violations += credentialLeaksInLogStatements(text, relative, sensitiveTokens)

            // 2) Diagnostics builders must never read credential fields at all —
            //    DiagnosticsRedactor is the single sanctioned exception because
            //    redaction itself has to know the field names.
            val isDiagnosticsFile =
                relative.contains("Diagnostics", ignoreCase = true) || file.name == "SettingsViewModel.kt"
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
            },
            missingInChinese.isEmpty() && missingInEnglish.isEmpty(),
        )
    }

    // endregion

    /**
     * Extracts every `Log.x(...)` / `println(...)` statement (parenthesis- and string-literal-aware)
     * and reports the ones whose content mentions a credential field.
     */
    private fun credentialLeaksInLogStatements(
        text: String,
        relative: String,
        sensitiveTokens: Regex,
    ): List<String> {
        val leaks = mutableListOf<String>()
        var searchFrom = 0
        val callPattern = Regex("(?:Log\\.(?:d|e|i|w|v|wtf|println)|\\bprintln)\\s*\\(")
        while (true) {
            val logStart = callPattern.find(text, searchFrom) ?: break
            val statementEnd = balancedStatementEnd(text, logStart.range.first)
            val statement = text.substring(logStart.range.first, statementEnd)
            if (sensitiveTokens.containsMatchIn(statement)) {
                leaks += "$relative passes a credential field into Log/println near offset ${logStart.range.first}"
            }
            searchFrom = statementEnd
        }
        return leaks
    }

    /**
     * End offset (exclusive) of the parenthesised expression starting at or after [fromIndex],
     * ignoring parens inside string/char literals.
     */
    private fun balancedStatementEnd(text: String, fromIndex: Int): Int {
        val openParen = text.indexOf('(', fromIndex)
        var depth = 0
        var i = openParen
        while (i < text.length) {
            val quotedEnd = skipLiteralAt(text, i)
            if (quotedEnd > i) {
                i = quotedEnd
                continue
            }
            if (text[i] == '(') depth++
            else if (text[i] == ')') {
                depth--
                if (depth == 0) return i + 1
            }
            i++
        }
        return text.length
    }

    private fun skipLiteralAt(text: String, start: Int): Int {
        if (text.startsWith("\"\"\"", start)) {
            val closeTriple = text.indexOf("\"\"\"", start + 3)
            return if (closeTriple == -1) text.length else closeTriple + 3
        }
        val quote = text[start]
        if (quote != '"' && quote != '\'') return start
        var i = start + 1
        while (i < text.length) {
            if (text[i] == '\\') {
                i += 2
            } else if (text[i] == quote) {
                return i + 1
            } else {
                i++
            }
        }
        return text.length
    }

    // region shared import-scanning helper

    private fun scanImports(dir: File, check: (relativeFile: String, import: String) -> String?): List<String> {
        val violations = mutableListOf<String>()
        kotlinFilesUnder(dir).forEach { file ->
            file.readLines().forEach { line ->
                if (!line.startsWith("import ")) return@forEach
                val violation = check(file.relativeTo(moduleDir).toString(), line) ?: return@forEach
                violations += violation
            }
        }
        return violations
    }

    // endregion
}
