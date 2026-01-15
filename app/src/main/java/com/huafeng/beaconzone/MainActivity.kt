package com.huafeng.beaconzone

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker.PERMISSION_GRANTED
import com.huafeng.beaconzone.ui.theme.BeaconZoneTheme
import org.altbeacon.beacon.*

data class BeaconRow(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,         // ✅ 这里存“平滑后的 RSSI”
    val lastSeenMs: Long
)

class MainActivity : ComponentActivity(), BeaconConsumer {

    private val beaconManager by lazy { BeaconManager.getInstanceForApplication(this) }
    private val region = Region("all-beacons", null, null, null)

    private var isRanging = false
    private var pushUpdate: ((List<BeaconRow>) -> Unit)? = null

    // ====== 稳定关键：缓存 + TTL + EMA ======
    // key = "uuid:major:minor"
    private val cache = mutableMapOf<String, BeaconRow>()
    private val TTL_MS = 3000L        // 3 秒内没扫到也先不删
    private val EMA_ALPHA = 0.7       // 越大越稳（0.7~0.9）

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BeaconZoneTheme {
                // ✅ 简单路由：scan / list / edit
                var route by remember { mutableStateOf("scan") }
                var editing by remember { mutableStateOf<Zone?>(null) }

                val store = remember { ZoneStore(applicationContext) }
                val zones by store.zones.collectAsState(initial = emptyList())

                when (route) {
                    "scan" -> ScannerScreen(
                        zones = zones,
                        hasPerm = { hasBlePerms() },
                        requestPerm = { requestBlePerms() },
                        onStart = { startScan() },
                        onStop = { stopScan() },
                        bindUpdate = { cb -> pushUpdate = cb },
                        onOpenZoneConfig = { route = "list" }
                    )

                    "list" -> ZoneListScreen(
                        zones = zones,
                        onAdd = { editing = null; route = "edit" },
                        onEdit = { z -> editing = z; route = "edit" },
                        onBack = { route = "scan" }
                    )

                    "edit" -> ZoneEditScreen(
                        title = if (editing == null) "新增区域" else "编辑区域",
                        initial = editing,
                        onSave = { z ->
                            store.upsert(z)
                            route = "list"
                        },
                        onDelete = if (editing == null) null else {
                            {
                                store.delete(editing!!.id)
                                route = "list"
                            }
                        },
                        onBack = { route = "list" }
                    )
                }
            }
        }
    }

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

    private fun startScan() {
        if (isRanging) return
        isRanging = true
        beaconManager.bind(this)
    }

    private fun stopScan() {
        isRanging = false
        try { beaconManager.stopRangingBeacons(region) } catch (_: Exception) {}
        try { beaconManager.unbind(this) } catch (_: Exception) {}
        cache.clear()
        runOnUiThread { pushUpdate?.invoke(emptyList()) }
    }

    override fun onBeaconServiceConnect() {
        beaconManager.removeAllRangeNotifiers()

        beaconManager.addRangeNotifier { beacons, _ ->
            val now = System.currentTimeMillis()

            // 1) 更新缓存：本轮扫到的写入 cache（带 EMA 平滑）
            beacons.forEach { b ->
                val uuid = b.id1?.toString() ?: return@forEach
                val major = b.id2?.toInt() ?: -1
                val minor = b.id3?.toInt() ?: -1
                val key = "$uuid:$major:$minor"

                val newRssi = b.rssi
                val old = cache[key]
                val smooth = if (old == null) {
                    newRssi
                } else {
                    (EMA_ALPHA * old.rssi + (1.0 - EMA_ALPHA) * newRssi).toInt()
                }

                cache[key] = BeaconRow(
                    uuid = uuid,
                    major = major,
                    minor = minor,
                    rssi = smooth,
                    lastSeenMs = now
                )
            }

            // 2) TTL：输出仍“有效”的 beacon（短时间扫不到也不会消失）
            val alive = cache.values
                .filter { now - it.lastSeenMs <= TTL_MS }
                .sortedBy { it.uuid + ":" + it.major + ":" + it.minor }

            runOnUiThread { pushUpdate?.invoke(alive) }
        }

        beaconManager.startRangingBeacons(region)
    }
}

@Composable
private fun ScannerScreen(
    zones: List<Zone>,
    hasPerm: () -> Boolean,
    requestPerm: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    bindUpdate: ((List<BeaconRow>) -> Unit) -> Unit,
    onOpenZoneConfig: () -> Unit
) {
    var rows by remember { mutableStateOf(listOf<BeaconRow>()) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bindUpdate { rows = it }
    }

    // ✅ 当前区域：在“仍有效的 rows”里找绑定项，取 RSSI 最大
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
        Column(modifier = Modifier.padding(16.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("iBeacon Scanner", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onOpenZoneConfig) { Text("区域配置") }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "当前区域：${currentZoneName ?: "未知（请先在“区域配置”里绑定信标）"}",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (!hasPerm()) requestPerm()
                        else { onStart(); scanning = true }
                    }
                ) { Text("Start") }

                OutlinedButton(
                    onClick = { onStop(); scanning = false }
                ) { Text("Stop") }

                Text(
                    if (scanning) "Scanning..." else "Idle",
                    modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(16.dp))

            if (rows.isEmpty()) {
                Text("No beacons yet. Make sure Bluetooth is ON and beacons are powered.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows, key = { it.uuid + ":" + it.major + ":" + it.minor }) { r ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text("UUID: ${r.uuid}")
                                Text("major/minor: ${r.major}/${r.minor}")
                                Text("RSSI (smoothed): ${r.rssi}")

                                // ✅ 绑定区域显示：现在 rows 不会因为偶尔扫不到就消失，所以不会“看起来没绑定”
                                val z = zones.firstOrNull {
                                    it.uuid.equals(r.uuid, ignoreCase = true) &&
                                            it.major == r.major &&
                                            it.minor == r.minor
                                }
                                if (z != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text("绑定区域：${z.name}", style = MaterialTheme.typography.titleSmall)
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text("未绑定", style = MaterialTheme.typography.bodySmall)
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
