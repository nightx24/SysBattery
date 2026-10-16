package com.nlab.sysbattery.domain

import com.nlab.sysbattery.data.BatterySysfsReader
import kotlin.math.abs

/**
 * Estimates battery rates from a trailing window, rather than a single percentage step.
 * These are best-effort device metrics, not proprietary kernel-manager calculations.
 */
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
        val direction: Direction,
        val powerWattsMagnitude: Double? = null,
        val sampleCount: Int = 0,
        val currentSource: String = "unavailable"
    )

    data class TimedSample(
        val timestampMillis: Long,
        val levelPercent: Int?,
        val currentMicroAmps: Long?,
        val chargeCounterMicroAmpHours: Long?
    )

    private const val WINDOW_MS = 10 * 60 * 1000L
    private const val MIN_LEVEL_RATE_SPAN_MS = 3 * 60 * 1000L
    private const val MIN_CURRENT_SAMPLES = 3
    private const val MIN_CURRENT_SPAN_MS = 45 * 1000L
    private const val MIN_SAMPLE_SPACING_MS = 15 * 1000L
    private const val MAX_SAMPLES = 60
    private const val MAX_PLAUSIBLE_CURRENT_MA = 15_000.0
    private const val MAX_PLAUSIBLE_RATE = 500.0

    fun calculate(
        previous: PreviousSample?,
        current: BatterySysfsReader.Snapshot,
        isCharging: Boolean?
    ): Rates = calculateWindowed(
        previous?.let {
            listOf(TimedSample(it.timestampMillis, it.levelPercent, it.currentMicroAmps, it.chargeCounterMicroAmpHours))
        }.orEmpty() + TimedSample(
            current.timestampMillis, current.levelPercent, current.currentMicroAmps,
            current.chargeCounterMicroAmpHours
        ),
        current,
        isCharging
    )

    fun calculateWindowed(
        samples: List<TimedSample>,
        current: BatterySysfsReader.Snapshot,
        isCharging: Boolean?
    ): Rates {
        val direction = when (isCharging) {
            true -> Direction.CHARGING
            false -> Direction.DISCHARGING
            null -> Direction.UNKNOWN
        }
        val now = current.timestampMillis
        val window = samples.asSequence()
            .filter { it.timestampMillis <= now && now - it.timestampMillis <= WINDOW_MS }
            .sortedBy { it.timestampMillis }
            .fold(mutableListOf<TimedSample>()) { result, sample ->
                if (result.isEmpty() || sample.timestampMillis - result.last().timestampMillis >= MIN_SAMPLE_SPACING_MS) {
                    result.add(sample)
                }
                result
            }.takeLast(MAX_SAMPLES)

        val validCurrentSamples = window.mapNotNull { sample ->
            sample.currentMicroAmps?.let { raw ->
                val ma = abs(raw) / 1_000.0
                if (ma in 1.0..MAX_PLAUSIBLE_CURRENT_MA) sample.timestampMillis to ma else null
            }
        }
        val usableCurrent = validCurrentSamples.map { it.second }
        val currentMa = usableCurrent.takeIf { it.isNotEmpty() }?.average()
            ?: current.currentMicroAmps?.let { raw ->
                (abs(raw) / 1_000.0).takeIf { it in 1.0..MAX_PLAUSIBLE_CURRENT_MA }
            }
        val voltageMv = current.voltageMilliVolts?.takeIf { it in 2_500L..5_000L }
        val powerW = if (currentMa != null && voltageMv != null) currentMa * voltageMv / 1_000_000.0 else null

        val first = window.firstOrNull()
        val last = window.lastOrNull()
        val span = if (first != null && last != null) last.timestampMillis - first.timestampMillis else 0L
        val levelDrop = if (first?.levelPercent != null && last?.levelPercent != null) {
            first.levelPercent - last.levelPercent
        } else null
        val levelRate = if (direction == Direction.DISCHARGING && span >= MIN_LEVEL_RATE_SPAN_MS &&
            levelDrop != null && levelDrop > 0 && levelDrop <= 10) {
            levelDrop * 3_600_000.0 / span
        } else null

        val chargeDelta = if (first?.chargeCounterMicroAmpHours != null &&
            last?.chargeCounterMicroAmpHours != null) {
            (last.chargeCounterMicroAmpHours - first.chargeCounterMicroAmpHours) / 1_000.0
        } else null

        val fullCapacityMah = (current.fullChargeCapacityMicroAmpHours
            ?: current.estimatedFullChargeCapacityMicroAmpHours)
            ?.takeIf { it > 0L }?.div(1_000.0)
        val currentSpan = if (validCurrentSamples.size >= 2) {
            validCurrentSamples.last().first - validCurrentSamples.first().first
        } else 0L
        val currentDerivedRate = if (
            direction == Direction.DISCHARGING &&
            currentMa != null && fullCapacityMah != null && fullCapacityMah > 0.0 &&
            validCurrentSamples.size >= MIN_CURRENT_SAMPLES && currentSpan >= MIN_CURRENT_SPAN_MS
        ) currentMa * 100.0 / fullCapacityMah else null
        val pctRate = (currentDerivedRate ?: levelRate)?.takeIf { it in 0.0..MAX_PLAUSIBLE_RATE }
        val rate = pctRate
        val integratedMah = if (currentMa != null && span > 0L && direction == Direction.DISCHARGING) {
            currentMa * span / 3_600_000.0
        } else null

        return Rates(
            currentMilliAmpsMagnitude = currentMa,
            batteryPercentPerHour = rate,
            estimatedDischargeMilliAmpHours = integratedMah,
            chargeCounterDeltaMilliAmpHours = chargeDelta,
            direction = direction,
            powerWattsMagnitude = powerW?.takeIf { it.isFinite() },
            sampleCount = window.size,
            currentSource = if (usableCurrent.isNotEmpty()) "rolling-current-average" else if (currentMa != null) "instant-current" else "unavailable"
        )
    }
}
