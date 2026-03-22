package com.huafeng.beaconzone

import org.altbeacon.beacon.Beacon

data class ZoneScanResult(
    val cache: Map<String, BeaconRow>,
    val aliveRows: List<BeaconRow>,
    val currentZone: Zone?
)

private fun beaconKey(uuid: String, major: Int, minor: Int) = "$uuid:$major:$minor"

fun updateCacheAndGetAliveRows(
    rawBeacons: Collection<Beacon>,
    oldCache: Map<String, BeaconRow>,
    nowMs: Long,
    ttlMs: Long = 3000L,
    emaAlpha: Double = 0.7,
    // 添加缺少的参数
    kalmanMap: Any? = null,
    beaconKey: Any? = null
): Pair<Map<String, BeaconRow>, List<BeaconRow>> {

    val newCache = oldCache.toMutableMap()

    rawBeacons.forEach { b ->
        val uuid = b.id1?.toString() ?: return@forEach
        val major = b.id2?.toInt() ?: return@forEach
        val minor = b.id3?.toInt() ?: return@forEach
        val key = beaconKey(uuid, major, minor)

        val newRssi = b.rssi
        val old = newCache[key]

        val smooth = if (old == null) newRssi
        else (emaAlpha * old.rssi + (1.0 - emaAlpha) * newRssi).toInt()

        newCache[key] = BeaconRow(
            uuid = uuid,
            major = major,
            minor = minor,
            rssi = smooth,
            lastSeenMs = nowMs
        )
    }

    val aliveRows = newCache.values
        .filter { nowMs - it.lastSeenMs <= ttlMs }
        .sortedBy { beaconKey(it.uuid, it.major, it.minor) }

    return newCache to aliveRows
}

fun detectCurrentZone(
    aliveRows: List<BeaconRow>,
    zones: List<Zone>
): Zone? {
    var bestZone: Zone? = null
    var bestRssi: Int? = null

    aliveRows.forEach { r ->
        val z = zones.firstOrNull {
            it.uuid.equals(r.uuid, ignoreCase = true) &&
                    it.major == r.major &&
                    it.minor == r.minor
        } ?: return@forEach

        if (bestRssi == null || r.rssi > bestRssi!!) {
            bestRssi = r.rssi
            bestZone = z
        }
    }
    return bestZone
}

fun resolveZoneFromRaw(
    rawBeacons: Collection<Beacon>,
    zones: List<Zone>,
    oldCache: Map<String, BeaconRow>,
    nowMs: Long = System.currentTimeMillis(),
    ttlMs: Long = 3000L,
    emaAlpha: Double = 0.7,
    // 添加缺少的参数，先用 Any? 占位保证编译通过
    kalmanMap: Any? = null,
    beaconKey: Any? = null
): ZoneScanResult {
    val (newCache, aliveRows) = updateCacheAndGetAliveRows(
        rawBeacons = rawBeacons,
        oldCache = oldCache,
        nowMs = nowMs,
        ttlMs = ttlMs,
        emaAlpha = emaAlpha,
        kalmanMap = kalmanMap,
        beaconKey = beaconKey
    )
    val currentZone = detectCurrentZone(aliveRows, zones)
    return ZoneScanResult(cache = newCache, aliveRows = aliveRows, currentZone = currentZone)
}
