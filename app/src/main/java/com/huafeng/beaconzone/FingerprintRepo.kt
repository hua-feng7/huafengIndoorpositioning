package com.huafeng.beaconzone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

data class MergedFingerprintGroup(
    val groupKey: String,
    val sourceIds: List<Long>,
    val mapId: String,
    val xPx: Float,
    val yPx: Float,
    val createdAtMs: Long,
    val mergedRssiMap: Map<String, Double>
)

class FingerprintRepo(
    private val dao: FingerprintDao
) {

    suspend fun initDefaultMap() {
        withContext(Dispatchers.IO) {
            dao.insertMap(MapEntity(id = "kunlun", name = "kunlun"))
        }
    }

    fun observeAllMaps(): Flow<List<MapEntity>> = dao.observeAllMaps()

    suspend fun insertMap(name: String, uri: String) {
        withContext(Dispatchers.IO) {
            dao.insertMap(MapEntity(id = name, name = name, imageUri = uri))
        }
    }

    /**
     * ✅ 删除地图及其所有关联的指纹点
     */
    suspend fun deleteMap(map: MapEntity) {
        withContext(Dispatchers.IO) {
            dao.deletePointsByMap(map.id) // 先删点
            dao.deleteMap(map) // 再删图
        }
    }

    fun observeByMap(mapId: String): Flow<List<FingerprintEntity>> =
        dao.observeByMap(mapId)

    @Suppress("FunctionName")
    fun _observeAllPointsRaw(): Flow<List<FingerprintEntity>> = dao.observeAllPoints()

    suspend fun getByMap(mapId: String): List<FingerprintEntity> =
        withContext(Dispatchers.IO) {
            dao.getByMap(mapId)
        }

    suspend fun insertPoint(
        mapId: String,
        xPx: Float,
        yPx: Float,
        rssiMap: Map<String, Double>
    ) {
        val json = JSONObject()
        for ((k, v) in rssiMap) json.put(k, v)

        withContext(Dispatchers.IO) {
            dao.insert(
                FingerprintEntity(
                    mapId = mapId,
                    xPx = xPx,
                    yPx = yPx,
                    rssiJson = json.toString()
                )
            )
        }
    }

    suspend fun deletePoint(e: FingerprintEntity) =
        withContext(Dispatchers.IO) {
            dao.delete(e)
        }

    fun parseRssiMap(e: FingerprintEntity): Map<String, Double> {
        val obj = JSONObject(e.rssiJson)
        val it = obj.keys()
        val out = mutableMapOf<String, Double>()
        while (it.hasNext()) {
            val k = it.next()
            out[k] = obj.optDouble(k)
        }
        return out
    }

    suspend fun getAll(): List<FingerprintEntity> =
        withContext(Dispatchers.IO) {
            dao.getAll()
        }

    suspend fun clearAll() =
        withContext(Dispatchers.IO) {
            dao.clearAll()
        }

    fun mergeByCoordinate(points: List<FingerprintEntity>): List<MergedFingerprintGroup> =
        points
            .groupBy { entity ->
                val xKey = (entity.xPx * 10f).roundToInt()
                val yKey = (entity.yPx * 10f).roundToInt()
                "${entity.mapId}:$xKey:$yKey"
            }
            .map { (key, grouped) ->
                val rssis = grouped.map(::parseRssiMap)
                val mergedKeys = rssis.flatMap { it.keys }.toSet()
                val mergedRssiMap = mergedKeys.associateWith { beaconKey ->
                    robustAverage(rssis.mapNotNull { it[beaconKey] })
                }
                val anchor = grouped.first()
                MergedFingerprintGroup(
                    groupKey = key,
                    sourceIds = grouped.map { it.id },
                    mapId = anchor.mapId,
                    xPx = robustAverage(grouped.map { it.xPx.toDouble() }).toFloat(),
                    yPx = robustAverage(grouped.map { it.yPx.toDouble() }).toFloat(),
                    createdAtMs = grouped.maxOf { it.createdAtMs },
                    mergedRssiMap = mergedRssiMap
                )
            }
            .sortedByDescending { it.createdAtMs }

    private fun robustAverage(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        if (values.size <= 2) return values.average()

        val median = values.median()
        val deviations = values.map { abs(it - median) }
        val mad = deviations.median()

        val filtered = if (mad < 1e-6) {
            values.filter { abs(it - median) <= 2.0 }
        } else {
            val threshold = mad * 2.5
            values.filter { abs(it - median) <= threshold }
        }

        return (if (filtered.isNotEmpty()) filtered else values).average()
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }
}
