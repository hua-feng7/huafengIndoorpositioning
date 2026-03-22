package com.huafeng.beaconzone

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FingerprintDao {

    // --- 指纹点操作 ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: FingerprintEntity): Long

    @Delete
    suspend fun delete(e: FingerprintEntity)

    /**
     * ✅ 实时监听所有指纹点（不分地图）
     */
    @Query("SELECT * FROM fingerprints ORDER BY id DESC")
    fun observeAllPoints(): Flow<List<FingerprintEntity>>

    /**
     * 实时监听某个地图下的指纹点
     */
    @Query("SELECT * FROM fingerprints WHERE mapId = :mapId ORDER BY id DESC")
    fun observeByMap(mapId: String): Flow<List<FingerprintEntity>>

    /**
     * 获取某个地图下的所有指纹点（导出用）
     */
    @Query("SELECT * FROM fingerprints WHERE mapId = :mapId ORDER BY id DESC")
    suspend fun getByMap(mapId: String): List<FingerprintEntity>

    @Query("SELECT * FROM fingerprints ORDER BY id DESC")
    suspend fun getAll(): List<FingerprintEntity>

    @Query("DELETE FROM fingerprints")
    suspend fun clearAll()

    /**
     * ✅ 删除特定地图下的所有指纹点
     */
    @Query("DELETE FROM fingerprints WHERE mapId = :mapId")
    suspend fun deletePointsByMap(mapId: String)


    // --- 地图管理操作 ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMap(m: MapEntity)

    @Delete
    suspend fun deleteMap(m: MapEntity)

    @Query("SELECT * FROM maps ORDER BY createdAtMs ASC")
    fun observeAllMaps(): Flow<List<MapEntity>>

    @Query("SELECT * FROM maps WHERE id = :mapId LIMIT 1")
    suspend fun getMapById(mapId: String): MapEntity?
}
