package com.nlab.sysbattery.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Optional root backend. Executes a fixed, read-only command allowlist through su.
 * It never accepts arbitrary command text from the UI.
 */
class RootAccessReader {
    data class RootReport(
        val rooted: Boolean,
        val identity: String,
        val batteryStats: String?,
        val powerState: String?,
        val findings: List<RootBatteryStatsParser.Finding>,
        val error: String?
    )

    fun read(): RootReport {
        val identityResult = runRootCommand("id")
        val identity = identityResult.output.trim()
        val rooted = identityResult.exitCode == 0 &&
            (identity.contains("uid=0") || identity.contains("gid=0"))
        if (!rooted) {
            return RootReport(false, identity.ifBlank { "su unavailable or root denied" }, null, null, emptyList(),
                identityResult.error ?: "Root access was not granted. Install/configure Magisk or another su provider and approve the prompt.")
        }
        val battery = runRootCommand("dumpsys batterystats --charged | head -c 24000")
        val power = runRootCommand("dumpsys power | head -c 12000")
        return RootReport(
            rooted = true,
            identity = identity,
            batteryStats = battery.output.take(MAX_OUTPUT),
            powerState = power.output.take(MAX_OUTPUT),
            findings = RootBatteryStatsParser.parse(battery.output),
            error = listOfNotNull(
                battery.error?.let { "BatteryStats: $it" },
                power.error?.let { "Power state: $it" }
            ).joinToString("\n").ifBlank { null }
        )
    }

    private data class CommandResult(val exitCode: Int, val output: String, val error: String?)

    private fun runRootCommand(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return CommandResult(-1, "", "Command timed out")
            }
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            CommandResult(process.exitValue(), output, if (process.exitValue() == 0) null else "su command exited ${process.exitValue()}")
        } catch (e: Exception) {
            CommandResult(-1, "", "${e.javaClass.simpleName}: ${e.message ?: "root command unavailable"}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 12L
        private const val MAX_OUTPUT = 24_000
    }
}
