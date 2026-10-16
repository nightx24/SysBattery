package com.nlab.sysbattery.data.shizuku

import android.os.RemoteException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Shizuku User Service executes this fixed read-only query as shell.
 * No caller-provided command strings are accepted.
 */
class BatteryStatsUserService : IBatteryStatsUserService.Stub() {
    @Throws(RemoteException::class)
    override fun readChargedBatteryStats(): String {
        return try {
            val process = ProcessBuilder("dumpsys", "batterystats", "--charged")
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            val buffer = CharArray(2048)
            while (output.length < MAX_OUTPUT) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_OUTPUT - output.length))
                if (count < 0) break
                output.append(buffer, 0, count)
            }
            reader.close()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return "ERROR: BatteryStats command timed out."
            }
            if (process.exitValue() != 0) {
                "ERROR: dumpsys batterystats exited ${process.exitValue()}: ${output.toString().take(500)}"
            } else {
                output.toString()
            }
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "BatteryStats query failed"}"
        }
    }

    private companion object {
        const val MAX_OUTPUT = 48_000
        const val TIMEOUT_SECONDS = 12L
    }
}
