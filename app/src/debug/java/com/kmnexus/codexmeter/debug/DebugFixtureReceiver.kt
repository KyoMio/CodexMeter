package com.kmnexus.codexmeter.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kmnexus.codexmeter.CodexMeterApp
import kotlinx.coroutines.runBlocking

/**
 * Debug-only fixture injection receiver. NEVER shipped in release builds: this class lives in
 * the `debug` source set and is registered in `app/src/debug/AndroidManifest.xml` only.
 *
 * Usage from the host shell (the shell user can reach non-exported receivers, the standard
 * adb-driven debug pattern):
 *
 * ```
 * adb shell am broadcast \
 *   -a com.kmnexus.codexmeter.debug.SEED_FIXTURE \
 *   --es scenario antigravity_2x2_depleted \
 *   [--es mode seed|clean]
 * ```
 *
 * `mode` defaults to `seed` (idempotent: pre-existing fixture rows are wiped first); `clean`
 * removes every fixture account and restores a clean state. Completion is reported through
 * logcat markers (`CMFIXTURE_OK scenario=<name>` / `CMFIXTURE_ERR scenario=<name> error=<msg>`)
 * so a CLI driver can assert the outcome, and through the ordered-broadcast result code.
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
        Thread {
            runBlocking {
                try {
                    execute(context.applicationContext, scenarioName, mode)
                    Log.i(TAG, "$MARKER_OK scenario=$scenarioName mode=$mode$nonceSuffix")
                    pendingResult.setResultCode(RESULT_OK)
                } catch (error: Throwable) {
                    Log.e(TAG, "$MARKER_ERR scenario=$scenarioName error=${error.message}$nonceSuffix", error)
                    pendingResult.setResultCode(RESULT_ERR)
                } finally {
                    pendingResult.finish()
                }
            }
        }.start()
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
    }
}
