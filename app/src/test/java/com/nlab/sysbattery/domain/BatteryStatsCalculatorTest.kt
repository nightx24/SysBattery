package com.nlab.sysbattery.domain

import com.nlab.sysbattery.data.BatterySysfsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryStatsCalculatorTest {
    private fun sample(time: Long, level: Int, chargeCounter: Long? = null) =
        BatterySysfsReader.Snapshot(
            timestampMillis = time,
            levelPercent = level,
            currentMicroAmps = -500_000L,
            voltageMilliVolts = 4_000L,
            chargeCounterMicroAmpHours = chargeCounter,
            temperatureTenthsCelsius = 340,
            source = "test"
        )

    @Test
    fun chargingPercentageIncreaseIsNotReportedAsDrain() {
        val result = BatteryStatsCalculator.calculate(
            previous = BatteryStatsCalculator.PreviousSample(0L, 40, -500_000L, 2_000_000L),
            current = sample(600_000L, 45, 2_200_000L),
            isCharging = true
        )
        assertNull(result.batteryPercentPerHour)
        assertEquals(BatteryStatsCalculator.Direction.CHARGING, result.direction)
    }

    @Test
    fun dischargeRateUsesOnlyPositivePercentageDrop() {
        val result = BatteryStatsCalculator.calculate(
            previous = BatteryStatsCalculator.PreviousSample(0L, 45, -500_000L, 2_200_000L),
            current = sample(600_000L, 44, 2_100_000L),
            isCharging = false
        )
        assertEquals(6.0, result.batteryPercentPerHour!!, 0.001)
        assertEquals(BatteryStatsCalculator.Direction.DISCHARGING, result.direction)
    }
}
