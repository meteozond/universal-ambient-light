package com.vasmarfas.UniversalAmbientLight.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import kotlinx.coroutines.launch

/**
 * Переиспользуемые элементы экрана настроек: группа и четыре типа пунктов.
 */
@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
fun CheckBoxPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summary: String? = null,
    onValueChange: ((Boolean) -> Unit)? = null,
) {
    var checked by remember { mutableStateOf(prefs.getBoolean(keyRes)) }
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Checkbox,
                onValueChange = {
                    checked = it
                    prefs.putBoolean(keyRes, it)
                    onValueChange?.invoke(it)
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun EditTextPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    summaryProvider: (String) -> String = { it },
    keyboardType: KeyboardType = KeyboardType.Text,
    externalValue: String? = null,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
) {
    // For numeric prefs the xml defaults live in <integer pref_default_*>; getString()
    // doesn't see them. Fall back to getInt() so the UI shows the resource default
    // instead of an empty field on first launch.
    fun readInitial(): String {
        val stored = prefs.getString(keyRes)
        if (!stored.isNullOrEmpty()) return stored
        return if (keyboardType == KeyboardType.Number) prefs.getInt(keyRes).toString() else ""
    }

    var value by remember(keyRes, recomposeKey) { mutableStateOf(readInitial()) }

    LaunchedEffect(externalValue, recomposeKey) {
        externalValue?.let { value = it }
        recomposeKey?.let { value = readInitial() }
    }

    // Reset dialog state when recomposeKey changes (e.g., when navigating away)
    // This ensures dialogs are closed when the screen is navigated away from
    var showDialog by remember(recomposeKey) { mutableStateOf(false) }

    // Close dialog when component is disposed (e.g., when navigating away)
    DisposableEffect(Unit) {
        onDispose {
            // Force close dialog when leaving the screen
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summaryProvider(value),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        var tempValue by remember(showDialog) { mutableStateOf(value) }
        val keyboardController = LocalSoftwareKeyboardController.current

        LaunchedEffect(showDialog) {
            if (showDialog) {
                tempValue = value
            }
        }

        fun applyValue() {
            // Trim: a stray space from an on-screen keyboard would otherwise pass the
            // "not empty" checks and only fail later as an unreachable host.
            value = tempValue.trim()
            prefs.putString(keyRes, value)
            onValueChange?.invoke(value)
            keyboardController?.hide()
            showDialog = false
        }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = tempValue,
                    onValueChange = { tempValue = it },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { applyValue() }
                    ),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { applyValue() }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    keyboardController?.hide()
                    showDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ListPreference(
    prefs: Preferences,
    keyRes: Int,
    title: String,
    entriesRes: Int,
    entryValuesRes: Int,
    onValueChange: ((String) -> Unit)? = null,
    recomposeKey: Any? = null,
    disabledIndices: Set<Int> = emptySet(),
) {
    val entries = stringArrayResource(entriesRes)
    val entryValues = stringArrayResource(entryValuesRes)

    var value by remember(keyRes, recomposeKey) {
        mutableStateOf(
            prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: ""
        )
    }

    LaunchedEffect(recomposeKey) {
        recomposeKey?.let { value = prefs.getString(keyRes) ?: entryValues.firstOrNull() ?: "" }
    }
    // Reset dialog state when recomposeKey changes (e.g., when navigating away)
    // This ensures dialogs are closed when the screen is navigated away from
    var showDialog by remember(recomposeKey) { mutableStateOf(false) }

    // Close dialog when component is disposed (e.g., when navigating away)
    DisposableEffect(Unit) {
        onDispose {
            // Force close dialog when leaving the screen
            showDialog = false
        }
    }
    val interactionSource = remember { MutableInteractionSource() }

    val summary = entries.getOrNull(entryValues.indexOf(value)) ?: value

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = { showDialog = true }
            )
            .padding(16.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEachIndexed { index, entry ->
                        val isDisabled = index in disabledIndices
                        val interactionSource = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isDisabled) Modifier
                                    else Modifier.clickable(
                                        interactionSource = interactionSource,
                                        indication = LocalIndication.current,
                                        onClick = {
                                            val newValue = entryValues[index]
                                            value = newValue
                                            prefs.putString(keyRes, newValue)
                                            onValueChange?.invoke(newValue)
                                            showDialog = false
                                        }
                                    )
                                )
                                .padding(12.dp)
                                .alpha(if (isDisabled) 0.38f else 1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = value == entryValues[index],
                                onClick = null,
                                enabled = !isDisabled
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = entry)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ClickablePreference(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indication = LocalIndication.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = indication,
                onClick = onClick
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (summary != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
