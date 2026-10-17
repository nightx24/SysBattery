package com.nlab.sysbattery.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable, bounded battery telemetry history.
 *
 * Stores raw observations only. Estimated app/component energy is intentionally not inferred
 * from UsageStats, and unknown fields remain SQL NULL rather than being fabricated.
 */
class BatteryHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    data class HistoryRow(
        val timestampMillis: Long,
        val currentMicroAmps: Long?,
        val levelPercent: Int?,
        val voltageMilliVolts: Long?,
        val temperatureTenthsCelsius: Int?,
        val isPlugged: Boolean,
        val isScreenOn: Boolean,
        val batteryStatus: Int?,
        val chargeCounterMicroAmpHours: Long?,
        val fullChargeCapacityMicroAmpHours: Long?,
        val source: String
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timeStamp INTEGER NOT NULL,
                electric_current INTEGER,
                battery_level INTEGER,
                battery_voltage INTEGER,
                temperature INTEGER,
                is_plugged INTEGER NOT NULL,
                is_screen_on INTEGER NOT NULL,
                battery_status INTEGER,
                charge_counter_uah INTEGER,
                full_charge_capacity_uah INTEGER,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_battery_history_timestamp ON $TABLE(timeStamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No published schema exists yet; keep upgrades explicit and non-destructive.
        if (oldVersion < 2) {
            addColumnIfMissing(db, "full_charge_capacity_uah", "INTEGER")
            addColumnIfMissing(db, "source", "TEXT NOT NULL DEFAULT 'unknown'")
        }
    }

    @Synchronized
    fun record(
        timestampMillis: Long,
        currentMicroAmps: Long?,
        levelPercent: Int?,
        voltageMilliVolts: Long?,
        temperatureTenthsCelsius: Int?,
        isPlugged: Boolean,
        isScreenOn: Boolean,
        batteryStatus: Int?,
        chargeCounterMicroAmpHours: Long?,
        fullChargeCapacityMicroAmpHours: Long?,
        source: String
    ) {
        val values = ContentValues().apply {
            put("timeStamp", timestampMillis)
            putNullable("electric_current", currentMicroAmps)
            putNullable("battery_level", levelPercent)
            putNullable("battery_voltage", voltageMilliVolts)
            putNullable("temperature", temperatureTenthsCelsius)
            put("is_plugged", if (isPlugged) 1 else 0)
            put("is_screen_on", if (isScreenOn) 1 else 0)
            putNullable("battery_status", batteryStatus)
            putNullable("charge_counter_uah", chargeCounterMicroAmpHours)
            putNullable("full_charge_capacity_uah", fullChargeCapacityMicroAmpHours)
            put("source", source.take(256))
        }
        val db = writableDatabase
        db.insert(TABLE, null, values)
        // Bound storage to 30 days and run the prune periodically, not on every sample.
        if (timestampMillis - lastPruneMillis >= PRUNE_INTERVAL_MS) {
            db.delete(TABLE, "timeStamp < ?", arrayOf((timestampMillis - RETENTION_MS).toString()))
            lastPruneMillis = timestampMillis
        }
    }

    @Synchronized
    fun latest(limit: Int = 500): List<HistoryRow> {
        val rows = mutableListOf<HistoryRow>()
        readableDatabase.query(
            TABLE,
            arrayOf(
                "timeStamp", "electric_current", "battery_level", "battery_voltage",
                "temperature", "is_plugged", "is_screen_on", "battery_status",
                "charge_counter_uah", "full_charge_capacity_uah", "source"
            ),
            null, null, null, null, "timeStamp DESC", limit.coerceIn(1, 5_000).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                rows += HistoryRow(
                    timestampMillis = cursor.getLong(0),
                    currentMicroAmps = cursor.longOrNull(1),
                    levelPercent = cursor.intOrNull(2),
                    voltageMilliVolts = cursor.longOrNull(3),
                    temperatureTenthsCelsius = cursor.intOrNull(4),
                    isPlugged = cursor.getInt(5) != 0,
                    isScreenOn = cursor.getInt(6) != 0,
                    batteryStatus = cursor.intOrNull(7),
                    chargeCounterMicroAmpHours = cursor.longOrNull(8),
                    fullChargeCapacityMicroAmpHours = cursor.longOrNull(9),
                    source = cursor.getString(10) ?: "unknown"
                )
            }
        }
        return rows
    }

    private fun ContentValues.putNullable(key: String, value: Long?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun addColumnIfMissing(db: SQLiteDatabase, column: String, type: String) {
        val exists = db.rawQuery("PRAGMA table_info($TABLE)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == column) found = true
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE $TABLE ADD COLUMN $column $type")
    }

    companion object {
        private const val DATABASE_NAME = "battery_history.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "BatteryHistory"
        private const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val PRUNE_INTERVAL_MS = 60L * 60 * 1000
        @Volatile private var lastPruneMillis = 0L
    }
}
