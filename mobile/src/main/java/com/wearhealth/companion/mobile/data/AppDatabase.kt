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
    version = 6,
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

        /** v3 → v4: 加 analysisMethod + aiReport 字段（DeepSeek 分析方式支持） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN analysisMethod TEXT NOT NULL DEFAULT 'heartvoice'",
                )
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN aiReport TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /** v4 → v5: 加 tavilyStatus + ppgReferenceHr 字段（Tavily 状态展示 + PPG 参考心率） */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN tavilyStatus TEXT NOT NULL DEFAULT ''",
                )
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN ppgReferenceHr INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** v5 → v6: 加 processedByAlgorithm 字段（区分算法分析 vs 原始波形直传） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE ecg_measurements ADD COLUMN processedByAlgorithm INTEGER NOT NULL DEFAULT 1",
                )
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ecg_mobile.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { INSTANCE = it }
        }
    }
}
