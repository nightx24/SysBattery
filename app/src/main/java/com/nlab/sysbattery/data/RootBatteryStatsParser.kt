package com.nlab.sysbattery.data

/**
 * Best-effort parser for human-readable Android dumpsys batterystats output.
 * Output varies by Android/OEM release; findings preserve source text and are not treated as
 * direct electrical measurements. Unknown lines are intentionally ignored.
 */
object RootBatteryStatsParser {
    data class Finding(val label: String, val value: String)

    private val estimatedPower = Regex("""(?i)^\s*estimated power use(?:\s*\([^)]*\))?\s*:\s*(.+?)\s*$""")
    private val capacity = Regex("""(?i)^\s*(?:battery )?capacity\s*[:=]\s*(.+?)\s*$""")
    private val discharge = Regex("""(?i)^\s*(discharge(?:d)?(?:\s+since.*)?|start clock time)\s*[:=]\s*(.+?)\s*$""")
    private val screen = Regex("""(?i)^\s*(screen(?: on| off)? time|screen-on time|screen-off time|screen)\s*:\s*(.+?)\s*$""")
    private val uidLine = Regex("""(?i)^\s*(uid\s+[^:]+)\s*:\s*(.+?)\s*$""")
    private val uidPower = Regex("""(?i)(?:^|[, (])(?:cpu|wifi|mobile radio|screen|camera|flashlight|bluetooth|gps|system|wake lock)\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)\s*m?ah\b""")
    private val wakeLock = Regex("""(?i)^\s*(?:wake lock|wakelock|partial wakelock)\s*[:=]?\s*(.+?)\s*$""")
    private val historyWakeLock = Regex("""(?i)(?:\+wake_lock=|wake_lock=|\+wakelock=)([^,\s]+)""")
    private val historyScreen = Regex("""(?i)(?:\+screen=|screen=)(on|off)\b""")
    private val historyState = Regex("""(?i)(\+running|\-running|\+wake_lock|\-wake_lock|\+sensor|\-sensor)\b""")

    fun parse(output: String, maxFindings: Int = 80): List<Finding> {
        if (output.isBlank() || maxFindings <= 0) return emptyList()
        val findings = linkedMapOf<String, Finding>()
        fun add(label: String, value: String) {
            val clean = value.trim().take(240)
            if (clean.isNotEmpty()) findings.putIfAbsent("$label:${clean.lowercase()}", Finding(label, clean))
        }

        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            estimatedPower.find(line)?.let { add("Estimated power", it.groupValues[1]) }
            capacity.find(line)?.let { add("Battery capacity", it.groupValues[1]) }
            discharge.find(line)?.let { add("Discharge", "${it.groupValues[1]}: ${it.groupValues[2]}") }
            screen.find(line)?.let { add("Screen time/state", "${it.groupValues[1]}: ${it.groupValues[2]}") }

            val uid = uidLine.find(line)
            if (uid != null && (line.contains("mah", ignoreCase = true) || line.contains("power", ignoreCase = true))) {
                add("UID power estimate", "${uid.groupValues[1]}: ${uid.groupValues[2]}")
            }
            wakeLock.find(line)?.let { add("Wake lock", it.groupValues[1]) }
            historyWakeLock.find(line)?.let { add("History wake-lock event", it.groupValues[1]) }
            historyScreen.find(line)?.let { add("History screen state", it.groupValues[1].lowercase()) }
            if (historyState.containsMatchIn(line)) add("History event", line)
            if (line.contains("Estimated power use", ignoreCase = true)) {
                uidPower.findAll(line).forEach { add("Component power estimate", it.value.trim()) }
            }
        }
        return findings.values.take(maxFindings)
    }
}
