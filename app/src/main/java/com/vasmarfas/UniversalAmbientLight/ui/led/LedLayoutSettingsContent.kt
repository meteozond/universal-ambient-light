package com.vasmarfas.UniversalAmbientLight.ui.led

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Панель параметров раскладки: количество светодиодов по сторонам, стартовый угол,
 * направление обхода и отступы захвата.
 */
@Composable
internal fun LedLayoutSettingsContent(
    topLedText: String,
    onTopLedTextChange: (String) -> Unit,
    rightLedText: String,
    onRightLedTextChange: (String) -> Unit,
    bottomLedText: String,
    onBottomLedTextChange: (String) -> Unit,
    leftLedText: String,
    onLeftLedTextChange: (String) -> Unit,
    bottomGapText: String,
    onBottomGapTextChange: (String) -> Unit,
    captureMarginTopText: String,
    onCaptureMarginTopTextChange: (String) -> Unit,
    captureMarginRightText: String,
    onCaptureMarginRightTextChange: (String) -> Unit,
    captureMarginBottomText: String,
    onCaptureMarginBottomTextChange: (String) -> Unit,
    captureMarginLeftText: String,
    onCaptureMarginLeftTextChange: (String) -> Unit,
    ledOffsetText: String,
    onLedOffsetTextChange: (String) -> Unit,
    scanDepthText: String,
    onScanDepthTextChange: (String) -> Unit,
    sideTop: String,
    onSideTopChange: (String) -> Unit,
    sideRight: String,
    onSideRightChange: (String) -> Unit,
    sideBottom: String,
    onSideBottomChange: (String) -> Unit,
    sideLeft: String,
    onSideLeftChange: (String) -> Unit,
    startCorner: String,
    onStartCornerChange: (String) -> Unit,
    direction: String,
    onDirectionChange: (String) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    // LED count inputs per side (order: left, top, right, bottom)
    OutlinedTextField(
        value = leftLedText,
        onValueChange = onLeftLedTextChange,
        label = { Text(stringResource(R.string.led_layout_left_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = leftLedText.isNotEmpty() && leftLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = topLedText,
        onValueChange = onTopLedTextChange,
        label = { Text(stringResource(R.string.led_layout_top_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = topLedText.isNotEmpty() && topLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = rightLedText,
        onValueChange = onRightLedTextChange,
        label = { Text(stringResource(R.string.led_layout_right_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = rightLedText.isNotEmpty() && rightLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = bottomLedText,
        onValueChange = onBottomLedTextChange,
        label = { Text(stringResource(R.string.led_layout_bottom_count_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = bottomLedText.isNotEmpty() && bottomLedText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(24.dp))

    // LED sides configuration
    Text(
        text = stringResource(R.string.led_layout_active_sides),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Order: left, top, right, bottom
    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_left),
        selectedMode = sideLeft,
        onModeSelected = onSideLeftChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_top),
        selectedMode = sideTop,
        onModeSelected = onSideTopChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_right),
        selectedMode = sideRight,
        onModeSelected = onSideRightChange
    )

    Spacer(modifier = Modifier.height(8.dp))

    SideSelectorCard(
        title = stringResource(R.string.led_layout_side_bottom),
        selectedMode = sideBottom,
        onModeSelected = onSideBottomChange
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Bottom gap
    OutlinedTextField(
        value = bottomGapText,
        onValueChange = onBottomGapTextChange,
        label = { Text(stringResource(R.string.led_layout_bottom_gap_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        isError = bottomGapText.isNotEmpty() && bottomGapText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Capture margins - separate for each side (order: left, top, right, bottom)
    Text(
        text = stringResource(R.string.led_layout_capture_margin_label),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - left
    OutlinedTextField(
        value = captureMarginLeftText,
        onValueChange = onCaptureMarginLeftTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_left_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_left_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginLeftText.isNotEmpty() && captureMarginLeftText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - top
    OutlinedTextField(
        value = captureMarginTopText,
        onValueChange = onCaptureMarginTopTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_top_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_top_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginTopText.isNotEmpty() && captureMarginTopText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - right
    OutlinedTextField(
        value = captureMarginRightText,
        onValueChange = onCaptureMarginRightTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_right_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_right_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginRightText.isNotEmpty() && captureMarginRightText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Capture margin - bottom
    OutlinedTextField(
        value = captureMarginBottomText,
        onValueChange = onCaptureMarginBottomTextChange,
        label = { Text(stringResource(R.string.led_layout_capture_margin_bottom_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_capture_margin_bottom_help),
                fontSize = 12.sp
            )
        },
        isError = captureMarginBottomText.isNotEmpty() && captureMarginBottomText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // LED offset along perimeter
    OutlinedTextField(
        value = ledOffsetText,
        onValueChange = onLedOffsetTextChange,
        label = { Text(stringResource(R.string.led_layout_offset_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_offset_help),
                fontSize = 12.sp
            )
        },
        isError = ledOffsetText.isNotEmpty() && ledOffsetText.toIntOrNull() == null
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Scan Depth
    OutlinedTextField(
        value = scanDepthText,
        onValueChange = onScanDepthTextChange,
        label = { Text(stringResource(R.string.led_layout_scan_depth_label)) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { keyboardController?.hide() }
        ),
        supportingText = {
            Text(
                text = stringResource(R.string.led_layout_scan_depth_help),
                fontSize = 12.sp
            )
        },
        isError = scanDepthText.isNotEmpty() && (scanDepthText.toIntOrNull() == null || scanDepthText.toInt() !in 1..50)
    )

    Spacer(modifier = Modifier.height(24.dp))

    // Start corner selection
    Text(
        text = stringResource(R.string.pref_title_led_start_corner),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("bottom_left", "top_left", "top_right", "bottom_right").forEach { corner ->
            FilterChip(
                selected = startCorner == corner,
                onClick = { onStartCornerChange(corner) },
                label = { Text(getCornerName(corner), fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Direction selection
    Text(
        text = stringResource(R.string.pref_title_led_direction),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf("clockwise", "counterclockwise").forEach { dir ->
            FilterChip(
                selected = direction == dir,
                onClick = { onDirectionChange(dir) },
                label = { Text(getDirectionName(dir), fontSize = 12.sp) },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Legend
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.led_layout_legend_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            LegendItem(
                color = Color(0xFF4CAF50),
                text = stringResource(R.string.led_layout_legend_first_led)
            )
            LegendItem(
                color = Color(0xFF2196F3),
                text = stringResource(R.string.led_layout_legend_active_leds)
            )
            LegendItem(
                color = Color.Gray.copy(alpha = 0.4f),
                text = stringResource(R.string.led_layout_legend_disabled_leds)
            )
            LegendItem(
                color = Color.Gray,
                text = stringResource(R.string.led_layout_legend_screen)
            )
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SideSelectorCard(
    title: String,
    selectedMode: String,
    onModeSelected: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedMode == "enabled",
                    onClick = { onModeSelected("enabled") },
                    label = { Text(stringResource(R.string.led_side_mode_on), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "disabled",
                    onClick = { onModeSelected("disabled") },
                    label = { Text(stringResource(R.string.led_side_mode_off), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = selectedMode == "not_installed",
                    onClick = { onModeSelected("not_installed") },
                    label = { Text(stringResource(R.string.led_side_mode_none), fontSize = 12.sp) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
