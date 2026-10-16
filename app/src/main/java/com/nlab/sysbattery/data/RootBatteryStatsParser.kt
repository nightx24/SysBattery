package com.nlab.sysbattery.data

/**
 * Conservative parser for human-readable dumpsys batterystats output.
 * Android/OEM output is not a stable API, so only explicit matching lines are surfaced.
 */
object RootBatteryStatsParser {
    data class Finding(val label: String, val value: String)

    private val patterns = listOf(
        "Estimated power" to Regex("""(?i)^\\s*(estimated power use.*?)(?::\\s*)(.+?)\\s*$"""),
        "Battery capacity" to Regex("""(?i)^\\s*(?:capacity|battery capacity)\\s*[:=]\\s*(.+?)\\s*$"""),
        "Discharge" to Regex("""(?i)^\\s*(discharge(?:d)?(?:\\s+since.*)?|start clock time)\\s*[:=]\\s*(.+?)\\s*$"""),
        "Screen" to Regex("""(?i)^\\s*(screen(?: on| off)? time|screen-on time|screen-off time)\\s*[:=]\\s*(.+?)\\s*$"""),
        "Wake locks" to Regex("""(?i)^\\s*(partial wakelock(?:s)?|wake lock(?:s)?)\\s*[:=]\\s*(.+?)\\s*$"""),
        "UID power" to Regex("""(?i)^\\s*(uid\\s+\\d+.*?(?:power|mah|mAh).*?)\\s*[:=]\\s*(.+?)\\s*$""")
    )

    fun parse(output: String, maxFindings: Int = 24): List<Finding> {
        if (output.isBlank()) return emptyList()
        val findings = linkedMapOf<String, Finding>()
        output.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            patterns.forEach { (label, regex) ->
                val match = regex.find(trimmed) ?: return@forEach
                val key = "${label}:${match.groupValues[1].lowercase()}"
                findings.putIfAbsent(key, Finding(label, match.groupValues[2].trim().take(240)))
            }
        }
        return findings.values.take(maxFindings)
    }
}
