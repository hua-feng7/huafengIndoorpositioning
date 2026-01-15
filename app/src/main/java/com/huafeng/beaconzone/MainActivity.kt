package com.huafeng.beaconzone

import android.Manifest
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
import android.os.Build


data class BeaconRow(
    val uuid: String,
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val lastSeenMs: Long
)

class MainActivity : ComponentActivity(), BeaconConsumer {

    private val beaconManager by lazy { BeaconManager.getInstanceForApplication(this) }
    private val region = Region("all-beacons", null, null, null)

    private var isRanging = false
    private var pushUpdate: ((List<BeaconRow>) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            BeaconZoneTheme {
                ScannerScreen(
                    hasPerm = { hasBlePerms() },
                    requestPerm = { requestBlePerms() },
                    onStart = { startScan() },
                    onStop = { stopScan() },
                    bindUpdate = { cb -> pushUpdate = cb }
                )
            }
        }
    }

    private fun hasBlePerms(): Boolean {
        val locOk = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PERMISSION_GRANTED

        // Android 12+ 才需要 scan/connect；低版本忽略即可
        val bleOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanOk = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_SCAN") == PERMISSION_GRANTED
            val connOk = ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT") == PERMISSION_GRANTED
            scanOk && connOk
        } else true

        return locOk && bleOk
    }

    private fun requestBlePerms() {
        // 统一请求：定位 +（如果是 Android 12+ 再加 scan/connect）
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += "android.permission.BLUETOOTH_SCAN"
            perms += "android.permission.BLUETOOTH_CONNECT"
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
    }

    override fun onBeaconServiceConnect() {
        beaconManager.removeAllRangeNotifiers()
        beaconManager.addRangeNotifier { beacons, _ ->
            val now = System.currentTimeMillis()
            val list = beacons.mapNotNull { b ->
                val id1 = b.id1?.toString() ?: return@mapNotNull null
                BeaconRow(
                    uuid = id1,
                    major = b.id2?.toInt() ?: -1,
                    minor = b.id3?.toInt() ?: -1,
                    rssi = b.rssi,
                    lastSeenMs = now
                )
            }.sortedBy { it.uuid + ":" + it.major + ":" + it.minor }

            runOnUiThread {
                pushUpdate?.invoke(list)
            }
        }

        beaconManager.startRangingBeacons(region)
    }
}

@Composable
private fun ScannerScreen(
    hasPerm: () -> Boolean,
    requestPerm: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    bindUpdate: ((List<BeaconRow>) -> Unit) -> Unit
) {
    var rows by remember { mutableStateOf(listOf<BeaconRow>()) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        bindUpdate { rows = it }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("iBeacon Scanner", style = MaterialTheme.typography.headlineSmall)
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

                Text(if (scanning) "Scanning..." else "Idle", modifier = Modifier.padding(top = 10.dp))
            }

            Spacer(Modifier.height(16.dp))

            if (rows.isEmpty()) {
                Text("No beacons yet. Make sure Bluetooth is ON and beacons are powered.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(rows) { r ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                Text("UUID: ${r.uuid}")
                                Text("major/minor: ${r.major}/${r.minor}")
                                Text("RSSI: ${r.rssi}")
                            }
                        }
                    }
                }
            }
        }
    }
}
