package com.kmnexus.codexmeter.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kmnexus.codexmeter.CodexMeterApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking

/**
 * Debug-only fixture injection receiver. NEVER shipped in release builds: this class lives in
 * the `debug` source set and is registered in `app/src/debug/AndroidManifest.xml` only.
 *
 * The receiver is `android:exported="true"` and guarded by the signature-level permission
 * `com.kmnexus.codexmeter.debug.permission.SEED_FIXTURE` (see `app/src/debug/AndroidManifest.xml`).
 * Ordinary apps and `adb shell` (uid 2000) do not hold that permission on Android 12+, so the
 * sanctioned host-shell invocation runs under `run-as`:
 *
 * ```
 * adb shell run-as com.kmnexus.codexmeter.debug \
 *   am broadcast --user 0 \
 *   -a com.kmnexus.codexmeter.debug.SEED_FIXTURE \
 *   -p com.kmnexus.codexmeter.debug \
 *   --es scenario antigravity_2x2_depleted \
 *   [--es mode seed|clean] [--es nonce <id>]
 * ```
 *
 * `mode` defaults to `seed` (idempotent: pre-existing fixture rows are wiped first); `clean`
 * removes every fixture account and restores a clean state. Completion is reported through
 * logcat markers (`CMFIXTURE_OK scenario=<name>` / `CMFIXTURE_ERR scenario=<name> error=<msg>`)
 * so a CLI driver can assert the outcome, and through the ordered-broadcast result code.
 * Broadcasts are serialized on a single background executor to prevent interleaved store writes.
 */
class DebugFixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED_FIXTURE) return
        // Clean runs carry no scenario extra; label the marker "clean" so CLI assertions
        // (and humans reading logcat) match the mode they asked for instead of an empty name.
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_SEED
        val scenarioName = intent.getStringExtra(EXTRA_SCENARIO)
            ?: if (mode == MODE_CLEAN) MODE_CLEAN else ""
        // Echo a caller-supplied nonce so the CLI can confirm THIS execution instead of
        // matching a stale marker left in the ring buffer by an earlier run.
        val nonce = intent.getStringExtra(EXTRA_NONCE).orEmpty()
        val nonceSuffix = if (nonce.isEmpty()) "" else " nonce=$nonce"
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        executor.execute {
            runBlocking {
                try {
                    execute(appContext, scenarioName, mode)
                    Log.i(TAG, "$MARKER_OK scenario=$scenarioName mode=$mode$nonceSuffix")
                    pendingResult.setResultCode(RESULT_OK)
                } catch (error: Throwable) {
                    Log.e(TAG, "$MARKER_ERR scenario=$scenarioName error=${error.message}$nonceSuffix", error)
                    pendingResult.setResultCode(RESULT_ERR)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun execute(context: Context, scenarioName: String, mode: String) {
        val app = context as? CodexMeterApp
            ?: error("Application is ${context.javaClass.name}, expected CodexMeterApp")
        val bridge = app.debugFixtureBridge
            ?: error("DebugFixtureBridge seam is missing; AppContainer wiring incomplete")
        val seeder = FixtureSeeder(bridge)
        when (mode) {
            MODE_CLEAN -> seeder.cleanFixtureAccounts()
            MODE_SEED -> {
                val scenario = FixtureScenarios.build(scenarioName, bridge.clock.instant())
                    ?: error(
                        "Unknown fixture scenario '$scenarioName'; known: ${FixtureScenarios.ALL.joinToString()}",
                    )
                seeder.seed(scenario)
            }
            else -> error("Unknown fixture mode '$mode'; expected '$MODE_SEED' or '$MODE_CLEAN'")
        }
    }

    companion object {
        const val ACTION_SEED_FIXTURE = "com.kmnexus.codexmeter.debug.SEED_FIXTURE"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_MODE = "mode"
        const val EXTRA_NONCE = "nonce"
        const val MODE_SEED = "seed"
        const val MODE_CLEAN = "clean"

        const val MARKER_OK = "CMFIXTURE_OK"
        const val MARKER_ERR = "CMFIXTURE_ERR"
        const val RESULT_OK = 1
        const val RESULT_ERR = 2

        private const val TAG = "DebugFixtureReceiver"

        // Serialize broadcasts so concurrent seed/clean calls cannot interleave FileSecureSessionStore
        // or DataStore mutations.
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TAG).apply { isDaemon = true }
        }
    }
}
