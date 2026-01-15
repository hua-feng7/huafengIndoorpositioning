package com.huafeng.beaconzone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("区域配置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onAdd) { Text("新增") } }
            )
        }
    ) { inner ->
        if (zones.isEmpty()) {
            Box(
                Modifier.padding(inner).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有区域，点右上角“新增”")
            }
        } else {
            LazyColumn(
                Modifier.padding(inner).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(zones, key = { it.id }) { z ->
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .fillMaxWidth()
                            .clickable { onEdit(z) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(z.name, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("UUID: ${z.uuid}")
                            Text("major/minor: ${z.major}/${z.minor}")
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
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
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var uuid by remember { mutableStateOf(initial?.uuid.orEmpty()) }
    var major by remember { mutableStateOf(initial?.major?.toString().orEmpty()) }
    var minor by remember { mutableStateOf(initial?.minor?.toString().orEmpty()) }
    var err by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { inner ->
        Column(
            Modifier.padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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

            err?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
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
                                    id = initial?.id ?: Zone(name = "", uuid = "", major = 0, minor = 0).id,
                                    name = name.trim(),
                                    uuid = uuid.trim(),
                                    major = mj,
                                    minor = mn
                                )
                                onSave(z)
                            }
                        }
                    }
                ) { Text("保存") }

                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}
