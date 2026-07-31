package com.vasmarfas.UniversalAmbientLight.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.CameraEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSetupScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Состояние разрешения на камеру, меняется по ходу работы
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    // При первой отрисовке запрашиваем разрешение, если его ещё нет
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // У Offset нет готового Saver, поэтому пишем свой — иначе углы теряются при смене конфигурации.
    val offsetSaver = remember {
        listSaver<Offset, Float>(
            save = { listOf(it.x, it.y) },
            restore = { Offset(it[0], it[1]) }
        )
    }
    var topLeft by rememberSaveable(stateSaver = offsetSaver) { mutableStateOf(Offset(0.1f, 0.1f)) }
    var topRight by rememberSaveable(stateSaver = offsetSaver) {
        mutableStateOf(
            Offset(
                0.9f,
                0.1f
            )
        )
    }
    var bottomRight by rememberSaveable(stateSaver = offsetSaver) {
        mutableStateOf(
            Offset(
                0.9f,
                0.9f
            )
        )
    }
    var bottomLeft by rememberSaveable(stateSaver = offsetSaver) {
        mutableStateOf(
            Offset(
                0.1f,
                0.9f
            )
        )
    }

    // Загружаем сохранённые углы
    LaunchedEffect(Unit) {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        val corners = CameraEncoder.parseCornersString(saved)
        topLeft = Offset(corners[0], corners[1])
        topRight = Offset(corners[2], corners[3])
        bottomRight = Offset(corners[4], corners[5])
        bottomLeft = Offset(corners[6], corners[7])
    }

    // Индекс перетаскиваемого угла (0-3) либо -1
    var dragCorner by remember { mutableIntStateOf(-1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.camera_setup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        topLeft = Offset(0.1f, 0.1f)
                        topRight = Offset(0.9f, 0.1f)
                        bottomRight = Offset(0.9f, 0.9f)
                        bottomLeft = Offset(0.1f, 0.9f)
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.camera_setup_reset)
                        )
                    }
                    IconButton(onClick = {
                        val cornersArray = floatArrayOf(
                            topLeft.x, topLeft.y,
                            topRight.x, topRight.y,
                            bottomRight.x, bottomRight.y,
                            bottomLeft.x, bottomLeft.y
                        )
                        prefs.putString(
                            R.string.pref_key_camera_corners,
                            CameraEncoder.cornersToString(cornersArray)
                        )
                        onBackClick()
                    }) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.camera_setup_save)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasCameraPermission) {
                // Экран запроса разрешения
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }) {
                        Text(stringResource(R.string.camera_grant_permission))
                    }
                }
            } else {
                CameraPreviewView(lifecycleOwner)

                // Слой с углами и перетаскиванием. Canvas объявлен здесь же, чтобы pointerInput
                // всегда читал свежие значения MutableState, а не устаревшее замыкание.
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { startOffset ->
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()

                                    val corners = listOf(
                                        Offset(topLeft.x * w, topLeft.y * h),
                                        Offset(topRight.x * w, topRight.y * h),
                                        Offset(bottomRight.x * w, bottomRight.y * h),
                                        Offset(bottomLeft.x * w, bottomLeft.y * h)
                                    )

                                    val threshold = 80f
                                    var minDist = Float.MAX_VALUE
                                    var minIdx = -1
                                    corners.forEachIndexed { idx, pos ->
                                        val dx = startOffset.x - pos.x
                                        val dy = startOffset.y - pos.y
                                        val dist = sqrt(dx * dx + dy * dy)
                                        if (dist < threshold && dist < minDist) {
                                            minDist = dist
                                            minIdx = idx
                                        }
                                    }
                                    dragCorner = minIdx
                                },
                                onDrag = { change, _ ->
                                    if (dragCorner < 0) return@detectDragGestures
                                    val w = size.width.toFloat()
                                    val h = size.height.toFloat()
                                    val pos = change.position
                                    val nx = (pos.x / w).coerceIn(0f, 1f)
                                    val ny = (pos.y / h).coerceIn(0f, 1f)
                                    val newOffset = Offset(nx, ny)
                                    when (dragCorner) {
                                        0 -> topLeft = newOffset
                                        1 -> topRight = newOffset
                                        2 -> bottomRight = newOffset
                                        3 -> bottomLeft = newOffset
                                    }
                                },
                                onDragEnd = { dragCorner = -1 },
                                onDragCancel = { dragCorner = -1 }
                            )
                        }
                ) {
                    drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft, dragCorner)
                }

                // Подсказка внизу экрана
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .background(
                            Color.Black.copy(alpha = 0.7f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.camera_setup_instruction),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

/**
 * Рисует четырёхугольник по углам и маркеры на них.
 * Чистая функция отрисовки, вызывается внутри DrawScope у Canvas.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCornersOverlay(
    topLeft: Offset,
    topRight: Offset,
    bottomRight: Offset,
    bottomLeft: Offset,
    dragCorner: Int = -1,
) {
    val w = size.width
    val h = size.height

    val tl = Offset(topLeft.x * w, topLeft.y * h)
    val tr = Offset(topRight.x * w, topRight.y * h)
    val br = Offset(bottomRight.x * w, bottomRight.y * h)
    val bl = Offset(bottomLeft.x * w, bottomLeft.y * h)

    // Полупрозрачная заливка поверх превью
    val overlayColor = Color.Black.copy(alpha = 0.4f)
    drawRect(overlayColor)

    // Контур четырёхугольника
    val quadPath = Path().apply {
        moveTo(tl.x, tl.y)
        lineTo(tr.x, tr.y)
        lineTo(br.x, br.y)
        lineTo(bl.x, bl.y)
        close()
    }

    // Внутри четырёхугольника заливка светлее
    drawPath(quadPath, Color.White.copy(alpha = 0.3f))

    // Граница четырёхугольника
    val borderColor = Color(0xFF00E676)
    drawPath(quadPath, borderColor, style = Stroke(width = 3f))

    // Маркеры углов
    val cornerRadius = 18f
    val corners = listOf(tl, tr, br, bl)
    val labels = listOf("TL", "TR", "BR", "BL")

    corners.forEachIndexed { idx, pos ->
        val isActive = dragCorner == idx
        val radius = if (isActive) cornerRadius * 1.5f else cornerRadius

        drawCircle(color = borderColor, radius = radius, center = pos, style = Stroke(width = 3f))
        drawCircle(
            color = if (isActive) borderColor.copy(alpha = 0.8f) else borderColor.copy(alpha = 0.4f),
            radius = radius - 3f,
            center = pos
        )

        drawContext.canvas.nativeCanvas.drawText(
            labels[idx],
            pos.x - 10f,
            pos.y + 5f,
            android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 24f
                isAntiAlias = true
                isFakeBoldText = true
                textAlign = android.graphics.Paint.Align.CENTER
            }
        )
    }
}

/**
 * Превью камеры во всю доступную область.
 * Привязывает только use case Preview и НЕ вызывает unbindAll(), чтобы ImageAnalysis
 * из CameraEncoder в сервисе продолжал работать. При уходе composable из композиции
 * отвязывается только наш Preview.
 */
@Composable
fun CameraPreviewView(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner = LocalLifecycleOwner.current,
) {
    val context = LocalContext.current

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        var previewUseCase: Preview? = null
        var boundProvider: ProcessCameraProvider? = null

        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                boundProvider = provider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                previewUseCase = preview

                // Привязываем только свой Preview — unbindAll() вызывать нельзя
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview
                )
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // Отвязываем только свой use case Preview
            previewUseCase?.let { uc ->
                try {
                    boundProvider?.unbind(uc)
                } catch (_: Exception) {
                    // Экран закрывается; провайдер мог отвязать use case раньше нас.
                }
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * Полноэкранное превью камеры с углами только для просмотра.
 * Используется фоном главного экрана, когда выбран режим камеры.
 *
 * @param isCapturing true — камеру уже занял сервис (CameraEncoder), поэтому вместо живого
 *   превью показываем тёмный фон с углами и пульсирующим индикатором. false — показываем
 *   живое превью для калибровки.
 */
@Composable
fun CameraPreviewBackground(isCapturing: Boolean = false) {
    val context = LocalContext.current
    val prefs = remember { Preferences(context) }

    val hasCameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    // Загружаем сохранённые углы
    val corners = remember {
        val saved = prefs.getString(R.string.pref_key_camera_corners, null)
        CameraEncoder.parseCornersString(saved)
    }
    val topLeft = Offset(corners[0], corners[1])
    val topRight = Offset(corners[2], corners[3])
    val bottomRight = Offset(corners[4], corners[5])
    val bottomLeft = Offset(corners[6], corners[7])

    Box(modifier = Modifier.fillMaxSize()) {
        if (!isCapturing && hasCameraPermission) {
            // Живое превью камеры (для калибровки до запуска)
            CameraPreviewView()
        } else {
            // Тёмный фон: либо камеру занял сервис, либо нет разрешения
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }

        // Углы только для просмотра, без перетаскивания
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCornersOverlay(topLeft, topRight, bottomRight, bottomLeft)
        }

        // Индикатор идущего захвата
        if (isCapturing) {
            val infiniteTransition = rememberInfiniteTransition(label = "capturePulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Canvas(modifier = Modifier.size(12.dp)) {
                        drawCircle(Color.Red.copy(alpha = alpha))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.camera_capturing_status),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
