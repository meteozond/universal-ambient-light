package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Группа автоопределения чёрных полос (issue #23).
 */
@Composable
internal fun ColumnScope.BorderDetectionSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    // Определение чёрных полос, letterbox (issue #23).
    SettingsGroup(title = stringResource(R.string.pref_group_border_detection)) {
        val rgbUnit = stringResource(R.string.unit_rgb)
        val framesUnit = stringResource(R.string.unit_frames)
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_border_detection_enabled,
            title = stringResource(R.string.pref_title_border_detection_enabled),
            summary = stringResource(R.string.pref_summary_border_detection_enabled),
            onValueChange = { enabled ->
                AnalyticsHelper.logSettingChanged(
                    context,
                    "border_detection_enabled",
                    enabled.toString()
                )
            }
        )
        EditTextPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_border_threshold,
            title = stringResource(R.string.pref_title_border_threshold),
            summaryProvider = { "$it $rgbUnit" },
            keyboardType = KeyboardType.Number,
            onValueChange = { newValue ->
                AnalyticsHelper.logSettingChanged(context, "border_threshold", newValue)
            }
        )
        EditTextPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_border_check_interval,
            title = stringResource(R.string.pref_title_border_check_interval),
            summaryProvider = { "$it $framesUnit" },
            keyboardType = KeyboardType.Number,
            onValueChange = { newValue ->
                AnalyticsHelper.logSettingChanged(
                    context,
                    "border_check_interval",
                    newValue
                )
            }
        )
    }
}
