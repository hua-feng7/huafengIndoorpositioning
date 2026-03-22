package com.huafeng.beaconzone

import android.content.Context

class AppSettingsStore(ctx: Context) {

    private val sp = ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    fun getSelectedMapId(): String? = sp.getString(KEY_SELECTED_MAP_ID, null)

    fun setSelectedMapId(mapId: String?) {
        sp.edit().apply {
            if (mapId.isNullOrBlank()) remove(KEY_SELECTED_MAP_ID) else putString(KEY_SELECTED_MAP_ID, mapId)
        }.apply()
    }

    fun getRouteAnimationMode(): RouteAnimationMode =
        sp.getString(KEY_ROUTE_ANIMATION_MODE, RouteAnimationMode.ELEGANT.name)
            ?.let { value -> RouteAnimationMode.entries.firstOrNull { it.name == value } }
            ?: RouteAnimationMode.ELEGANT

    fun setRouteAnimationMode(mode: RouteAnimationMode) {
        sp.edit().putString(KEY_ROUTE_ANIMATION_MODE, mode.name).apply()
    }

    fun getPositioningAlgorithm(): PositioningAlgorithm =
        sp.getString(KEY_POSITIONING_ALGORITHM, PositioningAlgorithm.ONE_NN.name)
            ?.let { value -> PositioningAlgorithm.entries.firstOrNull { it.name == value } }
            ?: PositioningAlgorithm.ONE_NN

    fun setPositioningAlgorithm(algorithm: PositioningAlgorithm) {
        sp.edit().putString(KEY_POSITIONING_ALGORITHM, algorithm.name).apply()
    }

    fun getSignalMode(): SignalMode =
        sp.getString(KEY_SIGNAL_MODE, SignalMode.BLE_ONLY.name)
            ?.let { value -> SignalMode.entries.firstOrNull { it.name == value } }
            ?: SignalMode.BLE_ONLY

    fun setSignalMode(mode: SignalMode) {
        sp.edit().putString(KEY_SIGNAL_MODE, mode.name).apply()
    }

    fun getNeighborCountInput(): String = sp.getString(KEY_NEIGHBOR_COUNT, "3") ?: "3"

    fun setNeighborCountInput(value: String) {
        sp.edit().putString(KEY_NEIGHBOR_COUNT, value).apply()
    }

    fun getStrongestBeaconCountInput(): String = sp.getString(KEY_STRONGEST_BEACON_COUNT, "3") ?: "3"

    fun setStrongestBeaconCountInput(value: String) {
        sp.edit().putString(KEY_STRONGEST_BEACON_COUNT, value).apply()
    }

    fun getWeightPowerInput(): String = sp.getString(KEY_WEIGHT_POWER, "1.0") ?: "1.0"

    fun setWeightPowerInput(value: String) {
        sp.edit().putString(KEY_WEIGHT_POWER, value).apply()
    }

    fun getSwitchMarginInput(): String = sp.getString(KEY_SWITCH_MARGIN, "0") ?: "0"

    fun setSwitchMarginInput(value: String) {
        sp.edit().putString(KEY_SWITCH_MARGIN, value).apply()
    }

    fun getEnableClusterPrefilter(): Boolean = sp.getBoolean(KEY_ENABLE_CLUSTER_PREFILTER, true)

    fun setEnableClusterPrefilter(value: Boolean) {
        sp.edit().putBoolean(KEY_ENABLE_CLUSTER_PREFILTER, value).apply()
    }

    fun getClusterCellSizeInput(): String = sp.getString(KEY_CLUSTER_CELL_SIZE, "160") ?: "160"

    fun setClusterCellSizeInput(value: String) {
        sp.edit().putString(KEY_CLUSTER_CELL_SIZE, value).apply()
    }

    fun getClusterCountInput(): String = sp.getString(KEY_CLUSTER_COUNT, "3") ?: "3"

    fun setClusterCountInput(value: String) {
        sp.edit().putString(KEY_CLUSTER_COUNT, value).apply()
    }

    fun getEnableTemporalSmoothing(): Boolean = sp.getBoolean(KEY_ENABLE_TEMPORAL_SMOOTHING, true)

    fun setEnableTemporalSmoothing(value: Boolean) {
        sp.edit().putBoolean(KEY_ENABLE_TEMPORAL_SMOOTHING, value).apply()
    }

    fun getSmoothingFactorInput(): String = sp.getString(KEY_SMOOTHING_FACTOR, "0.55") ?: "0.55"

    fun setSmoothingFactorInput(value: String) {
        sp.edit().putString(KEY_SMOOTHING_FACTOR, value).apply()
    }

    fun getShowPositioningCost(): Boolean = sp.getBoolean(KEY_SHOW_POSITIONING_COST, true)

    fun setShowPositioningCost(value: Boolean) {
        sp.edit().putBoolean(KEY_SHOW_POSITIONING_COST, value).apply()
    }

    fun getShowBeaconSummary(): Boolean = sp.getBoolean(KEY_SHOW_BEACON_SUMMARY, true)

    fun setShowBeaconSummary(value: Boolean) {
        sp.edit().putBoolean(KEY_SHOW_BEACON_SUMMARY, value).apply()
    }

    fun getMaxCollectionSeconds(): Int =
        sp.getInt(KEY_MAX_COLLECTION_SECONDS, 4).coerceIn(3, 15)

    fun setMaxCollectionSeconds(value: Int) {
        sp.edit().putInt(KEY_MAX_COLLECTION_SECONDS, value.coerceIn(3, 15)).apply()
    }

    fun getRequiredCollectionSamples(): Int =
        sp.getInt(KEY_REQUIRED_COLLECTION_SAMPLES, 3).coerceIn(1, 10)

    fun setRequiredCollectionSamples(value: Int) {
        sp.edit().putInt(KEY_REQUIRED_COLLECTION_SAMPLES, value.coerceIn(1, 10)).apply()
    }

    private companion object {
        const val KEY_SELECTED_MAP_ID = "selected_map_id"
        const val KEY_ROUTE_ANIMATION_MODE = "route_animation_mode"
        const val KEY_POSITIONING_ALGORITHM = "positioning_algorithm"
        const val KEY_SIGNAL_MODE = "signal_mode"
        const val KEY_NEIGHBOR_COUNT = "neighbor_count"
        const val KEY_STRONGEST_BEACON_COUNT = "strongest_beacon_count"
        const val KEY_WEIGHT_POWER = "weight_power"
        const val KEY_SWITCH_MARGIN = "switch_margin"
        const val KEY_ENABLE_CLUSTER_PREFILTER = "enable_cluster_prefilter"
        const val KEY_CLUSTER_CELL_SIZE = "cluster_cell_size"
        const val KEY_CLUSTER_COUNT = "cluster_count"
        const val KEY_ENABLE_TEMPORAL_SMOOTHING = "enable_temporal_smoothing"
        const val KEY_SMOOTHING_FACTOR = "smoothing_factor"
        const val KEY_SHOW_POSITIONING_COST = "show_positioning_cost"
        const val KEY_SHOW_BEACON_SUMMARY = "show_beacon_summary"
        const val KEY_MAX_COLLECTION_SECONDS = "max_collection_seconds"
        const val KEY_REQUIRED_COLLECTION_SAMPLES = "required_collection_samples"
    }
}
