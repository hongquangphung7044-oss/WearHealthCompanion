package com.wearhealth.companion.mobile.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 手机端 Room 数据库
 *
 * 仅包含 ECG 测量记录表。数据库实例通过 [get] 单例获取。
 */
@Database(
    entities = [EcgMeasurementEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun ecgMeasurementDao(): EcgMeasurementDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ecg_mobile.db",
                ).build().also { INSTANCE = it }
            }
        }
    }
}
