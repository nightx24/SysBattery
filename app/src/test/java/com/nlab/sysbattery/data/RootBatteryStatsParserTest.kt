package com.nlab.sysbattery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RootBatteryStatsParserTest {
    @Test fun parsesEstimatedPowerScreenAndUidRows() {
        val findings = RootBatteryStatsParser.parse(
            """
            Estimated power use (mAh): 245.7
            Screen: 1h 20m 10s
            Uid u0a123: 12.4 ( cpu=7.2 wifi=1.1 )
            """.trimIndent()
        )
        assertTrue(findings.any { it.label == "Estimated power" && it.value == "245.7" })
        assertTrue(findings.any { it.label == "Screen time/state" && it.value.contains("1h 20m") })
        assertTrue(findings.any { it.label == "UID estimate (mAh)" && it.value.contains("u0a123") })
    }

    @Test fun parsesWakeLockAndHistoryEvents() {
        val findings = RootBatteryStatsParser.parse(
            """
            Wake lock: *alarm*
            9h12m +screen=on +wake_lock=*alarm*
            """.trimIndent()
        )
        assertTrue(findings.any { it.label == "Wake lock" && it.value.contains("*alarm*") })
        assertTrue(findings.any { it.label == "History screen state" && it.value == "on" })
        assertTrue(findings.any { it.label == "History wake-lock event" && it.value.contains("*alarm*") })
    }

    @Test fun parsesOplusEstimatedComponentAndUidRows() {
        val findings = RootBatteryStatsParser.parse(
            """
            Estimated power use (mAh):
              Capacity: 7400, Computed drain: 1229, actual drain: 1229
              Global
              screen: 427 apps: 427
              cpu: 657 apps: 656 duration: 6h
              UID u0a446: 296 fg: 115 bg: 6.94
            """.trimIndent()
        )
        assertTrue(findings.any { it.label == "Component estimate (mAh)" && it.value.startsWith("screen: 427") })
        assertTrue(findings.any { it.label == "Component estimate (mAh)" && it.value.startsWith("cpu: 657") })
        assertTrue(findings.any { it.label == "UID estimate (mAh)" && it.value.startsWith("UID u0a446: 296") })
    }

    @Test fun ignoresEmptyAndBoundsOutput() {
        assertEquals(0, RootBatteryStatsParser.parse("").size)
        assertEquals(2, RootBatteryStatsParser.parse("capacity: 4000\ncapacity: 4200", maxFindings = 2).size)
        assertEquals(1, RootBatteryStatsParser.parse("capacity: 4000\ncapacity: 4200", maxFindings = 1).size)
    }
}
