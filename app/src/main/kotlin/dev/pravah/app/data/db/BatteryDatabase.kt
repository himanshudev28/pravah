package dev.pravah.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChargeSession::class], version = 4, exportSchema = false)
abstract class BatteryDatabase : RoomDatabase() {

    abstract fun chargeSessionDao(): ChargeSessionDao

    companion object {
        @Volatile private var INSTANCE: BatteryDatabase? = null

        /**
         * v1 → v2: add plugType; remove the duplicate rows written by the old double-repository bug
         * (same start/end percent, ending within the same minute).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_sessions ADD COLUMN plugType TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL(
                    """DELETE FROM charge_sessions WHERE id NOT IN (
                         SELECT MIN(id) FROM charge_sessions
                         GROUP BY startPercent, endPercent, endMillis / 60000)"""
                )
            }
        }

        /** v2 → v3: remember whether a fast-charge adapter was used. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_sessions ADD COLUMN fastCharger INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 → v4: merge sessions that an older version split when the cable was out for a moment
         * (next session starts < 2 min after the previous ended, same plug type, level continues).
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                data class Row(
                    val id: Long, val start: Long, val end: Long, val sp: Int, val ep: Int, val mah: Double,
                    val wear: Int, val peak: Double, val avg: Double, val plug: String, val fast: Int,
                )
                val rows = mutableListOf<Row>()
                db.query(
                    "SELECT id, startMillis, endMillis, startPercent, endPercent, mAhAdded, wearPermille, " +
                        "peakWatts, avgWatts, plugType, fastCharger FROM charge_sessions ORDER BY startMillis"
                ).use { c ->
                    while (c.moveToNext()) rows += Row(
                        c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getDouble(5),
                        c.getInt(6), c.getDouble(7), c.getDouble(8), c.getString(9), c.getInt(10),
                    )
                }
                var prev: Row? = null
                for (r in rows) {
                    val p = prev
                    if (p != null && r.start - p.end in 0 until 120_000 && r.plug == p.plug && r.sp >= p.ep - 1) {
                        val dp = (p.end - p.start).coerceAtLeast(1).toDouble()
                        val dr = (r.end - r.start).coerceAtLeast(1).toDouble()
                        val merged = p.copy(
                            end = r.end, ep = r.ep, mah = p.mah + r.mah, wear = p.wear + r.wear,
                            peak = maxOf(p.peak, r.peak), avg = (p.avg * dp + r.avg * dr) / (dp + dr),
                            fast = maxOf(p.fast, r.fast),
                        )
                        db.execSQL(
                            "UPDATE charge_sessions SET endMillis=?, endPercent=?, mAhAdded=?, wearPermille=?, " +
                                "peakWatts=?, avgWatts=?, fastCharger=? WHERE id=?",
                            arrayOf<Any>(merged.end, merged.ep, merged.mah, merged.wear, merged.peak, merged.avg, merged.fast, merged.id),
                        )
                        db.execSQL("DELETE FROM charge_sessions WHERE id=?", arrayOf<Any>(r.id))
                        prev = merged
                    } else prev = r
                }
            }
        }

        fun get(context: Context): BatteryDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(context.applicationContext, BatteryDatabase::class.java, "pravah.db")
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { INSTANCE = it }
            }
    }
}
