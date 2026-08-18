package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

/**
 * Второе, независимое подключение к Home Assistant — работает параллельно с основным
 * выводом. Например, основной вывод идёт на WLED, а лампы по бокам комнаты — сюда.
 * Чекбокс разворачивает остальные поля, как и везде в этом экране.
 */
@Composable
internal fun ColumnScope.HomeAssistantSecondarySection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current

    SettingsGroup(title = stringResource(R.string.pref_group_ha_secondary)) {
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_ha2_enabled,
            title = stringResource(R.string.pref_title_ha2_enabled),
            summary = stringResource(R.string.pref_summary_ha2_enabled),
            onValueChange = { enabled ->
                state.ha2Enabled = enabled
                AnalyticsHelper.logSettingChanged(context, "ha2_enabled", enabled.toString())
            }
        )

        if (!state.ha2Enabled) return@SettingsGroup

        EditTextPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_ha2_host,
            title = stringResource(R.string.pref_title_host),
            summaryProvider = { it },
            onValueChange = {
                AnalyticsHelper.logSettingChanged(context, "ha2_host", it)
            }
        )
        EditTextPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_ha2_port,
            title = stringResource(R.string.pref_title_port),
            summaryProvider = { it },
            keyboardType = KeyboardType.Number,
            onValueChange = {
                AnalyticsHelper.logSettingChanged(context, "ha2_port", it)
            }
        )

        HomeAssistantSection(
            prefs = prefs,
            analyticsPrefix = "ha2",
            keyToken = R.string.pref_key_ha2_token,
            lampsSpec = state.ha2LampsSpec,
            onLampsClick = { state.showHa2LampsDialog = true },
            keyUpdateInterval = R.string.pref_key_ha2_update_interval,
            keyChangeThreshold = R.string.pref_key_ha2_change_threshold,
            keyTransition = R.string.pref_key_ha2_transition,
            keyBrightnessMode = R.string.pref_key_ha2_brightness_mode,
            keyBrightness = R.string.pref_key_ha2_brightness,
            keyDarkOff = R.string.pref_key_ha2_dark_off,
            keyDarkThreshold = R.string.pref_key_ha2_dark_threshold,
            keyTurnOffLights = R.string.pref_key_ha2_turn_off_lights,
        )
    }
}
