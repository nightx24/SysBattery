package com.nlab.sysbattery.domain

import com.nlab.sysbattery.data.BatterySysfsReader
import kotlin.math.abs

/** Transparent estimates; not claimed to match unverified proprietary formulas. */
object BatteryStatsCalculator {
    enum class Direction { CHARGING, DISCHARGING, UNKNOWN }

    data class PreviousSample(
        val timestampMillis: Long,
        val levelPercent: Int?,
        val currentMicroAmps: Long?,
        val chargeCounterMicroAmpHours: Long?
    )

    data class Rates(
        val currentMilliAmpsMagnitude: Double?,
        val batteryPercentPerHour: Double?,
        val estimatedDischargeMilliAmpHours: Double?,
        val chargeCounterDeltaMilliAmpHours: Double?,
        val direction: Direction
    )

    fun calculate(
        previous: PreviousSample?,
        current: BatterySysfsReader.Snapshot,
        isCharging: Boolean?
    ): Rates {
        val currentMa = current.currentMicroAmps?.div(1_000.0)
        val direction = when (isCharging) {
            true -> Direction.CHARGING
            false -> Direction.DISCHARGING
            null -> when {
                currentMa == null || currentMa == 0.0 -> Direction.UNKNOWN
                currentMa < 0 -> Direction.DISCHARGING // common convention, not universal
                else -> Direction.CHARGING
            }
        }
        if (previous == null) return Rates(currentMa?.let(::abs), null, null, null, direction)
        val elapsed = current.timestampMillis - previous.timestampMillis
        if (elapsed <= 0L) return Rates(currentMa?.let(::abs), null, null, null, direction)

        val percentPerHour = if (previous.levelPercent != null && current.levelPercent != null) {
            abs(current.levelPercent - previous.levelPercent) * 3_600_000.0 / elapsed
        } else null

        val counterDelta = if (previous.chargeCounterMicroAmpHours != null &&
            current.chargeCounterMicroAmpHours != null) {
            (current.chargeCounterMicroAmpHours - previous.chargeCounterMicroAmpHours) / 1_000.0
        } else null

        // µA / 1000 = mA; mA × elapsed milliseconds / 3,600,000 = mAh.
        val integratedMah = currentMa?.let { abs(it) * elapsed / 3_600_000.0 }
        return Rates(currentMilliAmpsMagnitude = currentMa?.let(::abs),
            batteryPercentPerHour = percentPerHour,
            estimatedDischargeMilliAmpHours = if (direction == Direction.DISCHARGING) integratedMah else null,
            chargeCounterDeltaMilliAmpHours = counterDelta,
            direction = direction)
    }
}
