package com.nlab.sysbattery.data

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Optional privileged BatteryStats reader using the Shizuku shell identity.
 * This deliberately executes a fixed read-only command, never arbitrary UI text.
 */
class ShizukuBatteryStatsReader {
    data class Report(
        val findings: List<RootBatteryStatsParser.Finding>,
        val rawOutput: String,
        val exitCode: Int
    )

    fun read(): Report {
        check(Shizuku.pingBinder()) { "Shizuku is not running. Start Shizuku and retry." }
        check(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "SysBattery has not been authorized in Shizuku. Tap Enable Shizuku access first."
        }

        val process = Shizuku.newProcess(arrayOf("dumpsys", "batterystats", "--charged"), null, null)
        val stdout = FutureTask { BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() } }
        val stderr = FutureTask { BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() } }
        Thread(stdout, "SysBatteryShizukuStdout").apply { isDaemon = true; start() }
        Thread(stderr, "SysBatteryShizukuStderr").apply { isDaemon = true; start() }

        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy()
            throw IllegalStateException("Shizuku BatteryStats command timed out.")
        }
        val output = stdout.get(2, TimeUnit.SECONDS).take(MAX_OUTPUT)
        val error = stderr.get(2, TimeUnit.SECONDS).take(2_000)
        val exitCode = process.exitValue()
        if (exitCode != 0) {
            throw IllegalStateException(
                "Shizuku could not read BatteryStats (exit $exitCode): " +
                    error.ifBlank { output.take(500) }
            )
        }
        return Report(
            findings = RootBatteryStatsParser.parse(output, maxFindings = 100),
            rawOutput = output,
            exitCode = exitCode
        )
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
        const val MAX_OUTPUT = 48_000
    }
}
