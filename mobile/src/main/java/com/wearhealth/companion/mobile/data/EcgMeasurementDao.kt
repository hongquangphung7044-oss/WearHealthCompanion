package com.wearhealth.companion.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ECG 测量记录的数据访问对象
 */
@Dao
interface EcgMeasurementDao {

    /** 插入一条测量记录（REPLACE 策略：相同主键时覆盖） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: EcgMeasurementEntity): Long

    /** 获取全部测量记录，按时间倒序 */
    @Query("SELECT * FROM ecg_measurements ORDER BY timestamp DESC")
    fun getAll(): Flow<List<EcgMeasurementEntity>>

    /** 按主键查询单条记录 */
    @Query("SELECT * FROM ecg_measurements WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): EcgMeasurementEntity?

    /** 按主键删除单条记录 */
    @Query("DELETE FROM ecg_measurements WHERE id = :id")
    suspend fun delete(id: Long)

    /** 删除全部记录 */
    @Query("DELETE FROM ecg_measurements")
    suspend fun deleteAll()
}
