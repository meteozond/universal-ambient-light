package com.vasmarfas.UniversalAmbientLight.ui.home

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.camera.CameraPreviewBackground
import kotlin.math.sqrt

/**
 * Главный экран: кнопка включения, режимы фоновой анимации и кнопки в углах.
 */
enum class EffectMode {
    RAINBOW,
    SIDE_COLORS,
    MOVING_BAR,
    SOLID_WHITE,
    SOLID_RED,
    SOLID_GREEN,
    SOLID_BLUE,
    BREATHING,
    VERTICAL_BARS,
    HORIZONTAL_BARS;
}

internal fun EffectMode.next(): EffectMode =
    when (this) {
        EffectMode.RAINBOW -> EffectMode.SIDE_COLORS
        EffectMode.SIDE_COLORS -> EffectMode.MOVING_BAR
        EffectMode.MOVING_BAR -> EffectMode.SOLID_WHITE
        EffectMode.SOLID_WHITE -> EffectMode.SOLID_RED
        EffectMode.SOLID_RED -> EffectMode.SOLID_GREEN
        EffectMode.SOLID_GREEN -> EffectMode.SOLID_BLUE
        EffectMode.SOLID_BLUE -> EffectMode.BREATHING
        EffectMode.BREATHING -> EffectMode.VERTICAL_BARS
        EffectMode.VERTICAL_BARS -> EffectMode.HORIZONTAL_BARS
        EffectMode.HORIZONTAL_BARS -> EffectMode.RAINBOW
    }

/**
 * Рамка фокуса для d-pad на ТВ. Отдельно от border с условной шириной: 0.dp — это
 * Dp.Hairline, и тонкое кольцо primary рисовалось даже без фокуса.
 */
@Composable
private fun Modifier.focusBorder(focused: Boolean): Modifier =
    if (focused) {
        border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
    } else {
        this
    }

/** Контур OutlinedButton, подсвечивающийся при фокусе с пульта. */
@Composable
private fun focusableOutline(focused: Boolean): BorderStroke =
    BorderStroke(
        width = if (focused) 2.dp else 1.dp,
        color = if (focused) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outline
        }
    )

@Composable
fun MainScreen(
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onEffectsClick: () -> Unit,
    effectMode: EffectMode,
    captureSource: String = "screen",
    onHelpClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onReportIssueClick: () -> Unit = {},
    onLeaveReviewClick: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // В режиме камеры фоном идёт превью камеры с углами
        if (captureSource == "camera") {
            CameraPreviewBackground(isCapturing = isRunning)
        }

        // В режиме экрана — анимированный фон, но только когда захват запущен
        if (isRunning && captureSource != "camera") {
            val infiniteTransition = rememberInfiniteTransition(label = "effects")

            when (effectMode) {
                EffectMode.RAINBOW -> {
                    val angle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(4000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "rotation"
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val diagonal =
                                    sqrt(size.width * size.width + size.height * size.height)

                                rotate(angle) {
                                    drawCircle(
                                        brush = Brush.sweepGradient(
                                            colors = listOf(
                                                Color.Red,
                                                Color.Magenta,
                                                Color.Blue,
                                                Color.Cyan,
                                                Color.Green,
                                                Color.Yellow,
                                                Color.Red
                                            )
                                        ),
                                        radius = diagonal / 2
                                    )
                                }
                            }
                    )
                }

                EffectMode.SIDE_COLORS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val thickness = h * 0.12f

                                // Верх — красный
                                drawRect(
                                    color = Color.Red,
                                    size = androidx.compose.ui.geometry.Size(w, thickness)
                                )
                                // Низ — синий
                                drawRect(
                                    color = Color.Blue,
                                    topLeft = androidx.compose.ui.geometry.Offset(
                                        0f,
                                        h - thickness
                                    ),
                                    size = androidx.compose.ui.geometry.Size(w, thickness)
                                )
                                // Слева — жёлтый
                                drawRect(
                                    color = Color.Yellow,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                                    size = androidx.compose.ui.geometry.Size(thickness, h)
                                )
                                // Справа — зелёный
                                drawRect(
                                    color = Color.Green,
                                    topLeft = androidx.compose.ui.geometry.Offset(
                                        w - thickness,
                                        0f
                                    ),
                                    size = androidx.compose.ui.geometry.Size(thickness, h)
                                )
                            }
                    )
                }

                EffectMode.MOVING_BAR -> {
                    val offset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(3000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "movingBar"
                    )

                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val barWidth = w * 0.12f
                                val x = (w + barWidth) * offset - barWidth

                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(
                                            Color.Red,
                                            Color.Yellow,
                                            Color.Green,
                                            Color.Cyan,
                                            Color.Blue,
                                            Color.Magenta
                                        )
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                                    size = androidx.compose.ui.geometry.Size(barWidth, h)
                                )
                            }
                    )
                }

                EffectMode.SOLID_WHITE,
                EffectMode.SOLID_RED,
                EffectMode.SOLID_GREEN,
                EffectMode.SOLID_BLUE,
                    -> {
                    val color = when (effectMode) {
                        EffectMode.SOLID_WHITE -> Color.White
                        EffectMode.SOLID_RED -> Color.Red
                        EffectMode.SOLID_GREEN -> Color.Green
                        EffectMode.SOLID_BLUE -> Color.Blue
                        else -> Color.White
                    }
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(color)
                    )
                }

                EffectMode.BREATHING -> {
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.2f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(2000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "breathing"
                    )
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Cyan.copy(alpha = alpha))
                    )
                }

                EffectMode.VERTICAL_BARS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val colors = listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta
                                )
                                val barWidth = w / colors.size
                                colors.forEachIndexed { index, c ->
                                    drawRect(
                                        color = c,
                                        topLeft = androidx.compose.ui.geometry.Offset(
                                            index * barWidth,
                                            0f
                                        ),
                                        size = androidx.compose.ui.geometry.Size(barWidth, h)
                                    )
                                }
                            }
                    )
                }

                EffectMode.HORIZONTAL_BARS -> {
                    Spacer(
                        modifier = Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val w = size.width
                                val h = size.height
                                val colors = listOf(
                                    Color.Red,
                                    Color.Yellow,
                                    Color.Green,
                                    Color.Cyan,
                                    Color.Blue,
                                    Color.Magenta
                                )
                                val barHeight = h / colors.size
                                colors.forEachIndexed { index, c ->
                                    drawRect(
                                        color = c,
                                        topLeft = androidx.compose.ui.geometry.Offset(
                                            0f,
                                            index * barHeight
                                        ),
                                        size = androidx.compose.ui.geometry.Size(w, barHeight)
                                    )
                                }
                            }
                    )
                }
            }
        }

        // Центральный блок с рядом кнопок управления. Прокрутка — на телефоне в ландшафте
        // колонка выше экрана, и без неё нижние кнопки было не достать.
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var effectsFocused by remember { mutableStateOf(false) }
            var powerFocused by remember { mutableStateOf(false) }
            var settingsFocused by remember { mutableStateOf(false) }

            // Эффекты рисуются на экране устройства и попадают на ленту через захват
            // экрана — в режиме камеры кнопка ничего не меняет
            val effectsEnabled = captureSource != "camera"

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Кнопка эффектов (слева)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .focusBorder(effectsFocused)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onEffectsClick,
                        enabled = effectsEnabled,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { effectsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = stringResource(R.string.home_effects),
                            modifier = Modifier.size(40.dp),
                            tint = when {
                                !effectsEnabled ->
                                    MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)

                                isRunning -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onBackground
                            }
                        )
                    }
                }

                // Кнопка включения (в центре, самая крупная)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            brush = if (isRunning) Brush.sweepGradient(
                                listOf(
                                    Color.Red,
                                    Color.Magenta,
                                    Color.Blue,
                                    Color.Cyan,
                                    Color.Green,
                                    Color.Yellow,
                                    Color.Red
                                )
                            ) else Brush.linearGradient(listOf(Color.Gray, Color.Gray)),
                            shape = CircleShape
                        )
                        .focusBorder(powerFocused)
                        .padding(4.dp) // Border width
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onToggleClick,
                        modifier = Modifier
                            .size(112.dp)
                            .onFocusChanged { powerFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = stringResource(R.string.home_toggle_power),
                            modifier = Modifier
                                .size(64.dp)
                                .alpha(if (isRunning) 1f else 0.25f),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                // Кнопка настроек (справа, меньше кнопки включения)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                            shape = CircleShape
                        )
                        .focusBorder(settingsFocused)
                        .padding(4.dp)
                        .background(MaterialTheme.colorScheme.background, CircleShape)
                ) {
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier
                            .size(72.dp)
                            .onFocusChanged { settingsFocused = it.isFocused }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.home_settings),
                            modifier = Modifier.size(40.dp),
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Строка состояния
            if (isRunning) {
                Text(
                    text = stringResource(id = R.string.status_grabber_running),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Столбец кнопок помощи и поддержки. Ширина ограничена: на ТВ кнопки
            // растягивались во весь экран. Рамка при фокусе — состояние d-pad на
            // OutlinedButton иначе почти неразличимо.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(horizontal = 16.dp)
            ) {
                var helpFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onHelpClick,
                    border = focusableOutline(helpFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { helpFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Help,
                        contentDescription = stringResource(R.string.help),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.help))
                }

                var supportFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onSupportClick,
                    border = focusableOutline(supportFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { supportFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = stringResource(R.string.support_project),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.support_project))
                }

                var reportIssueFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onReportIssueClick,
                    border = focusableOutline(reportIssueFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { reportIssueFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = stringResource(R.string.report_issue),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.report_issue))
                }

                var leaveReviewFocused by remember { mutableStateOf(false) }
                OutlinedButton(
                    onClick = onLeaveReviewClick,
                    border = focusableOutline(leaveReviewFocused),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { leaveReviewFocused = it.isFocused }
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = stringResource(R.string.leave_review),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.leave_review))
                }
            }
        }
    }
}
