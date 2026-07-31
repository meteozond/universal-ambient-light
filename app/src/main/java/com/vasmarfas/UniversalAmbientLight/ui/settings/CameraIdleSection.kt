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
 * Группа автоматического сна камеры (issue #38); показывается только для камеры.
 */
@Composable
internal fun ColumnScope.CameraIdleSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    // Camera auto-sleep (issue #38). Camera-only: the screen sources already get a
    // standby signal from ACTION_SCREEN_OFF.
    if (state.captureSource == "camera") {
        SettingsGroup(title = stringResource(R.string.pref_group_camera_idle)) {
            val secondsUnit = stringResource(R.string.unit_seconds)
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_enabled,
                title = stringResource(R.string.pref_title_camera_idle_enabled),
                summary = stringResource(R.string.pref_summary_camera_idle_enabled),
                onValueChange = { enabled ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_enabled",
                        enabled.toString()
                    )
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_timeout,
                title = stringResource(R.string.pref_title_camera_idle_timeout),
                summaryProvider = { "$it $secondsUnit" },
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_timeout",
                        newValue
                    )
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_dark_level,
                title = stringResource(R.string.pref_title_camera_idle_dark_level),
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_dark_level",
                        newValue
                    )
                }
            )
            EditTextPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_motion_level,
                title = stringResource(R.string.pref_title_camera_idle_motion_level),
                keyboardType = KeyboardType.Number,
                onValueChange = { newValue ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_motion_level",
                        newValue
                    )
                }
            )
            CheckBoxPreference(
                prefs = prefs,
                keyRes = R.string.pref_key_camera_idle_static,
                title = stringResource(R.string.pref_title_camera_idle_static),
                summary = stringResource(R.string.pref_summary_camera_idle_static),
                onValueChange = { enabled ->
                    AnalyticsHelper.logSettingChanged(
                        context,
                        "camera_idle_static",
                        enabled.toString()
                    )
                }
            )
        }
    }
}
