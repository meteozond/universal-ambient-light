package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R

/**
 * Общие настройки и отладочная информация.
 */
@Composable
internal fun ColumnScope.GeneralSection(prefs: Preferences, state: SettingsScreenState) {
    val context = LocalContext.current
    SettingsGroup(title = stringResource(R.string.pref_group_general)) {
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_boot,
            title = stringResource(R.string.pref_title_boot),
            onValueChange = { enabled ->
                AnalyticsHelper.logBootStartEnabled(context, enabled)
                AnalyticsHelper.logSettingChanged(context, "boot_start", enabled.toString())
            }
        )
        CheckBoxPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_standby_keepalive,
            title = stringResource(R.string.pref_title_standby_keepalive),
            summary = stringResource(R.string.pref_summary_standby_keepalive),
            onValueChange = { enabled ->
                AnalyticsHelper.logSettingChanged(
                    context,
                    "standby_keepalive",
                    enabled.toString()
                )
            }
        )
        ListPreference(
            prefs = prefs,
            keyRes = R.string.pref_key_language,
            title = stringResource(R.string.pref_title_language),
            entriesRes = R.array.pref_list_language,
            entryValuesRes = R.array.pref_list_language_values,
            onValueChange = { language ->
                AnalyticsHelper.logLanguageChanged(context, language)
                AnalyticsHelper.logSettingChanged(context, "language", language)
                AnalyticsHelper.updateLanguageProperty(context, language)
                LocaleHelper.setLocale(context, language)
                (context as? Activity)?.recreate()
            }
        )
    }

    // Отладка
    SettingsGroup(title = "Debug") {
        ClickablePreference(
            title = "Device Info",
            summary = "Show device information for debugging",
            onClick = { state.showDebugDialog = true }
        )
    }
}
