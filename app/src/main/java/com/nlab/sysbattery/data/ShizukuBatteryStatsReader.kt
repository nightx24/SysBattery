package com.nlab.sysbattery.data

import com.nlab.sysbattery.data.shizuku.IBatteryStatsUserService

/**
 * Parses BatteryStats gathered by the optional, authorized Shizuku User Service.
 */
class ShizukuBatteryStatsReader(
    private val service: IBatteryStatsUserService
) {
    data class Report(
        val findings: List<RootBatteryStatsParser.Finding>,
        val rawOutput: String
    )

    fun read(): Report {
        val output = service.readChargedBatteryStats()
        if (output.startsWith("ERROR:")) {
            throw IllegalStateException(output.removePrefix("ERROR:").trim())
        }
        return Report(
            findings = RootBatteryStatsParser.parse(output, maxFindings = 100),
            rawOutput = output
        )
    }
}
