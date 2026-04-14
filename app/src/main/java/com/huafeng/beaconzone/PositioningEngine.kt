package com.huafeng.beaconzone

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
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
    val enableDensityCompensationWknn: Boolean = true,
    val densityNeighborCount: Int = 5,
    val densityCompensationStrength: Double = 1.0,
    val switchMargin: Double = 0.0,
    val enableClusterPrefilter: Boolean = true,
    val clusterCellSizePx: Float = 160f,
    val clusterCount: Int = 3,
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
    private enum class SignalFamily {
        BLE,
        WIFI
    }

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
        currentFingerprintId: Long? = null
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
        ).preserveCurrentFingerprint(
            allFingerprints = fingerprints,
            currentFingerprintId = currentFingerprintId
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
                allFingerprints = fingerprints,
                neighborCount = config.neighborCount,
                weighted = config.algorithm != PositioningAlgorithm.KNN,
                weightPower = config.weightPower,
                enableDensityCompensation = config.algorithm == PositioningAlgorithm.WKNN &&
                    config.enableDensityCompensationWknn,
                densityNeighborCount = config.densityNeighborCount,
                densityCompensationStrength = config.densityCompensationStrength
            )
        } ?: return null

        return applyHysteresis(
            candidate = candidate,
            scored = scored,
            currentFingerprintId = currentFingerprintId,
            switchMargin = config.switchMargin
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
        allFingerprints: List<FingerprintSample>,
        neighborCount: Int,
        weighted: Boolean,
        weightPower: Double,
        enableDensityCompensation: Boolean,
        densityNeighborCount: Int,
        densityCompensationStrength: Double
    ): PositionEstimate? {
        if (scored.isEmpty()) return null

        val k = neighborCount.coerceAtLeast(1).coerceAtMost(scored.size)
        val nearest = scored.take(k)
        val densityFactors = if (weighted && enableDensityCompensation) {
            buildDensityCompensationMap(
                fingerprints = allFingerprints,
                densityNeighborCount = densityNeighborCount,
                densityCompensationStrength = densityCompensationStrength
            )
        } else {
            emptyMap()
        }
        var weightedX = 0.0
        var weightedY = 0.0
        var totalWeight = 0.0

        nearest.forEach { (sample, distance) ->
            val baseWeight = if (weighted) {
                1.0 / Math.pow(distance + 1e-6, weightPower.coerceAtLeast(0.1))
            } else {
                1.0
            }
            val densityFactor = densityFactors[sample.id] ?: 1.0
            val weight = baseWeight * densityFactor
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

    private fun buildDensityCompensationMap(
        fingerprints: List<FingerprintSample>,
        densityNeighborCount: Int,
        densityCompensationStrength: Double
    ): Map<Long, Double> {
        if (fingerprints.size <= 2) return emptyMap()

        val neighborCount = densityNeighborCount
            .coerceAtLeast(1)
            .coerceAtMost((fingerprints.size - 1).coerceAtLeast(1))
        val strength = densityCompensationStrength.coerceIn(0.1, 3.0)

        val localSpacing = fingerprints.associate { sample ->
            val meanDistance = fingerprints.asSequence()
                .filter { it.id != sample.id }
                .map { other -> euclideanDistance(sample.xPx, sample.yPx, other.xPx, other.yPx) }
                .sorted()
                .take(neighborCount)
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.average()
                ?: 0.0
            sample.id to meanDistance
        }

        val globalMeanSpacing = localSpacing.values
            .filter { it.isFinite() && it > 0.0 }
            .average()
            .takeIf { it.isFinite() && it > 0.0 }
            ?: return emptyMap()

        return localSpacing.mapValues { (_, spacing) ->
            val normalizedSpacing = (spacing / globalMeanSpacing)
                .coerceIn(0.6, 1.8)
            normalizedSpacing.pow(strength)
        }
    }

    private fun List<FingerprintSample>.preserveCurrentFingerprint(
        allFingerprints: List<FingerprintSample>,
        currentFingerprintId: Long?
    ): List<FingerprintSample> {
        if (currentFingerprintId == null || any { it.id == currentFingerprintId }) return this
        val current = allFingerprints.firstOrNull { it.id == currentFingerprintId } ?: return this
        return this + current
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
        val activeFamilies = a.keys
            .mapNotNull(::detectSignalFamily)
            .toSet()

        if (activeFamilies.isEmpty()) return Double.MAX_VALUE

        val familyScores = activeFamilies.mapNotNull { family ->
            calculateDistanceForFamily(
                a = a,
                b = b,
                family = family,
                missingPenalty = missingPenalty
            )
        }

        return familyScores.takeIf { it.isNotEmpty() }?.average() ?: Double.MAX_VALUE
    }

    private fun calculateDistanceForFamily(
        a: Map<String, Double>,
        b: Map<String, Double>,
        family: SignalFamily,
        missingPenalty: Double
    ): Double? {
        val familyA = a.filterKeys { detectSignalFamily(it) == family }
        val familyB = b.filterKeys { detectSignalFamily(it) == family }
        val keys = (familyA.keys + familyB.keys).toSet()
        if (keys.isEmpty()) return null

        return when (family) {
            SignalFamily.BLE -> calculateBleDistance(
                a = familyA,
                b = familyB,
                missingPenalty = missingPenalty
            )

            SignalFamily.WIFI -> calculateGenericDistance(
                keys = keys,
                a = familyA,
                b = familyB,
                missingPenalty = missingPenalty
            )
        }
    }

    private fun calculateBleDistance(
        a: Map<String, Double>,
        b: Map<String, Double>,
        missingPenalty: Double
    ): Double {
        val keys = (a.keys + b.keys).toSet()
        if (keys.isEmpty()) return Double.MAX_VALUE

        val commonKeys = a.keys.intersect(b.keys)
        if (commonKeys.isEmpty()) {
            return missingPenalty * 1.8
        }

        var weightedSum = 0.0
        var totalWeight = 0.0
        commonKeys.forEach { key ->
            val av = a.getValue(key)
            val bv = b.getValue(key)
            val delta = av - bv
            val weight = bleSignalWeight(maxOf(av, bv))
            weightedSum += delta * delta * weight
            totalWeight += weight
        }

        val overlapRatio = commonKeys.size.toDouble() / keys.size
        val weightedRmse = if (totalWeight > 0.0) {
            sqrt(weightedSum / totalWeight)
        } else {
            missingPenalty
        }
        val missingComponent = missingPenalty * (1.0 - overlapRatio)

        return weightedRmse + missingComponent
    }

    private fun calculateGenericDistance(
        keys: Set<String>,
        a: Map<String, Double>,
        b: Map<String, Double>,
        missingPenalty: Double
    ): Double {
        var sum = 0.0
        for (key in keys) {
            val av = a[key]
            val bv = b[key]
            val delta = if (av != null && bv != null) av - bv else missingPenalty
            sum += delta * delta
        }
        return sqrt(sum / keys.size)
    }

    private fun bleSignalWeight(rssi: Double): Double {
        val normalized = (rssi + 100.0) / 40.0
        return normalized.coerceIn(0.35, 1.5)
    }

    private fun detectSignalFamily(key: String): SignalFamily? = when {
        key.startsWith("ble:") -> SignalFamily.BLE
        key.startsWith("wifi:") -> SignalFamily.WIFI
        else -> null
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

    private fun euclideanDistance(x1: Float, y1: Float, x2: Float, y2: Float): Double {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt((dx * dx + dy * dy).toDouble())
    }
}
