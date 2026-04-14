package com.huafeng.beaconzone

import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.system.measureTimeMillis

private const val COLLECTION_SAMPLE_STEP_MS = 500L

private fun hasValidImageSize(imageSize: Size): Boolean =
    imageSize.width.isFinite() &&
            imageSize.height.isFinite() &&
            imageSize.width > 0f &&
            imageSize.height > 0f

private fun loadMapImageSize(context: Context, map: MapEntity?): Size? {
    if (map == null) return null

    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (map.id == "kunlun") {
            BitmapFactory.decodeResource(context.resources, R.drawable.floor, options)
        } else {
            val imagePath = map.imageUri ?: return null
            BitmapFactory.decodeFile(imagePath, options)
        }

        if (options.outWidth > 0 && options.outHeight > 0) {
            Size(options.outWidth.toFloat(), options.outHeight.toFloat())
        } else null
    } catch (_: Exception) {
        null
    }
}

private fun calculateImageBounds(
    containerSize: IntSize,
    imageSize: Size
): Rect? {
    if (containerSize.width <= 0 || containerSize.height <= 0) return null
    if (!hasValidImageSize(imageSize)) return null

    val scale = minOf(
        containerSize.width / imageSize.width,
        containerSize.height / imageSize.height
    )
    val drawnWidth = imageSize.width * scale
    val drawnHeight = imageSize.height * scale
    val left = (containerSize.width - drawnWidth) / 2f
    val top = (containerSize.height - drawnHeight) / 2f
    return Rect(left, top, left + drawnWidth, top + drawnHeight)
}

private fun mapScreenPointToImage(
    screenPoint: Offset,
    imageBounds: Rect,
    imageSize: Size
): Offset? {
    if (!imageBounds.contains(screenPoint)) return null
    val relativeX = (screenPoint.x - imageBounds.left) / imageBounds.width
    val relativeY = (screenPoint.y - imageBounds.top) / imageBounds.height
    return Offset(
        x = relativeX * imageSize.width,
        y = relativeY * imageSize.height
    )
}

private fun mapImagePointToScreen(
    imagePoint: Offset,
    imageBounds: Rect,
    imageSize: Size
): Offset {
    val relativeX = imagePoint.x / imageSize.width
    val relativeY = imagePoint.y / imageSize.height
    return Offset(
        x = imageBounds.left + relativeX * imageBounds.width,
        y = imageBounds.top + relativeY * imageBounds.height
    )
}

/**
 * ✅ 将图片拷贝到 App 内部存储，防止源文件丢失
 */
private suspend fun saveUriToInternalStorage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    try {
        val mapsDir = File(context.filesDir, "maps").apply { if (!exists()) mkdirs() }
        val fileName = "map_${System.currentTimeMillis()}.jpg"
        val destFile = File(mapsDir, fileName)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        destFile.absolutePath
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@Composable
fun MapCollectScreen(
    repo: FingerprintRepo,
    liveRows: List<BeaconRow>,
    initialMapId: String?,
    onCurrentMapChange: (String) -> Unit,
    positioningAlgorithm: PositioningAlgorithm,
    neighborCountInput: String,
    strongestBeaconCountInput: String,
    weightPowerInput: String,
    enableDensityCompensationWknn: Boolean,
    densityNeighborCountInput: String,
    densityCompensationStrengthInput: String,
    switchMarginInput: String,
    enableClusterPrefilter: Boolean,
    clusterCellSizeInput: String,
    clusterCountInput: String,
    enableTemporalSmoothing: Boolean,
    smoothingFactorInput: String,
    showPositioningCost: Boolean,
    showBeaconSummary: Boolean,
    maxCollectionSeconds: Int,
    requiredCollectionSamples: Int,
    onBack: () -> Unit,
    onExport: (String) -> Unit,
    onManagePoints: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onBack)
    val maps by repo.observeAllMaps().collectAsState(initial = emptyList())
    var currentMap by remember(initialMapId) { mutableStateOf<MapEntity?>(null) }
    
    LaunchedEffect(Unit) {
        repo.initDefaultMap()
    }
    
    LaunchedEffect(maps, initialMapId) {
        if (maps.isEmpty()) return@LaunchedEffect
        val preferredMap = initialMapId?.let { selectedId ->
            maps.find { it.id == selectedId }
        }
        val fallbackMap = maps.find { it.id == "kunlun" } ?: maps.first()
        val resolvedMap = preferredMap ?: currentMap?.let { existing ->
            maps.find { it.id == existing.id }
        } ?: fallbackMap

        if (currentMap?.id != resolvedMap.id) {
            currentMap = resolvedMap
        }
        onCurrentMapChange(resolvedMap.id)
    }

    val fpEntities by produceState(emptyList<FingerprintEntity>(), currentMap) {
        if (currentMap != null) {
            repo.observeByMap(currentMap!!.id).collect { value = it }
        }
    }

    val mergedFpGroups = remember(fpEntities) { repo.mergeByCoordinate(fpEntities) }

    val fpVectors = remember(mergedFpGroups) {
        mergedFpGroups.map { entity ->
            FingerprintSample(
                id = entity.sourceIds.first(),
                xPx = entity.xPx,
                yPx = entity.yPx,
                rssiMap = entity.mergedRssiMap
            )
        }
    }

    var showMapPicker by remember { mutableStateOf(false) }
    var showNameDialog by remember { mutableStateOf<Uri?>(null) }
    var mapToDelete by remember { mutableStateOf<MapEntity?>(null) } // ✅ 删除确认状态
    var newMapName by remember { mutableStateOf("") }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showNameDialog = uri
    }

    var pickedImagePoint by remember { mutableStateOf<Offset?>(null) }
    var collecting by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(0) }
    var validSampleCount by remember { mutableIntStateOf(0) }
    var lastAcceptedScanSignature by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var snappedId by remember { mutableStateOf<Long?>(null) }
    var snappedPx by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    var lastPositioningCostMs by remember { mutableLongStateOf(0L) }
    var mapContainerSize by remember { mutableStateOf(IntSize.Zero) }
    val smoothingAlpha = remember(enableTemporalSmoothing, smoothingFactorInput) {
        smoothingFactorInput.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.55
    }

    val beaconCount = liveRows.size
    val averageRssi = remember(liveRows) {
        liveRows.takeIf { it.isNotEmpty() }?.map { it.rssi }?.average()?.toInt()
    }
    val strongestRssi = remember(liveRows) {
        liveRows.maxOfOrNull { it.rssi }
    }

    val positioningConfig = remember(
        positioningAlgorithm,
        neighborCountInput,
        strongestBeaconCountInput,
        weightPowerInput,
        enableDensityCompensationWknn,
        densityNeighborCountInput,
        densityCompensationStrengthInput,
        switchMarginInput,
        enableClusterPrefilter,
        clusterCellSizeInput,
        clusterCountInput,
        enableTemporalSmoothing,
        smoothingFactorInput
    ) {
        PositioningConfig(
            algorithm = positioningAlgorithm,
            neighborCount = neighborCountInput.toIntOrNull()?.coerceAtLeast(1) ?: 3,
            strongestBeaconCount = strongestBeaconCountInput.toIntOrNull()?.coerceAtLeast(1) ?: 3,
            weightPower = weightPowerInput.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 1.0,
            enableDensityCompensationWknn = enableDensityCompensationWknn,
            densityNeighborCount = densityNeighborCountInput.toIntOrNull()?.coerceAtLeast(1) ?: 5,
            densityCompensationStrength = densityCompensationStrengthInput.toDoubleOrNull()?.coerceIn(0.1, 3.0) ?: 1.0,
            switchMargin = switchMarginInput.toDoubleOrNull()?.coerceAtLeast(0.0) ?: 0.0,
            enableClusterPrefilter = enableClusterPrefilter,
            clusterCellSizePx = clusterCellSizeInput.toFloatOrNull()?.coerceAtLeast(32f) ?: 160f,
            clusterCount = clusterCountInput.toIntOrNull()?.coerceAtLeast(1) ?: 3
        )
    }

    LaunchedEffect(collecting, maxCollectionSeconds) {
        if (!collecting || currentMap == null) return@LaunchedEffect
        validSampleCount = 0
        lastAcceptedScanSignature = null
        val totalMs = maxCollectionSeconds * 1_000L
        val startedAt = System.currentTimeMillis()
        secondsLeft = maxCollectionSeconds
        while (collecting) {
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed >= totalMs) {
                collecting = false
                break
            }
            secondsLeft = kotlin.math.ceil((totalMs - elapsed).coerceAtLeast(0).toDouble() / 1000.0).toInt()
            delay(200)
        }
        validSampleCount = 0
        lastAcceptedScanSignature = null
    }

    LaunchedEffect(collecting, liveRows, currentMap?.id, pickedImagePoint, requiredCollectionSamples) {
        if (!collecting || currentMap == null || pickedImagePoint == null) return@LaunchedEffect

        val scanVector = PositioningEngine.buildScanVector(liveRows)
        if (scanVector.isEmpty()) return@LaunchedEffect

        val signature = buildString {
            append(liveRows.maxOfOrNull { it.lastSeenMs } ?: 0L)
            append("|")
            scanVector.entries
                .sortedBy { it.key }
                .forEach { (key, value) ->
                    append(key)
                    append("=")
                    append(String.format(java.util.Locale.US, "%.1f", value))
                    append(";")
                }
        }

        if (signature == lastAcceptedScanSignature) return@LaunchedEffect

        lastAcceptedScanSignature = signature
        repo.insertPoint(currentMap!!.id, pickedImagePoint!!.x, pickedImagePoint!!.y, scanVector)
        validSampleCount += 1

        if (validSampleCount >= requiredCollectionSamples) {
            collecting = false
        }
    }

    LaunchedEffect(locating, liveRows, fpVectors) {
        if (!locating) return@LaunchedEffect
        while (locating) {
            if (fpVectors.isNotEmpty()) {
                var estimate: PositionEstimate? = null
                lastPositioningCostMs = measureTimeMillis {
                    estimate = PositioningEngine.estimatePosition(
                        liveRssi = PositioningEngine.buildScanVector(liveRows),
                        fingerprints = fpVectors,
                        config = positioningConfig,
                        currentFingerprintId = snappedId
                    )
                }
                snappedId = estimate?.fingerprintId
                snappedPx = estimate?.let {
                    smoothDisplayedPosition(
                        previous = snappedPx,
                        candidate = it.xPx to it.yPx,
                        enabled = enableTemporalSmoothing,
                        alpha = smoothingAlpha
                    )
                }
            } else {
                snappedPx = null
                lastPositioningCostMs = 0L
            }
            delay(500)
        }
    }

    LaunchedEffect(locating) {
        if (!locating) {
            snappedId = null
            snappedPx = null
            lastPositioningCostMs = 0L
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val compactTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() * 0.4f
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = compactTopInset, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentMap?.name?.let { "地图定位: $it" } ?: "地图定位",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showMapPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("管理地图")
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (currentMap != null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val imgData = if (currentMap!!.id == "kunlun") R.drawable.floor else currentMap!!.imageUri
                        val painter = rememberAsyncImagePainter(model = imgData)
                        val imageSize = remember(currentMap?.id, currentMap?.imageUri) {
                            loadMapImageSize(context, currentMap)
                        } ?: Size.Unspecified
                        val imageBounds = remember(mapContainerSize, imageSize) {
                            calculateImageBounds(mapContainerSize, imageSize)
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { mapContainerSize = it }
                                .pointerInput(currentMap, collecting, imageBounds, imageSize) {
                                    detectTapGestures { tap ->
                                        if (!collecting && imageBounds != null && hasValidImageSize(imageSize)) {
                                            pickedImagePoint = mapScreenPointToImage(
                                                screenPoint = tap,
                                                imageBounds = imageBounds,
                                                imageSize = imageSize
                                            )
                                        }
                                    }
                                }
                        ) {
                            Image(
                                painter = painter,
                                contentDescription = "map",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                if (imageBounds != null && hasValidImageSize(imageSize)) {
                                    mergedFpGroups.forEach { e ->
                                        val center = mapImagePointToScreen(
                                            imagePoint = Offset(e.xPx, e.yPx),
                                            imageBounds = imageBounds,
                                            imageSize = imageSize
                                        )
                                        drawCircle(Color(0xFF1E88E5), 8f, center)
                                    }
                                    snappedPx?.let { (x, y) ->
                                        val center = mapImagePointToScreen(
                                            imagePoint = Offset(x, y),
                                            imageBounds = imageBounds,
                                            imageSize = imageSize
                                        )
                                        drawCircle(Color.Red, 12f, center)
                                    }
                                    pickedImagePoint?.let { point ->
                                        val center = mapImagePointToScreen(
                                            imagePoint = point,
                                            imageBounds = imageBounds,
                                            imageSize = imageSize
                                        )
                                        drawCircle(Color.Black.copy(alpha = 0.9f), 18f, center)
                                        drawCircle(Color(0xFFFFC107), 13f, center)
                                        drawCircle(Color.White, 6f, center)
                                    }
                                }
                            }

                            BeaconDebugOverlay(
                                showBeaconSummary = showBeaconSummary,
                                showPositioningCost = showPositioningCost,
                                positioningCostMs = lastPositioningCostMs,
                                beaconCount = beaconCount,
                                averageRssi = averageRssi,
                                strongestRssi = strongestRssi
                            )

                            if (collecting) {
                                CollectionLoadingOverlay(
                                    validSampleCount = validSampleCount,
                                    requiredCollectionSamples = requiredCollectionSamples,
                                    maxCollectionSeconds = maxCollectionSeconds,
                                    secondsLeft = secondsLeft
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !collecting && pickedImagePoint != null, onClick = { collecting = true }, modifier = Modifier.weight(1f)) {
                        Text(if (collecting) "采集中(${validSampleCount}/$requiredCollectionSamples)" else "采集保存(最长${maxCollectionSeconds}s)")
                    }
                    Button(onClick = {
                        locating = !locating
                        if (!locating) {
                            snappedId = null
                            snappedPx = null
                            lastPositioningCostMs = 0L
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Text(if (locating) "停止定位" else "开始定位")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onManagePoints() }, modifier = Modifier.weight(1f)) { Text("管理数据") }
                    Button(onClick = { currentMap?.let { onExport(it.id) } }, modifier = Modifier.weight(1f)) { Text("导出CSV") }
                }
            }
        }
    }

    // --- 地图管理弹窗 ---
    if (showMapPicker) {
        AlertDialog(
            onDismissRequest = { showMapPicker = false },
            title = { Text("管理地图") },
            text = {
                Box(Modifier.heightIn(max = 400.dp)) {
                    LazyColumn {
                        items(maps) { m ->
                            ListItem(
                                headlineContent = { Text(m.name) },
                                trailingContent = {
                                    if (m.id != "kunlun") { // ✅ 保护默认地图
                                        IconButton(onClick = { mapToDelete = m }) {
                                            Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                },
                                modifier = Modifier.clickable { 
                                    currentMap = m
                                    onCurrentMapChange(m.id)
                                    showMapPicker = false
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { pickerLauncher.launch("image/*") }) { Text("添加新地图") }
            },
            dismissButton = {
                Button(onClick = { showMapPicker = false }) { Text("关闭") }
            }
        )
    }

    // --- 删除确认弹窗 ---
    if (mapToDelete != null) {
        AlertDialog(
            onDismissRequest = { mapToDelete = null },
            title = { Text("删除地图") },
            text = { Text("确定要删除地图 \"${mapToDelete!!.name}\" 吗？这将同步删除该地图下的所有采集点。") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        scope.launch {
                            repo.deleteMap(mapToDelete!!)
                            if (currentMap?.id == mapToDelete!!.id) {
                                val nextMap = maps.firstOrNull { it.id != mapToDelete!!.id }
                                currentMap = nextMap
                                nextMap?.let { onCurrentMapChange(it.id) }
                            }
                            mapToDelete = null
                        }
                    }
                ) { Text("确定删除") }
            },
            dismissButton = {
                Button(onClick = { mapToDelete = null }) { Text("取消") }
            }
        )
    }

    // --- 新地图命名弹窗 ---
    if (showNameDialog != null) {
        AlertDialog(
            onDismissRequest = { showNameDialog = null },
            title = { Text("为新地图命名") },
            text = {
                TextField(value = newMapName, onValueChange = { newMapName = it }, placeholder = { Text("输入地图名字") })
            },
            confirmButton = {
                Button(onClick = {
                    if (newMapName.isNotBlank()) {
                        scope.launch {
                            val localPath = saveUriToInternalStorage(context, showNameDialog!!)
                            if (localPath != null) {
                                repo.insertMap(newMapName, localPath)
                                onCurrentMapChange(newMapName)
                            }
                            newMapName = ""
                            showNameDialog = null
                        }
                    }
                }) { Text("确定") }
            }
        )
    }
}

@Composable
private fun CollectionLoadingOverlay(
    validSampleCount: Int,
    requiredCollectionSamples: Int,
    maxCollectionSeconds: Int,
    secondsLeft: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text(
                    "正在采集，请保持静止",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    "有效数据 ${validSampleCount}/$requiredCollectionSamples，最长 ${maxCollectionSeconds}s，剩余 ${secondsLeft}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun smoothDisplayedPosition(
    previous: Pair<Float, Float>?,
    candidate: Pair<Float, Float>,
    enabled: Boolean,
    alpha: Double
): Pair<Float, Float> {
    if (!enabled || previous == null) return candidate

    val safeAlpha = alpha.coerceIn(0.0, 1.0)
    if (safeAlpha >= 1.0) return candidate

    return Pair(
        (previous.first * (1 - safeAlpha) + candidate.first * safeAlpha).toFloat(),
        (previous.second * (1 - safeAlpha) + candidate.second * safeAlpha).toFloat()
    )
}

@Composable
fun AnimationModePanel(
    selectedMode: RouteAnimationMode,
    onModeChange: (RouteAnimationMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("页面动画", style = MaterialTheme.typography.titleSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedMode == RouteAnimationMode.SIMPLE,
                onClick = { onModeChange(RouteAnimationMode.SIMPLE) },
                label = { Text("简单动画") }
            )
            FilterChip(
                selected = selectedMode == RouteAnimationMode.ELEGANT,
                onClick = { onModeChange(RouteAnimationMode.ELEGANT) },
                label = { Text("优雅动画") }
            )
        }
    }
}

@Composable
private fun BeaconDebugOverlay(
    showBeaconSummary: Boolean,
    showPositioningCost: Boolean,
    positioningCostMs: Long,
    beaconCount: Int,
    averageRssi: Int?,
    strongestRssi: Int?
) {
    val debugTextStyle = MaterialTheme.typography.bodySmall.copy(
        fontSize = 11.sp,
        lineHeight = 1.2.em,
        color = Color.White,
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.9f),
            offset = Offset(1.5f, 1.5f),
            blurRadius = 4f
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (showBeaconSummary) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("信标 ${beaconCount}个", style = debugTextStyle)
                averageRssi?.let { Text("平均RSSI ${it}dBm", style = debugTextStyle) }
                strongestRssi?.let { Text("最强RSSI ${it}dBm", style = debugTextStyle) }
            }
        }

        if (showPositioningCost) {
            Text(
                text = "定位耗时 ${positioningCostMs}ms",
                style = debugTextStyle,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlgorithmConfigPanel(
    selectedAlgorithm: PositioningAlgorithm,
    neighborCountInput: String,
    strongestBeaconCountInput: String,
    weightPowerInput: String,
    enableDensityCompensationWknn: Boolean,
    densityNeighborCountInput: String,
    densityCompensationStrengthInput: String,
    switchMarginInput: String,
    enableClusterPrefilter: Boolean,
    clusterCellSizeInput: String,
    clusterCountInput: String,
    enableTemporalSmoothing: Boolean,
    smoothingFactorInput: String,
    onAlgorithmChange: (PositioningAlgorithm) -> Unit,
    onNeighborCountChange: (String) -> Unit,
    onStrongestBeaconCountChange: (String) -> Unit,
    onWeightPowerChange: (String) -> Unit,
    onEnableDensityCompensationWknnChange: (Boolean) -> Unit,
    onDensityNeighborCountChange: (String) -> Unit,
    onDensityCompensationStrengthChange: (String) -> Unit,
    onSwitchMarginChange: (String) -> Unit,
    onEnableClusterPrefilterChange: (Boolean) -> Unit,
    onClusterCellSizeChange: (String) -> Unit,
    onClusterCountChange: (String) -> Unit,
    onEnableTemporalSmoothingChange: (Boolean) -> Unit,
    onSmoothingFactorChange: (String) -> Unit,
    onSave: () -> Unit
) {
    var algorithmMenuExpanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("定位算法", style = MaterialTheme.typography.titleSmall)

        ExposedDropdownMenuBox(
            expanded = algorithmMenuExpanded,
            onExpandedChange = { algorithmMenuExpanded = !algorithmMenuExpanded }
        ) {
            OutlinedTextField(
                value = selectedAlgorithm.label,
                onValueChange = {},
                readOnly = true,
                label = { Text("定位算法") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = algorithmMenuExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)
            )

            ExposedDropdownMenu(
                expanded = algorithmMenuExpanded,
                onDismissRequest = { algorithmMenuExpanded = false }
            ) {
                PositioningAlgorithm.entries.forEach { algorithm ->
                    DropdownMenuItem(
                        text = { Text(algorithm.label) },
                        onClick = {
                            onAlgorithmChange(algorithm)
                            algorithmMenuExpanded = false
                        }
                    )
                }
            }
        }

        when (selectedAlgorithm) {
            PositioningAlgorithm.ONE_NN -> {
                Text(
                    "1NN 不需要设置 N，直接返回最近指纹点坐标。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            PositioningAlgorithm.KNN -> {
                OutlinedTextField(
                    value = neighborCountInput,
                    onValueChange = onNeighborCountChange,
                    label = { Text("K 值") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "KNN 使用最近 K 个参考点做普通平均坐标。",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            PositioningAlgorithm.WKNN -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = neighborCountInput,
                        onValueChange = onNeighborCountChange,
                        label = { Text("K 值") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weightPowerInput,
                        onValueChange = onWeightPowerChange,
                        label = { Text("权重指数 p") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "WKNN 使用欧氏距离倒数作为权重，权重形式为 1 / d^p。",
                    style = MaterialTheme.typography.bodySmall
                )

                SettingSwitchRow(
                    title = "密度补偿 WKNN",
                    subtitle = "降低高密度采样区域的天然优势，减轻结果被密集点拉偏",
                    checked = enableDensityCompensationWknn,
                    onCheckedChange = onEnableDensityCompensationWknnChange
                )

                if (enableDensityCompensationWknn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = densityNeighborCountInput,
                            onValueChange = onDensityNeighborCountChange,
                            label = { Text("密度邻居数") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = densityCompensationStrengthInput,
                            onValueChange = onDensityCompensationStrengthChange,
                            label = { Text("补偿强度") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Decimal
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        "按空间邻居密度反向修正 WKNN 权重。邻居数越大越看整体密度，补偿强度越大越偏向稀疏区域。",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            PositioningAlgorithm.KNN_STRONGEST_BEACONS -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = neighborCountInput,
                        onValueChange = onNeighborCountChange,
                        label = { Text("K 值") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = strongestBeaconCountInput,
                        onValueChange = onStrongestBeaconCountChange,
                        label = { Text("最强 N 信标") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        OutlinedTextField(
            value = switchMarginInput,
            onValueChange = onSwitchMarginChange,
            label = { Text("防抖阈值") },
            supportingText = { Text("0 表示不防抖，数值越大越不容易切换") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            modifier = Modifier.fillMaxWidth()
        )

        HorizontalDivider()

        SettingSwitchRow(
            title = "聚类预筛选",
            subtitle = "先按坐标网格聚类，再只保留最相近的几个簇参与定位",
            checked = enableClusterPrefilter,
            onCheckedChange = onEnableClusterPrefilterChange
        )

        if (enableClusterPrefilter) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = clusterCellSizeInput,
                    onValueChange = onClusterCellSizeChange,
                    label = { Text("网格尺寸(px)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    ),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = clusterCountInput,
                    onValueChange = onClusterCountChange,
                    label = { Text("保留簇数") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SettingSwitchRow(
            title = "时间平滑",
            subtitle = "对连续定位结果做指数平滑，减少跳点",
            checked = enableTemporalSmoothing,
            onCheckedChange = onEnableTemporalSmoothingChange
        )

        if (enableTemporalSmoothing) {
            OutlinedTextField(
                value = smoothingFactorInput,
                onValueChange = onSmoothingFactorChange,
                label = { Text("平滑系数 α") },
                supportingText = { Text("0 到 1，越大越跟手，越小越平稳") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text("保存算法设置")
        }
    }
}
