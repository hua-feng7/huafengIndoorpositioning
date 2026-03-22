package com.huafeng.beaconzone

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.net.wifi.WifiManager
import android.net.wifi.ScanResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import androidx.lifecycle.lifecycleScope
import com.huafeng.beaconzone.ui.theme.BeaconZoneTheme
import kotlinx.coroutines.launch
import org.altbeacon.beacon.*
import java.io.OutputStreamWriter
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
// -------------------- UI 展示用（平滑后的） --------------------
enum class SignalSource(val label: String) {
    BLE("BLE"),
    WIFI("WiFi")
}

enum class SignalMode(val label: String) {
    BLE_ONLY("仅BLE"),
    WIFI_ONLY("仅WIFI"),
    BLE_WIFI("BLE+WIFI")
}

data class BeaconRow(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val source: SignalSource = SignalSource.BLE,
    val displayName: String = uuid,
    val displayDetail: String = "$major/$minor"
)

val BeaconRow.vectorKey: String
    get() = when (source) {
        SignalSource.BLE -> PositioningEngine.buildBeaconKey(uuid.lowercase(), major, minor)
        SignalSource.WIFI -> PositioningEngine.buildWifiKey(uuid.lowercase())
    }

enum class RouteAnimationMode {
    SIMPLE,
    ELEGANT
}

enum class MainTab(val label: String) {
    HOME("首页"),
    MAP("地图定位"),
    ZONE("区域"),
    SETTINGS("设置")
}

private enum class DetailScreen {
    NONE,
    ZONE_LIST,
    ZONE_EDIT,
    FP_MANAGER
}

class MainActivity : ComponentActivity() {

    // ✅ Compose 可观察状态：scan页 & map页都会实时刷新
    private var bleRowsState by mutableStateOf<List<BeaconRow>>(emptyList())
    private var wifiRowsState by mutableStateOf<List<BeaconRow>>(emptyList())

    private val beaconManager by lazy { BeaconManager.getInstanceForApplication(this) }
    private val region = Region("all-beacons", null, null, null)
    private val wifiManager by lazy {
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    private var isRanging = false
    private var isWifiScanning = false

    // zones 仍然用于“区域判断”功能（扫描页显示当前区域）
    @Volatile private var latestZones: List<Zone> = emptyList()

    // ====== 稳定关键：缓存 + TTL + EMA ======
    private val bleCache = mutableMapOf<String, BeaconRow>()
    private val wifiCache = mutableMapOf<String, BeaconRow>()
    private val TTL_MS = 3000L
    private val EMA_ALPHA = 0.7
    private var wifiScanJob: Job? = null
    private var wifiReceiverRegistered = false
    // ====== Kalman：每个 beacon 一个滤波器 ======
    private class RssiKalman(
        private val Q: Double = 0.2,   // 过程噪声：越小越“相信历史”
        private val R: Double = 9.0    // 测量噪声：越大越“抗抖”
    ) {
        private var xHat = 0.0
        private var p = 10.0
        private var inited = false

        fun update(z: Double): Double {
            if (!inited) {
                xHat = z
                inited = true
                return xHat
            }

            // predict
            p += Q

            // update
            val k = p / (p + R)
            xHat = xHat + k * (z - xHat)
            p = (1 - k) * p

            return xHat
        }
    }

    private val kalmanMap = mutableMapOf<String, RssiKalman>()

    private fun beaconKey(uuid: String, major: Int, minor: Int): String =
        "${uuid.lowercase()}:$major:$minor"

    private fun wifiKey(bssid: String): String = bssid.lowercase()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionStateVersion++
        }

    private var permissionStateVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BeaconZoneApp()
        }
    }

    // -------------------- 权限 --------------------
    private fun hasBlePerms(): Boolean {
        val locOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PERMISSION_GRANTED

        val bleOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanOk = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PERMISSION_GRANTED
            val connOk = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PERMISSION_GRANTED
            scanOk && connOk
        } else true

        return locOk && bleOk
    }

    private fun requestBlePerms() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasLocationPerm(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PERMISSION_GRANTED

    private fun hasSignalPerms(mode: SignalMode): Boolean = when (mode) {
        SignalMode.BLE_ONLY,
        SignalMode.BLE_WIFI -> hasBlePerms()
        SignalMode.WIFI_ONLY -> hasLocationPerm()
    }

    private fun requestPermsForMode(mode: SignalMode) {
        when (mode) {
            SignalMode.BLE_ONLY,
            SignalMode.BLE_WIFI -> requestBlePerms()
            SignalMode.WIFI_ONLY -> permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    // -------------------- 扫描控制 --------------------
    private fun startScan() {
        startBleScan()
        startWifiScan()
    }

    private fun stopScan() {
        stopBleScan()
        stopWifiScan()
    }

    private fun startBleScan() {
        if (isRanging) return
        isRanging = true
        beaconManager.addRangeNotifier(rangeNotifier)
        beaconManager.startRangingBeacons(region)
    }

    private fun stopBleScan() {
        if (!isRanging) return
        isRanging = false
        beaconManager.stopRangingBeacons(region)
        beaconManager.removeRangeNotifier(rangeNotifier)
        bleCache.clear()
        kalmanMap.clear()
        runOnUiThread { bleRowsState = emptyList() }
    }

    private fun startWifiScan() {
        if (isWifiScanning) return
        isWifiScanning = true
        registerWifiReceiverIfNeeded()
        refreshWifiRows()
        wifiScanJob = lifecycleScope.launch {
            while (isWifiScanning) {
                try {
                    wifiManager.startScan()
                } catch (_: SecurityException) {
                } catch (_: Exception) {
                }
                refreshWifiRows()
                delay(2500)
            }
        }
    }

    private fun stopWifiScan() {
        if (!isWifiScanning) return
        isWifiScanning = false
        wifiScanJob?.cancel()
        wifiScanJob = null
        if (wifiReceiverRegistered) {
            unregisterReceiver(wifiScanReceiver)
            wifiReceiverRegistered = false
        }
        wifiCache.clear()
        runOnUiThread { wifiRowsState = emptyList() }
    }

    private fun registerWifiReceiverIfNeeded() {
        if (wifiReceiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, filter)
        }
        wifiReceiverRegistered = true
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshWifiRows()
        }
    }

    private fun refreshWifiRows() {
        val now = System.currentTimeMillis()
        val results = try {
            wifiManager.scanResults.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        results.forEach { result ->
            val bssid = result.BSSID?.lowercase().orEmpty()
            if (bssid.isBlank()) return@forEach
            val key = wifiKey(bssid)
            val old = wifiCache[key]
            val smooth = if (old == null) {
                result.level
            } else {
                (EMA_ALPHA * old.rssi + (1.0 - EMA_ALPHA) * result.level).toInt()
            }
            wifiCache[key] = result.toSignalRow(now, smooth)
        }

        val aliveRows = wifiCache.values
            .filter { now - it.lastSeenMs <= TTL_MS }
            .sortedBy { it.uuid }
        runOnUiThread { wifiRowsState = aliveRows }
    }

    private fun ScanResult.toSignalRow(nowMs: Long, smoothRssi: Int): BeaconRow {
        val safeSsid = if (SSID.isNullOrBlank() || SSID == "<unknown ssid>") "隐藏WiFi" else SSID
        return BeaconRow(
            uuid = BSSID.lowercase(),
            major = 0,
            minor = 0,
            rssi = smoothRssi,
            lastSeenMs = nowMs,
            source = SignalSource.WIFI,
            displayName = safeSsid,
            displayDetail = BSSID.lowercase()
        )
    }

    // -------------------- 导出 CSV --------------------
    private fun exportFingerprintsCsv(
        fps: List<FingerprintEntity>,
        filename: String = "fingerprints.csv"
    ): String {
        val header = "id,mapId,xPx,yPx,createdAtMs,rssiJson\n"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = applicationContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "导出失败：无法创建文件"

            resolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { w ->
                    w.write(header)
                    for (fp in fps) {
                        val safeJson = fp.rssiJson.replace("\"", "\"\"")
                        w.write("${fp.id},${fp.mapId},${fp.xPx},${fp.yPx},${fp.createdAtMs},\"$safeJson\"\n")
                    }
                }
            } ?: return "导出失败：无法写入文件"

            "已保存到 Downloads/$filename"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()

            val file = java.io.File(dir, filename)
            java.io.FileOutputStream(file).use { os ->
                OutputStreamWriter(os).use { w ->
                    w.write(header)
                    for (fp in fps) {
                        val safeJson = fp.rssiJson.replace("\"", "\"\"")
                        w.write("${fp.id},${fp.mapId},${fp.xPx},${fp.yPx},${fp.createdAtMs},\"$safeJson\"\n")
                    }
                }
            }

            "已保存到 ${file.absolutePath}"
        }
    }

    // -------------------- AltBeacon 回调 --------------------
    private val rangeNotifier = RangeNotifier { beacons, _ ->
        val now = System.currentTimeMillis()
        val result = resolveZoneFromRaw(
            rawBeacons = beacons,
            zones = latestZones,
            oldCache = bleCache,
            nowMs = now,
            ttlMs = TTL_MS,
            emaAlpha = EMA_ALPHA,
            kalmanMap = kalmanMap,
            beaconKey = ::beaconKey
        )
        bleCache.clear()
        bleCache.putAll(result.cache)
        runOnUiThread { bleRowsState = result.aliveRows }
    }

    @Composable
    fun BeaconZoneApp() {
        BeaconZoneTheme {
            val db = remember { AppDb.get(applicationContext) }
            val fpRepo = remember { FingerprintRepo(db.fingerprintDao()) }
            val settingsStore = remember { AppSettingsStore(applicationContext) }
            val appVersionName = remember {
                try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
                } catch (_: Exception) {
                    "-"
                }
            }

            var editing by remember { mutableStateOf<Zone?>(null) }
            var selectedMapId by remember { mutableStateOf(settingsStore.getSelectedMapId()) }
            var routeAnimationMode by remember { mutableStateOf(settingsStore.getRouteAnimationMode()) }
            var selectedTab by remember { mutableStateOf(MainTab.HOME) }
            var detailScreen by remember { mutableStateOf(DetailScreen.NONE) }
            var developerModeEnabled by remember { mutableStateOf(false) }
            var signalMode by remember { mutableStateOf(settingsStore.getSignalMode()) }
            var positioningAlgorithm by remember { mutableStateOf(settingsStore.getPositioningAlgorithm()) }
            var neighborCountInput by remember { mutableStateOf(settingsStore.getNeighborCountInput()) }
            var strongestBeaconCountInput by remember { mutableStateOf(settingsStore.getStrongestBeaconCountInput()) }
            var weightPowerInput by remember { mutableStateOf(settingsStore.getWeightPowerInput()) }
            var switchMarginInput by remember { mutableStateOf(settingsStore.getSwitchMarginInput()) }
            var enableClusterPrefilter by remember { mutableStateOf(settingsStore.getEnableClusterPrefilter()) }
            var clusterCellSizeInput by remember { mutableStateOf(settingsStore.getClusterCellSizeInput()) }
            var clusterCountInput by remember { mutableStateOf(settingsStore.getClusterCountInput()) }
            var enableTemporalSmoothing by remember { mutableStateOf(settingsStore.getEnableTemporalSmoothing()) }
            var smoothingFactorInput by remember { mutableStateOf(settingsStore.getSmoothingFactorInput()) }
            var showPositioningCost by remember { mutableStateOf(settingsStore.getShowPositioningCost()) }
            var showBeaconSummary by remember { mutableStateOf(settingsStore.getShowBeaconSummary()) }
            var maxCollectionSeconds by remember { mutableIntStateOf(settingsStore.getMaxCollectionSeconds()) }
            var requiredCollectionSamples by remember { mutableIntStateOf(settingsStore.getRequiredCollectionSamples()) }

            val store = remember { ZoneStore(applicationContext) }
            val zones by store.zones.collectAsState(initial = emptyList())
            val currentPermissionStateVersion = permissionStateVersion
            val liveRows = remember(bleRowsState, wifiRowsState, signalMode) {
                when (signalMode) {
                    SignalMode.BLE_ONLY -> bleRowsState
                    SignalMode.WIFI_ONLY -> wifiRowsState
                    SignalMode.BLE_WIFI -> (bleRowsState + wifiRowsState)
                        .sortedWith(compareBy<BeaconRow> { it.source.ordinal }.thenByDescending { it.rssi })
                }
            }

            LaunchedEffect(zones) { latestZones = zones }
            
            // ✅ 初始化默认地图
            LaunchedEffect(Unit) { fpRepo.initDefaultMap() }
            LaunchedEffect(Unit) {
                if (!hasBlePerms()) {
                    requestBlePerms()
                }
            }
            LaunchedEffect(signalMode, currentPermissionStateVersion) {
                if (hasSignalPerms(signalMode)) {
                    startScan()
                } else {
                    stopScan()
                }
            }

            // ✅ 导出逻辑：现在需要接受 mapId
            val onDoExport = { mapId: String ->
                lifecycleScope.launch {
                    val msg = try {
                        val all = fpRepo.getByMap(mapId)
                        withContext(Dispatchers.IO) {
                            exportFingerprintsCsv(all, "fingerprints_${mapId}.csv")
                        }
                    } catch (e: Exception) {
                        "导出失败：${e.message ?: "未知错误"}"
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }

            val contentAnimationSpec = remember(routeAnimationMode) {
                if (routeAnimationMode == RouteAnimationMode.ELEGANT) 420 else 220
            }

            val onTabSelected: (MainTab) -> Unit = { tab ->
                selectedTab = tab
                detailScreen = DetailScreen.NONE
            }

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == MainTab.HOME,
                            onClick = { onTabSelected(MainTab.HOME) },
                            icon = { Icon(Icons.Default.Home, contentDescription = null) },
                            label = { Text("首页") },
                            alwaysShowLabel = false
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainTab.MAP,
                            onClick = { onTabSelected(MainTab.MAP) },
                            icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                            label = { Text("地图定位") },
                            alwaysShowLabel = false
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainTab.ZONE,
                            onClick = { onTabSelected(MainTab.ZONE) },
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            label = { Text("区域定位") },
                            alwaysShowLabel = false
                        )
                        NavigationBarItem(
                            selected = selectedTab == MainTab.SETTINGS,
                            onClick = { onTabSelected(MainTab.SETTINGS) },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text("设置") },
                            alwaysShowLabel = false
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    AnimatedContent(
                        targetState = detailScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(contentAnimationSpec)) togetherWith
                                    fadeOut(animationSpec = tween(contentAnimationSpec))
                        },
                        label = "detail-fade"
                    ) { currentDetail ->
                    when (currentDetail) {
                        DetailScreen.NONE -> {
                            AnimatedContent(
                                targetState = selectedTab,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(contentAnimationSpec)) togetherWith
                                            fadeOut(animationSpec = tween(contentAnimationSpec))
                                },
                                label = "tab-fade"
                            ) { currentTab ->
                                when (currentTab) {
                                    MainTab.HOME -> HomeScreen(
                                        zones = zones,
                                        rows = liveRows
                                    )

                                    MainTab.MAP -> {
                                        MapCollectScreen(
                                            repo = fpRepo,
                                            liveRows = liveRows,
                                            initialMapId = selectedMapId,
                                            onCurrentMapChange = {
                                                selectedMapId = it
                                                settingsStore.setSelectedMapId(it)
                                            },
                                            positioningAlgorithm = positioningAlgorithm,
                                            neighborCountInput = neighborCountInput,
                                            strongestBeaconCountInput = strongestBeaconCountInput,
                                            weightPowerInput = weightPowerInput,
                                            switchMarginInput = switchMarginInput,
                                            enableClusterPrefilter = enableClusterPrefilter,
                                            clusterCellSizeInput = clusterCellSizeInput,
                                            clusterCountInput = clusterCountInput,
                                            enableTemporalSmoothing = enableTemporalSmoothing,
                                            smoothingFactorInput = smoothingFactorInput,
                                            showPositioningCost = showPositioningCost,
                                            showBeaconSummary = showBeaconSummary,
                                            maxCollectionSeconds = maxCollectionSeconds,
                                            requiredCollectionSamples = requiredCollectionSamples,
                                            onBack = { onTabSelected(MainTab.ZONE) },
                                            onExport = { mapId -> onDoExport(mapId) },
                                            onManagePoints = { detailScreen = DetailScreen.FP_MANAGER }
                                        )
                                    }

                                    MainTab.ZONE -> ScannerScreen(
                                        zones = zones,
                                        rows = bleRowsState,
                                        hasPerm = { hasBlePerms() },
                                        requestPerm = { requestBlePerms() },
                                        onStart = { startScan() },
                                        onStop = { stopScan() },
                                        onOpenZoneConfig = { detailScreen = DetailScreen.ZONE_LIST },
                                        onSelectBeacon = { beacon ->
                                            editing = Zone(
                                                name = "",
                                                uuid = beacon.uuid,
                                                major = beacon.major,
                                                minor = beacon.minor
                                            )
                                            detailScreen = DetailScreen.ZONE_EDIT
                                        }
                                    )

                                    MainTab.SETTINGS -> SettingsScreen(
                                        appVersionName = appVersionName,
                                        routeAnimationMode = routeAnimationMode,
                                        onRouteAnimationModeChange = {
                                            routeAnimationMode = it
                                            settingsStore.setRouteAnimationMode(it)
                                        },
                                        developerModeEnabled = developerModeEnabled,
                                        onDeveloperModeEnabledChange = { developerModeEnabled = it },
                                        signalMode = signalMode,
                                        onSignalModeChange = {
                                            signalMode = it
                                            settingsStore.setSignalMode(it)
                                        },
                                        hasRequiredPermissions = hasSignalPerms(signalMode),
                                        permissionStateVersion = currentPermissionStateVersion,
                                        onRequestPermissions = { requestPermsForMode(signalMode) },
                                        positioningAlgorithm = positioningAlgorithm,
                                        onPositioningAlgorithmChange = { positioningAlgorithm = it },
                                        neighborCountInput = neighborCountInput,
                                        onNeighborCountChange = { neighborCountInput = it.filter(Char::isDigit) },
                                        strongestBeaconCountInput = strongestBeaconCountInput,
                                        onStrongestBeaconCountChange = { strongestBeaconCountInput = it.filter(Char::isDigit) },
                                        weightPowerInput = weightPowerInput,
                                        onWeightPowerChange = { weightPowerInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        switchMarginInput = switchMarginInput,
                                        onSwitchMarginChange = { switchMarginInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        enableClusterPrefilter = enableClusterPrefilter,
                                        onEnableClusterPrefilterChange = { enableClusterPrefilter = it },
                                        clusterCellSizeInput = clusterCellSizeInput,
                                        onClusterCellSizeChange = { clusterCellSizeInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        clusterCountInput = clusterCountInput,
                                        onClusterCountChange = { clusterCountInput = it.filter(Char::isDigit) },
                                        enableTemporalSmoothing = enableTemporalSmoothing,
                                        onEnableTemporalSmoothingChange = { enableTemporalSmoothing = it },
                                        smoothingFactorInput = smoothingFactorInput,
                                        onSmoothingFactorChange = { smoothingFactorInput = it.filter { ch -> ch.isDigit() || ch == '.' } },
                                        onSaveAlgorithmConfig = {
                                            settingsStore.setPositioningAlgorithm(positioningAlgorithm)
                                            settingsStore.setNeighborCountInput(neighborCountInput)
                                            settingsStore.setStrongestBeaconCountInput(strongestBeaconCountInput)
                                            settingsStore.setWeightPowerInput(weightPowerInput)
                                            settingsStore.setSwitchMarginInput(switchMarginInput)
                                            settingsStore.setEnableClusterPrefilter(enableClusterPrefilter)
                                            settingsStore.setClusterCellSizeInput(clusterCellSizeInput)
                                            settingsStore.setClusterCountInput(clusterCountInput)
                                            settingsStore.setEnableTemporalSmoothing(enableTemporalSmoothing)
                                            settingsStore.setSmoothingFactorInput(smoothingFactorInput)
                                            Toast.makeText(this@MainActivity, "算法设置已保存", Toast.LENGTH_SHORT).show()
                                        },
                                        showPositioningCost = showPositioningCost,
                                        onShowPositioningCostChange = {
                                            showPositioningCost = it
                                            settingsStore.setShowPositioningCost(it)
                                        },
                                        showBeaconSummary = showBeaconSummary,
                                        onShowBeaconSummaryChange = {
                                            showBeaconSummary = it
                                            settingsStore.setShowBeaconSummary(it)
                                        },
                                        maxCollectionSeconds = maxCollectionSeconds,
                                        onMaxCollectionSecondsChange = {
                                            maxCollectionSeconds = it
                                            settingsStore.setMaxCollectionSeconds(it)
                                        },
                                        requiredCollectionSamples = requiredCollectionSamples,
                                        onRequiredCollectionSamplesChange = {
                                            requiredCollectionSamples = it
                                            settingsStore.setRequiredCollectionSamples(it)
                                        },
                                        onOpenZoneConfig = { detailScreen = DetailScreen.ZONE_LIST },
                                        onOpenFpManager = { detailScreen = DetailScreen.FP_MANAGER }
                                    )
                                }
                            }
                        }

                        DetailScreen.ZONE_LIST -> ZoneListScreen(
                            zones = zones,
                            onAdd = { editing = null; detailScreen = DetailScreen.ZONE_EDIT },
                            onEdit = { z -> editing = z; detailScreen = DetailScreen.ZONE_EDIT },
                            onBack = { detailScreen = DetailScreen.NONE }
                        )

                        DetailScreen.ZONE_EDIT -> ZoneEditScreen(
                            title = if (editing == null) "新增区域" else if (editing!!.name.isEmpty()) "命名新区域" else "编辑区域",
                            initial = editing,
                            onSave = { z ->
                                store.upsert(z)
                                detailScreen = DetailScreen.ZONE_LIST
                            },
                            onDelete = if (editing == null || editing!!.name.isEmpty()) null else {
                                {
                                    store.delete(editing!!.id)
                                    detailScreen = DetailScreen.ZONE_LIST
                                }
                            },
                            onBack = { detailScreen = DetailScreen.ZONE_LIST }
                        )

                        DetailScreen.FP_MANAGER -> FpManagerScreen(
                            repo = fpRepo,
                            onBack = { detailScreen = DetailScreen.NONE }
                        )
                    }
                    }
                }
            }
        }
    }
}

// -------------------- 扫描页 --------------------
@Composable
private fun ScannerScreen(
    zones: List<Zone>,
    rows: List<BeaconRow>,
    hasPerm: () -> Boolean,
    requestPerm: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenZoneConfig: () -> Unit,
    onSelectBeacon: (BeaconRow) -> Unit
) {
    var scanning by remember { mutableStateOf(false) }

    val currentZoneName = remember(rows, zones) {
        val match = rows
            .mapNotNull { r ->
                val z = zones.firstOrNull {
                    it.uuid.equals(r.uuid, ignoreCase = true) &&
                            it.major == r.major &&
                            it.minor == r.minor
                }
                if (z != null) z to r.rssi else null
            }
            .maxByOrNull { it.second }
        match?.first?.name
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp)
        ) {
            Text(
                "区域定位",
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onOpenZoneConfig, modifier = Modifier.fillMaxWidth()) {
                    Text("区域配置")
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "当前区域：${currentZoneName ?: "未知（请先在“区域配置”里绑定）"}",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!hasPerm()) requestPerm()
                        else { onStart(); scanning = true }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Start") }

                Button(
                    onClick = { onStop(); scanning = false },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (scanning) "● Scanning..." else "○ Idle",
                style = MaterialTheme.typography.bodyMedium,
                color = if (scanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.align(Alignment.End)
            )

            Spacer(Modifier.height(8.dp))
            Text("提示：点击下方扫描到的信标可直接绑定区域", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))

            if (rows.isEmpty()) {
                Text("No beacons yet. Make sure Bluetooth is ON and beacons are powered.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = { it.uuid + ":" + it.major + ":" + it.minor }) { r ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectBeacon(r) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text("UUID: ${r.uuid}")
                                Text("major/minor: ${r.major}/${r.minor}")
                                Text("RSSI (smoothed): ${r.rssi}")

                                val z = zones.firstOrNull {
                                    it.uuid.equals(r.uuid, ignoreCase = true) &&
                                            it.major == r.major &&
                                            it.minor == r.minor
                                }
                                Spacer(Modifier.height(6.dp))
                                if (z != null) {
                                    Text("已绑定：${z.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                } else {
                                    Text("未绑定（点击绑定）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                                }

                                val age = (System.currentTimeMillis() - r.lastSeenMs).coerceAtLeast(0)
                                Text("age: ${age}ms", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    zones: List<Zone>,
    rows: List<BeaconRow>
) {
    val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f

    Surface(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "HuaFeng室内定位",
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "当前扫描到的信标",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (rows.isEmpty()) "暂未发现信标，请在设置页检查权限状态" else "已发现 ${rows.size} 个信标",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            if (rows.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "请在信标附近稍等片刻，首页会实时显示扫描结果。",
                            modifier = Modifier.padding(20.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                items(rows, key = { it.uuid + ":" + it.major + ":" + it.minor }) { row ->
                    val zoneName = if (row.source == SignalSource.BLE) {
                        zones.firstOrNull {
                            it.uuid.equals(row.uuid, ignoreCase = true) &&
                                    it.major == row.major &&
                                    it.minor == row.minor
                        }?.name
                    } else null

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "${row.source.label} ${row.displayName}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text("RSSI ${row.rssi} dBm", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                row.displayDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                if (row.source == SignalSource.WIFI) {
                                    "用于 WiFi 指纹定位"
                                } else {
                                    zoneName?.let { "已绑定区域：$it" } ?: "未绑定区域"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (zoneName != null || row.source == SignalSource.WIFI) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    appVersionName: String,
    routeAnimationMode: RouteAnimationMode,
    onRouteAnimationModeChange: (RouteAnimationMode) -> Unit,
    developerModeEnabled: Boolean,
    onDeveloperModeEnabledChange: (Boolean) -> Unit,
    signalMode: SignalMode,
    onSignalModeChange: (SignalMode) -> Unit,
    hasRequiredPermissions: Boolean,
    permissionStateVersion: Int,
    onRequestPermissions: () -> Unit,
    positioningAlgorithm: PositioningAlgorithm,
    onPositioningAlgorithmChange: (PositioningAlgorithm) -> Unit,
    neighborCountInput: String,
    onNeighborCountChange: (String) -> Unit,
    strongestBeaconCountInput: String,
    onStrongestBeaconCountChange: (String) -> Unit,
    weightPowerInput: String,
    onWeightPowerChange: (String) -> Unit,
    switchMarginInput: String,
    onSwitchMarginChange: (String) -> Unit,
    enableClusterPrefilter: Boolean,
    onEnableClusterPrefilterChange: (Boolean) -> Unit,
    clusterCellSizeInput: String,
    onClusterCellSizeChange: (String) -> Unit,
    clusterCountInput: String,
    onClusterCountChange: (String) -> Unit,
    enableTemporalSmoothing: Boolean,
    onEnableTemporalSmoothingChange: (Boolean) -> Unit,
    smoothingFactorInput: String,
    onSmoothingFactorChange: (String) -> Unit,
    onSaveAlgorithmConfig: () -> Unit,
    showPositioningCost: Boolean,
    onShowPositioningCostChange: (Boolean) -> Unit,
    showBeaconSummary: Boolean,
    onShowBeaconSummaryChange: (Boolean) -> Unit,
    maxCollectionSeconds: Int,
    onMaxCollectionSecondsChange: (Int) -> Unit,
    requiredCollectionSamples: Int,
    onRequiredCollectionSamplesChange: (Int) -> Unit,
    onOpenZoneConfig: () -> Unit,
    onOpenFpManager: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var developerTapCount by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize()) {
        val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("设置", style = MaterialTheme.typography.headlineSmall)
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("定位信号源", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        SignalModePanel(
                            selectedMode = signalMode,
                            onModeChange = onSignalModeChange
                        )
                        PermissionStatusRow(
                            hasRequiredPermissions = hasRequiredPermissions,
                            onRequestPermissions = onRequestPermissions
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("显示", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)

                        SettingSwitchRow(
                            title = "显示定位耗时",
                            subtitle = "在地图角落显示本次定位计算耗时",
                            checked = showPositioningCost,
                            onCheckedChange = onShowPositioningCostChange
                        )

                        SettingSwitchRow(
                            title = "显示信号摘要",
                            subtitle = "显示信标个数、平均 RSSI 和最强 RSSI",
                            checked = showBeaconSummary,
                            onCheckedChange = onShowBeaconSummaryChange
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("定位算法", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        AlgorithmConfigPanel(
                            selectedAlgorithm = positioningAlgorithm,
                            neighborCountInput = neighborCountInput,
                            strongestBeaconCountInput = strongestBeaconCountInput,
                            weightPowerInput = weightPowerInput,
                            switchMarginInput = switchMarginInput,
                            enableClusterPrefilter = enableClusterPrefilter,
                            clusterCellSizeInput = clusterCellSizeInput,
                            clusterCountInput = clusterCountInput,
                            enableTemporalSmoothing = enableTemporalSmoothing,
                            smoothingFactorInput = smoothingFactorInput,
                            onAlgorithmChange = onPositioningAlgorithmChange,
                            onNeighborCountChange = onNeighborCountChange,
                            onStrongestBeaconCountChange = onStrongestBeaconCountChange,
                            onWeightPowerChange = onWeightPowerChange,
                            onSwitchMarginChange = onSwitchMarginChange,
                            onEnableClusterPrefilterChange = onEnableClusterPrefilterChange,
                            onClusterCellSizeChange = onClusterCellSizeChange,
                            onClusterCountChange = onClusterCountChange,
                            onEnableTemporalSmoothingChange = onEnableTemporalSmoothingChange,
                            onSmoothingFactorChange = onSmoothingFactorChange,
                            onSave = onSaveAlgorithmConfig
                        )
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("管理", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Button(onClick = onOpenZoneConfig, modifier = Modifier.fillMaxWidth()) {
                            Text("区域配置")
                        }
                        Button(onClick = onOpenFpManager, modifier = Modifier.fillMaxWidth()) {
                            Text("采集点管理")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("采集", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "最长采集时间 ${maxCollectionSeconds}s",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = maxCollectionSeconds.toFloat(),
                            onValueChange = { onMaxCollectionSecondsChange(it.roundToInt()) },
                            valueRange = 3f..15f,
                            steps = 11
                        )
                        Text(
                            "有效采样次数 ${requiredCollectionSamples} 次",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Slider(
                            value = requiredCollectionSamples.toFloat(),
                            onValueChange = { onRequiredCollectionSamplesChange(it.roundToInt()) },
                            valueRange = 1f..10f,
                            steps = 8
                        )
                        Text(
                            "采集至少达到设定的有效次数后会提前结束；若一直未满足，则最多等待到这里设定的时长。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            if (developerModeEnabled) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("开发者选项", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "页面切换动画",
                                style = MaterialTheme.typography.titleMedium
                            )
                            AnimationModePanel(
                                selectedMode = routeAnimationMode,
                                onModeChange = onRouteAnimationModeChange
                            )
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (developerModeEnabled) {
                                developerTapCount = 0
                            } else {
                                developerTapCount += 1
                                if (developerTapCount >= 7) {
                                    developerTapCount = 0
                                    onDeveloperModeEnabledChange(true)
                                    Toast.makeText(
                                        context,
                                        "开发者模式已启用",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                        .padding(bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "App 版本",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = appVersionName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    hasRequiredPermissions: Boolean,
    onRequestPermissions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("权限状态", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasRequiredPermissions) "已获取" else "未获取当前模式所需权限",
                style = MaterialTheme.typography.bodySmall,
                color = if (hasRequiredPermissions) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
        }
        if (!hasRequiredPermissions) {
            Button(onClick = onRequestPermissions) {
                Text("请求权限")
            }
        }
    }
}

@Composable
private fun SignalModePanel(
    selectedMode: SignalMode,
    onModeChange: (SignalMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("扫描与定位数据源", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SignalMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) },
                    label = { Text(mode.label) }
                )
            }
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// -------------------- 采集点管理页 --------------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FpManagerScreen(
    repo: FingerprintRepo,
    onBack: () -> Unit
) {
    val fps by repo._observeAllPointsRaw().collectAsState(initial = emptyList())
    val mergedGroups = remember(fps) { repo.mergeByCoordinate(fps) }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionMode = selectedIds.isNotEmpty()

    androidx.activity.compose.BackHandler(enabled = !selectionMode) {
        onBack()
    }
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectedIds = emptySet()
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (selectionMode) selectedIds = emptySet() else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectionMode) "已选择 ${selectedIds.size} 项" else "采集点管理",
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                if (mergedGroups.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (selectionMode) {
                            TextButton(onClick = {
                                selectedIds = if (selectedIds.size == mergedGroups.size) emptySet() else mergedGroups.map { it.groupKey }.toSet()
                            }) {
                                Text(if (selectedIds.size == mergedGroups.size) "取消全选" else "全选")
                            }
                            TextButton(
                                onClick = { showDeleteConfirm = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("删除")
                            }
                        } else {
                            TextButton(onClick = {
                                expandedIds = if (expandedIds.size == mergedGroups.size) emptySet() else mergedGroups.map { it.sourceIds.first() }.toSet()
                            }) {
                                Text(if (expandedIds.size == mergedGroups.size) "收起全部" else "展开全部")
                            }
                            TextButton(onClick = { selectedIds = setOf(mergedGroups.first().groupKey) }) {
                                Text("多选")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (mergedGroups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无采集数据")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(mergedGroups, key = { it.groupKey }) { group ->
                        val anchorId = group.sourceIds.first()
                        val isSelected = group.groupKey in selectedIds
                        val isExpanded = anchorId in expandedIds
                        val rssiEntries = remember(group.groupKey) {
                            group.mergedRssiMap.entries.sortedBy { it.key }
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selectionMode) {
                                            selectedIds = selectedIds.toggle(group.groupKey)
                                        } else {
                                            expandedIds = expandedIds.toggle(anchorId)
                                        }
                                    },
                                    onLongClick = {
                                        selectedIds = selectedIds.toggle(group.groupKey)
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("点位 [Map: ${group.mapId}] 采样 ${group.sourceIds.size} 条", style = MaterialTheme.typography.titleSmall)
                                        Text("坐标: (${group.xPx.toInt()}, ${group.yPx.toInt()})", style = MaterialTheme.typography.bodyMedium)
                                        Text("时间: ${sdf.format(Date(group.createdAtMs))}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (selectionMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selectedIds = selectedIds.toggle(group.groupKey)
                                            }
                                        )
                                    } else {
                                        IconButton(onClick = {
                                            selectedIds = setOf(group.groupKey)
                                            showDeleteConfirm = true
                                        }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }

                                if (isExpanded) {
                                    Spacer(Modifier.height(10.dp))
                                    HorizontalDivider()
                                    Spacer(Modifier.height(10.dp))
                                    Text("RSSI 数据", style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.height(6.dp))
                                    if (rssiEntries.isEmpty()) {
                                        Text("暂无 RSSI 数据", style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        rssiEntries.forEach { (key, value) ->
                                            Text("$key = ${"%.1f".format(value)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && selectedIds.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("确认删除") },
            text = { Text("确定要删除选中的 ${selectedIds.size} 个采集点吗？此操作无法撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        val deletingKeys = selectedIds
                        scope.launch {
                            val deletingIds = mergedGroups
                                .filter { it.groupKey in deletingKeys }
                                .flatMap { it.sourceIds }
                                .toSet()
                            fps.filter { it.id in deletingIds }.forEach { repo.deletePoint(it) }
                            selectedIds = emptySet()
                            showDeleteConfirm = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

private fun Set<Long>.toggle(id: Long): Set<Long> =
    if (id in this) this - id else this + id

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
