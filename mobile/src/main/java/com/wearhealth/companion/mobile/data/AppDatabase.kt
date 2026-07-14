package com.wearhealth.companion.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Phone-side Room database. */
@Database(
    entities = [EcgMeasurementEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ecgMeasurementDao(): EcgMeasurementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Makes existing databases safe for retransmission before adding the unique timestamp index. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "DELETE FROM ecg_measurements WHERE id NOT IN " +
                        "(SELECT MIN(id) FROM ecg_measurements GROUP BY timestamp)",
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_ecg_measurements_timestamp " +
                        "ON ecg_measurements(timestamp)",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN possibleDiagnoses TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN isReverse INTEGER NOT NULL DEFAULT 0",
                )
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN avgP INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ecg_mobile.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
        }
    }
}
