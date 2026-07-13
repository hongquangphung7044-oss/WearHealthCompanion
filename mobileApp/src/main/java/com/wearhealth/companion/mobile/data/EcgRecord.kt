package com.wearhealth.companion.mobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ecg_records")
data class EcgRecord(
    @androidx.room.PrimaryKey val recordId: String,
    val timestamp: Long,
    val diagnosisCsv: String,
    val heartRate: Int,
    val minHeartRate: Int,
    val maxHeartRate: Int,
    val signalQuality: Double,
    val abnormal: Boolean,
    val qrs: Int,
    val pr: Int,
    val qt: Int,
    val qtc: Int,
    val pac: Int,
    val pvc: Int,
    val sampleRate: Int,
    val sampleCount: Int,
    val rawFileName: String,
)

@Dao
interface EcgRecordDao {
    @Query("SELECT * FROM ecg_records ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<EcgRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: EcgRecord)
}

@Database(entities = [EcgRecord::class], version = 1, exportSchema = false)
abstract class EcgDatabase : RoomDatabase() {
    abstract fun ecgDao(): EcgRecordDao
}
