package com.huafeng.beaconzone

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

enum class PositioningAlgorithm(val label: String) {
    ONE_NN("1NN"),
    KNN("KNN"),
    WKNN("WKNN"),
    KNN_STRONGEST_BEACONS("KNN+最强N信标")
}

data class PositioningConfig(
    val algorithm: PositioningAlgorithm = PositioningAlgorithm.ONE_NN,
    val neighborCount: Int = 3,
    val strongestBeaconCount: Int = 3,
    val weightPower: Double = 1.0,
    val switchMargin: Double = 0.0,
    val enableClusterPrefilter: Boolean = true,
    val clusterCellSizePx: Float = 160f,
    val clusterCount: Int = 3,
    val enableTemporalSmoothing: Boolean = true,
    val smoothingFactor: Double = 0.55,
    val missingPenalty: Double = 12.0
)

data class FingerprintSample(
    val id: Long,
    val xPx: Float,
    val yPx: Float,
    val rssiMap: Map<String, Double>
)

data class PositionEstimate(
    val xPx: Float,
    val yPx: Float,
    val fingerprintId: Long,
    val distance: Double
)

object PositioningEngine {
    fun buildBeaconKey(uuid: String, major: Int, minor: Int): String = "ble:$uuid:$major:$minor"
    fun buildWifiKey(bssid: String): String = "wifi:$bssid"

    fun buildScanVector(rows: List<BeaconRow>): Map<String, Double> =
        rows.associate { row ->
            row.vectorKey to row.rssi.toDouble()
        }

    fun estimatePosition(
        liveRssi: Map<String, Double>,
        fingerprints: List<FingerprintSample>,
        config: PositioningConfig = PositioningConfig(),
        currentFingerprintId: Long? = null,
        previousEstimate: PositionEstimate? = null
    ): PositionEstimate? {
        if (liveRssi.isEmpty() || fingerprints.isEmpty()) return null

        val effectiveLiveRssi = when (config.algorithm) {
            PositioningAlgorithm.KNN_STRONGEST_BEACONS ->
                keepStrongestBeacons(liveRssi, config.strongestBeaconCount)
            else -> liveRssi
        }
        if (effectiveLiveRssi.isEmpty()) return null

        val candidateFingerprints = prefilterFingerprintsByCluster(
            liveRssi = effectiveLiveRssi,
            fingerprints = fingerprints,
            config = config
        )

        val scored = candidateFingerprints.map { sample ->
            val effectiveFingerprint = when (config.algorithm) {
                PositioningAlgorithm.KNN_STRONGEST_BEACONS ->
                    sample.rssiMap.filterKeys { it in effectiveLiveRssi.keys }
                else -> sample.rssiMap
            }
            sample to calculateDistance(
                a = effectiveLiveRssi,
                b = effectiveFingerprint,
                missingPenalty = config.missingPenalty
            )
        }.sortedBy { it.second }

        val candidate = when (config.algorithm) {
            PositioningAlgorithm.ONE_NN -> scored.firstOrNull()?.let { (sample, distance) ->
                PositionEstimate(
                    xPx = sample.xPx,
                    yPx = sample.yPx,
                    fingerprintId = sample.id,
                    distance = distance
                )
            }

            PositioningAlgorithm.KNN,
            PositioningAlgorithm.WKNN,
            PositioningAlgorithm.KNN_STRONGEST_BEACONS -> estimateByKnn(
                scored = scored,
                neighborCount = config.neighborCount,
                weighted = config.algorithm != PositioningAlgorithm.KNN,
                weightPower = config.weightPower
            )
        } ?: return null

        val hysteresisApplied = applyHysteresis(
            candidate = candidate,
            scored = scored,
            currentFingerprintId = currentFingerprintId,
            switchMargin = config.switchMargin
        )

        return applyTemporalSmoothing(
            candidate = hysteresisApplied,
            previousEstimate = previousEstimate,
            enabled = config.enableTemporalSmoothing,
            smoothingFactor = config.smoothingFactor
        )
    }

    private fun prefilterFingerprintsByCluster(
        liveRssi: Map<String, Double>,
        fingerprints: List<FingerprintSample>,
        config: PositioningConfig
    ): List<FingerprintSample> {
        if (!config.enableClusterPrefilter || fingerprints.size <= 3) return fingerprints

        val cellSize = config.clusterCellSizePx.takeIf { it.isFinite() && it > 1f } ?: return fingerprints
        val keepClusterCount = config.clusterCount.coerceAtLeast(1)

        val clusters = fingerprints.groupBy { sample ->
            val gridX = floor(sample.xPx / cellSize).toInt()
            val gridY = floor(sample.yPx / cellSize).toInt()
            gridX to gridY
        }
        if (clusters.size <= 1) return fingerprints

        val rankedClusters = clusters.entries.map { (clusterKey, samples) ->
            val mergedSignals = mergeRssiMaps(samples.map { it.rssiMap })
            clusterKey to calculateDistance(
                a = liveRssi,
                b = mergedSignals,
                missingPenalty = config.missingPenalty
            )
        }.sortedBy { it.second }

        val selectedKeys = rankedClusters
            .take(keepClusterCount.coerceAtMost(rankedClusters.size))
            .map { it.first }
            .toSet()

        return fingerprints.filter { sample ->
            val gridX = floor(sample.xPx / cellSize).toInt()
            val gridY = floor(sample.yPx / cellSize).toInt()
            (gridX to gridY) in selectedKeys
        }.ifEmpty { fingerprints }
    }

    private fun estimateByKnn(
        scored: List<Pair<FingerprintSample, Double>>,
        neighborCount: Int,
        weighted: Boolean,
        weightPower: Double
    ): PositionEstimate? {
        if (scored.isEmpty()) return null

        val k = neighborCount.coerceAtLeast(1).coerceAtMost(scored.size)
        val nearest = scored.take(k)
        var weightedX = 0.0
        var weightedY = 0.0
        var totalWeight = 0.0

        nearest.forEach { (sample, distance) ->
            val weight = if (weighted) {
                1.0 / Math.pow(distance + 1e-6, weightPower.coerceAtLeast(0.1))
            } else {
                1.0
            }
            weightedX += sample.xPx * weight
            weightedY += sample.yPx * weight
            totalWeight += weight
        }

        if (totalWeight <= 0.0) return null

        val anchor = nearest.first()
        return PositionEstimate(
            xPx = (weightedX / totalWeight).toFloat(),
            yPx = (weightedY / totalWeight).toFloat(),
            fingerprintId = anchor.first.id,
            distance = anchor.second
        )
    }

    private fun applyHysteresis(
        candidate: PositionEstimate,
        scored: List<Pair<FingerprintSample, Double>>,
        currentFingerprintId: Long?,
        switchMargin: Double
    ): PositionEstimate {
        val current = currentFingerprintId?.let { id ->
            scored.firstOrNull { it.first.id == id }
        } ?: return candidate

        if (current.first.id == candidate.fingerprintId) return candidate
        return if (candidate.distance < current.second - switchMargin) {
            candidate
        } else {
            PositionEstimate(
                xPx = current.first.xPx,
                yPx = current.first.yPx,
                fingerprintId = current.first.id,
                distance = current.second
            )
        }
    }

    private fun applyTemporalSmoothing(
        candidate: PositionEstimate,
        previousEstimate: PositionEstimate?,
        enabled: Boolean,
        smoothingFactor: Double
    ): PositionEstimate {
        if (!enabled || previousEstimate == null) return candidate

        val alpha = smoothingFactor.coerceIn(0.0, 1.0)
        if (alpha >= 1.0) return candidate

        return candidate.copy(
            xPx = (previousEstimate.xPx * (1 - alpha) + candidate.xPx * alpha).toFloat(),
            yPx = (previousEstimate.yPx * (1 - alpha) + candidate.yPx * alpha).toFloat()
        )
    }

    private fun keepStrongestBeacons(
        liveRssi: Map<String, Double>,
        strongestBeaconCount: Int
    ): Map<String, Double> {
        val count = strongestBeaconCount.coerceAtLeast(1)
        return liveRssi.entries
            .sortedBy { abs(it.value) }
            .take(count)
            .associate { it.key to it.value }
    }

    private fun calculateDistance(
        a: Map<String, Double>,
        b: Map<String, Double>,
        missingPenalty: Double
    ): Double {
        val keys = (a.keys + b.keys).toSet()
        var sum = 0.0
        for (key in keys) {
            val av = a[key]
            val bv = b[key]
            val delta = if (av != null && bv != null) av - bv else missingPenalty
            sum += delta * delta
        }
        return sqrt(sum)
    }

    private fun mergeRssiMaps(rssiMaps: List<Map<String, Double>>): Map<String, Double> {
        if (rssiMaps.isEmpty()) return emptyMap()
        val valuesByKey = linkedMapOf<String, MutableList<Double>>()
        rssiMaps.forEach { map ->
            map.forEach { (key, value) ->
                valuesByKey.getOrPut(key) { mutableListOf() }.add(value)
            }
        }
        return valuesByKey.mapValues { (_, values) -> values.average() }
    }
}
