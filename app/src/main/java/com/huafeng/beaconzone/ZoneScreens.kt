package com.huafeng.beaconzone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneListScreen(
    zones: List<Zone>,
    onAdd: () -> Unit,
    onEdit: (Zone) -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("区域配置", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onAdd) { Text("新增") }
            }

            Spacer(Modifier.height(12.dp))

            if (zones.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("还没有区域，请点击右上角“新增”")
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(zones, key = { it.id }) { z ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(z) }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(z.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(4.dp))
                                Text("UUID: ${z.uuid}", style = MaterialTheme.typography.bodySmall)
                                Text("Major/Minor: ${z.major} / ${z.minor}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZoneEditScreen(
    title: String,
    initial: Zone?,
    onSave: (Zone) -> Unit,
    onDelete: (() -> Unit)?,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var uuid by remember { mutableStateOf(initial?.uuid.orEmpty()) }
    var major by remember { mutableStateOf(initial?.major?.toString().orEmpty()) }
    var minor by remember { mutableStateOf(initial?.minor?.toString().orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }

            Spacer(Modifier.height(12.dp))

            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("区域名称") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("绑定 UUID") },
                    placeholder = { Text("例如：01122334-4556-6778-899a-abbccddeeff0") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = major,
                        onValueChange = { major = it },
                        label = { Text("major") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = minor,
                        onValueChange = { minor = it },
                        label = { Text("minor") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (err != null) {
                    Text(err!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.weight(1f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val mj = major.toIntOrNull()
                            val mn = minor.toIntOrNull()
                            when {
                                name.isBlank() -> err = "区域名称不能为空"
                                uuid.isBlank() -> err = "UUID 不能为空"
                                mj == null -> err = "major 必须是整数"
                                mn == null -> err = "minor 必须是整数"
                                else -> {
                                    err = null
                                    val z = Zone(
                                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                        name = name.trim(),
                                        uuid = uuid.trim(),
                                        major = mj,
                                        minor = mn
                                    )
                                    onSave(z)
                                }
                            }
                        }
                    ) { Text("保存配置") }

                    if (onDelete != null) {
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = onDelete
                        ) { Text("删除区域") }
                    }
                }
            }
        }
    }
}
