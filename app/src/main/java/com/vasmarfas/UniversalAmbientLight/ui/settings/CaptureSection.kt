package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.MtkThalCaptureEncoder
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.ColorProcessor
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Группа «Захват»: источник и метод захвата, качество, частота кадров и цветокоррекция.
 */
@Composable
internal fun ColumnScope.CaptureSection(prefs: Preferences, state: SettingsScreenState, onLedLayoutClick: () -> Unit, onCameraSetupClick: () -> Unit) {
    val context = LocalContext.current
    // Capturing Group
    SettingsGroup(title = stringResource(R.string.pref_group_capturing)) {
        // Capture Source (Screen / Camera)
        key(state.captureSource) {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_capture_source,
                title = stringResource(R.string.pref_title_capture_source),
                entriesRes = R.array.pref_list_capture_source,
                entryValuesRes = R.array.pref_list_capture_source_values,
                recomposeKey = state.captureSource,
                onValueChange = { newSource ->
                    state.captureSource = newSource
                    AnalyticsHelper.logSettingChanged(context, "capture_source", newSource)
                }
            )
        }

        // Capture Method (MediaProjection, Screencap, Accessibility)
        if (state.captureSource == "screen") {
            ListPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_capture_method,
                title = stringResource(R.string.pref_title_capture_method),
                entriesRes = R.array.pref_list_capture_method,
                entryValuesRes = R.array.pref_list_capture_method_values,
                recomposeKey = state.captureMethod,
                disabledIndices = remember {
                    val entryValues =
                        context.resources.getStringArray(R.array.pref_list_capture_method_values)
                    val disabled = mutableSetOf<Int>()
                    entryValues.forEachIndexed { index, value ->
                        when (value) {
                            "mtk_thal_capture" -> if (!MtkThalCaptureEncoder.isAvailable()) disabled.add(
                                index
                            )
                        }
                    }
                    disabled
                },
                onValueChange = { newMethod ->
                    if (newMethod == "accessibility") {
                        // Check if service is already enabled
                        if (AccessibilityCaptureService.getInstance() == null) {
                            // Show disclosure dialog BEFORE applying fully or opening settings
                            // Note: ListPreference already saved the value to prefs, so we might need to revert if denied
                            state.previousCaptureMethod =
                                state.captureMethod // save old method (which is actually current before update in state)
                            // Ideally ListPreference shouldn't update automatically, but here we intercept
                            state.showAccessibilityDisclosure = true
                        } else {
                            state.captureMethod = newMethod
                            AnalyticsHelper.logSettingChanged(
                                context,
                                "capture_method",
                                newMethod
                            )
                        }
                    } else {
                        state.captureMethod = newMethod
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "capture_method",
                            newMethod
                        )
                    }
                }
            )

            if (state.captureMethod == "adb_local" || state.captureMethod == "adb_stream" || state.captureMethod == "scrcpy") {
                EditTextPreference(
                    prefs = prefs,
                    keyRes = R.string.pref_key_adb_port,
                    title = stringResource(R.string.pref_title_adb_port),
                    summaryProvider = { it },
                    keyboardType = KeyboardType.Number
                )
                ClickablePreference(
                    title = stringResource(R.string.pref_btn_adb_pair),
                    summary = stringResource(R.string.pref_summary_adb_pair),
                    onClick = { state.showAdbPairingDialog = true }
                )
            }
        }

        // Camera corner setup (only when camera source is selected)
        if (state.captureSource == "camera") {
            ClickablePreference(
                title = stringResource(R.string.pref_title_camera_setup),
                summary = stringResource(R.string.pref_summary_camera_setup),
                onClick = { onCameraSetupClick() }
            )
        }

        ClickablePreference(
            title = stringResource(R.string.pref_title_led_layout),
            summary = stringResource(R.string.pref_summary_led_layout),
            onClick = {
                AnalyticsHelper.logLedLayoutOpened(context)
                onLedLayoutClick()
            }
        )
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_framerate,
            title = stringResource(R.string.pref_title_framerate),
            entriesRes = R.array.pref_list_framerate,
            entryValuesRes = R.array.pref_list_framerate_values,
            onValueChange = { newFramerate ->
                val framerateInt = newFramerate.toIntOrNull() ?: 10
                AnalyticsHelper.logFramerateChanged(context, framerateInt)
                AnalyticsHelper.logSettingChanged(context, "framerate", newFramerate)
            }
        )
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_capture_quality,
            title = stringResource(R.string.pref_title_capture_quality),
            entriesRes = R.array.pref_list_capture_quality,
            entryValuesRes = R.array.pref_list_capture_quality_values,
            onValueChange = { newQuality ->
                val qualityInt = newQuality.toIntOrNull() ?: 128
                AnalyticsHelper.logCaptureQualityChanged(context, qualityInt)
                AnalyticsHelper.logSettingChanged(context, "capture_quality", newQuality)
            }
        )
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_use_avg_color,
            title = stringResource(R.string.pref_title_use_avg_color),
            onValueChange = { enabled ->
                AnalyticsHelper.logUseAvgColorChanged(context, enabled)
                AnalyticsHelper.logSettingChanged(
                    context,
                    "use_avg_color",
                    enabled.toString()
                )
            }
        )

        // Color processing settings
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_color_processing_enabled,
            title = stringResource(R.string.pref_title_color_processing_enabled),
            onValueChange = { enabled ->
                state.colorProcessingEnabled = enabled
                AnalyticsHelper.logSettingChanged(
                    context,
                    "color_processing_enabled",
                    enabled.toString()
                )
            }
        )

        if (state.colorProcessingEnabled) {
            // Bumped on every per-channel/global color pref change; drives the live preview below.
            var colorPrefsVersion by remember { mutableIntStateOf(0) }

            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_color_brightness,
                title = stringResource(R.string.pref_title_color_brightness),
                summaryProvider = { "${it}%" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    colorPrefsVersion++
                    AnalyticsHelper.logSettingChanged(context, "color_brightness", newValue)
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_color_contrast,
                title = stringResource(R.string.pref_title_color_contrast),
                summaryProvider = { "${it}%" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    colorPrefsVersion++
                    AnalyticsHelper.logSettingChanged(context, "color_contrast", newValue)
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_color_black_level,
                title = stringResource(R.string.pref_title_color_black_level),
                summaryProvider = { "${it}%" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    colorPrefsVersion++
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "color_black_level",
                        newValue
                    )
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_color_white_level,
                title = stringResource(R.string.pref_title_color_white_level),
                summaryProvider = { "${it}%" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    colorPrefsVersion++
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "color_white_level",
                        newValue
                    )
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_color_saturation,
                title = stringResource(R.string.pref_title_color_saturation),
                summaryProvider = { "${it}%" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    colorPrefsVersion++
                    AnalyticsHelper.logSettingChanged(context, "color_saturation", newValue)
                }
            )

            // Per-channel correction subsection (issue #21).
            Text(
                text = stringResource(R.string.pref_group_color_per_channel),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            )
            listOf(
                R.string.pref_key_color_brightness_r to R.string.pref_title_color_brightness_r,
                R.string.pref_key_color_brightness_g to R.string.pref_title_color_brightness_g,
                R.string.pref_key_color_brightness_b to R.string.pref_title_color_brightness_b
            ).forEach { (keyRes, titleRes) ->
                val keyName = stringResource(keyRes)
                EditTextPreference(
                    prefs = prefs,
                    keyRes = keyRes,
                    title = stringResource(titleRes),
                    summaryProvider = { "${it}%" },
                    keyboardType = KeyboardType.Number,
                    onValueChange = { newValue ->
                        colorPrefsVersion++
                        AnalyticsHelper.logSettingChanged(
                            context,
                            keyName,
                            newValue
                        )
                    }
                )
            }
            listOf(
                R.string.pref_key_color_gamma_r to R.string.pref_title_color_gamma_r,
                R.string.pref_key_color_gamma_g to R.string.pref_title_color_gamma_g,
                R.string.pref_key_color_gamma_b to R.string.pref_title_color_gamma_b
            ).forEach { (keyRes, titleRes) ->
                val keyName = stringResource(keyRes)
                EditTextPreference(
                    prefs = prefs,
                    keyRes = keyRes,
                    title = stringResource(titleRes),
                    summaryProvider = { "${it}%" },
                    keyboardType = KeyboardType.Number,
                    onValueChange = { newValue ->
                        colorPrefsVersion++
                        AnalyticsHelper.logSettingChanged(
                            context,
                            keyName,
                            newValue
                        )
                    }
                )
            }

            ColorProcessingPreview(prefs = prefs, version = colorPrefsVersion)
        }
    }
}

/**
 * Live preview of the color-processing pipeline.
 * Reads all relevant prefs each time [version] changes (incremented by the
 * surrounding EditTextPreference fields) and renders three pure R/G/B swatches
 * plus a grayscale gradient, all after [ColorProcessor.processColor].
 */
@Composable
private fun ColorProcessingPreview(prefs: Preferences, version: Int) {
    val preview = remember(version) {
        val brightness = prefs.getInt(R.string.pref_key_color_brightness, 100)
        val contrast = prefs.getInt(R.string.pref_key_color_contrast, 100)
        val blackLevel = prefs.getInt(R.string.pref_key_color_black_level, 0)
        val whiteLevel = prefs.getInt(R.string.pref_key_color_white_level, 100)
        val saturation = prefs.getInt(R.string.pref_key_color_saturation, 100)
        val bR = prefs.getInt(R.string.pref_key_color_brightness_r, 100)
        val bG = prefs.getInt(R.string.pref_key_color_brightness_g, 100)
        val bB = prefs.getInt(R.string.pref_key_color_brightness_b, 100)
        val gR = prefs.getInt(R.string.pref_key_color_gamma_r, 100)
        val gG = prefs.getInt(R.string.pref_key_color_gamma_g, 100)
        val gB = prefs.getInt(R.string.pref_key_color_gamma_b, 100)

        val process: (Int, Int, Int) -> Color = { r, g, b ->
            val (ro, go, bo) = ColorProcessor.processColor(
                r, g, b,
                brightness, contrast, blackLevel, whiteLevel, saturation,
                bR, bG, bB, gR, gG, gB
            )
            Color(ro, go, bo)
        }
        Triple(
            process(255, 0, 0),
            process(0, 255, 0),
            process(0, 0, 255)
        ) to (0..8).map { step -> process(step * 32, step * 32, step * 32) }
    }
    val swatches = preview.first
    val ramp = preview.second

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.pref_color_preview_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.first))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.second))
            Box(modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(swatches.third))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(Brush.horizontalGradient(ramp))
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.pref_color_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
