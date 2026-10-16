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
    fun unavailableCurrentDoesNotPreventPercentageRateCalculation() {
        val noCurrent = sample(1_800_000L, 39, 2_000_000L).copy(currentMicroAmps = null)
        val result = BatteryStatsCalculator.calculate(
            previous = BatteryStatsCalculator.PreviousSample(600_000L, 40, null, 2_100_000L),
            current = noCurrent,
            isCharging = false
        )
        assertEquals(6.0, result.batteryPercentPerHour!!, 0.001)
        assertNull(result.currentMilliAmpsMagnitude)
    }

    @Test
    fun equalBatteryLevelDoesNotInventPercentageDrain() {
        val result = BatteryStatsCalculator.calculate(
            previous = BatteryStatsCalculator.PreviousSample(0L, 45, null, null),
            current = sample(600_000L, 45),
            isCharging = false
        )
        assertNull(result.batteryPercentPerHour)
        assertEquals(BatteryStatsCalculator.Direction.DISCHARGING, result.direction)
    }

    @Test
    fun windowedRateWaitsForEnoughSamples() {
        val current = sample(60_000L, 49, 1_900_000L)
        val samples = listOf(
            BatteryStatsCalculator.TimedSample(0L, 50, -500_000L, 2_000_000L),
            BatteryStatsCalculator.TimedSample(20_000L, 50, -480_000L, 1_990_000L),
            BatteryStatsCalculator.TimedSample(40_000L, 50, -510_000L, 1_950_000L),
            BatteryStatsCalculator.TimedSample(60_000L, 49, -500_000L, 1_900_000L)
        )
        val result = BatteryStatsCalculator.calculateWindowed(samples, current, false)
        assertNull(result.batteryPercentPerHour)
        assertEquals(BatteryStatsCalculator.Direction.DISCHARGING, result.direction)
        org.junit.Assert.assertNotNull(result.currentMilliAmpsMagnitude)
        org.junit.Assert.assertNotNull(result.powerWattsMagnitude)
    }

    @Test
    fun windowedRateCalculatesRateFromThreeMinuteLevelWindow() {
        val current = sample(180_000L, 49, 1_900_000L)
        val samples = listOf(
            BatteryStatsCalculator.TimedSample(0L, 50, -500_000L, 2_000_000L),
            BatteryStatsCalculator.TimedSample(60_000L, 50, -480_000L, 1_990_000L),
            BatteryStatsCalculator.TimedSample(120_000L, 50, -510_000L, 1_950_000L),
            BatteryStatsCalculator.TimedSample(180_000L, 49, -500_000L, 1_900_000L)
        )
        val result = BatteryStatsCalculator.calculateWindowed(samples, current, false)
        assertEquals(20.0, result.batteryPercentPerHour!!, 0.001)
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
