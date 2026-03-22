package com.huafeng.beaconzone

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个指纹点（地图上的一个蓝点）
 * - mapId: 属于哪张地图
 * - xPx, yPx：地图图片上的像素坐标
 * - rssiJson：{ "uuid:major:minor": rssi, ... } 的 JSON 字符串
 */
@Entity(tableName = "fingerprints")
data class FingerprintEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val mapId: String, // ✅ 新增：用于数据隔离

    val xPx: Float,
    val yPx: Float,

    val rssiJson: String,

    val createdAtMs: Long = System.currentTimeMillis()
)

/**
 * ✅ 新增：地图实体类
 */
@Entity(tableName = "maps")
data class MapEntity(
    @PrimaryKey
    val id: String, // 名字即 ID 或随机 UUID
    val name: String,
    val imageUri: String? = null, // 图片的 URI 路径，null 则代表使用默认图
    val createdAtMs: Long = System.currentTimeMillis()
)
