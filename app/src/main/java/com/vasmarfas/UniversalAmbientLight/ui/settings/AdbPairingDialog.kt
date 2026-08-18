package com.vasmarfas.UniversalAmbientLight.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vasmarfas.UniversalAmbientLight.BuildConfig
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.util.AdbAutoPair
import com.vasmarfas.UniversalAmbientLight.common.util.AdbKeyHelper
import com.vasmarfas.UniversalAmbientLight.common.util.AppAdbConnectionManager
import com.vasmarfas.UniversalAmbientLight.common.util.DevOptionsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Диалог сопряжения и подключения по беспроводному ADB (Android 11+).
 */

@Composable
fun AdbPairingDialog(
    context: Context,
    prefs: Preferences,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var pairCode by remember { mutableStateOf("") }
    var pairPort by remember { mutableStateOf("") }

    var manualExpanded by remember { mutableStateOf(false) }
    var showAutoPairConsent by remember { mutableStateOf(false) }

    var devEnabled by remember { mutableStateOf(DevOptionsHelper.isDeveloperOptionsEnabled(context)) }
    var adbEnabled by remember { mutableStateOf(DevOptionsHelper.isAdbEnabled(context)) }
    val onLabel = stringResource(R.string.adb_status_on)
    val offLabel = stringResource(R.string.adb_status_off)

    // Когда пользователь возвращается, включив службу доступности (сценарий автосопряжения),
    // снова открываем диалог согласия, чтобы сопряжение продолжилось без повторного нажатия.
    // Заодно перечитываем статусы Dev options/ADB: за ними пользователь и уходил в настройки,
    // и замороженные значения показывали бы красное «выключено» после включения.
    val lifecycle = (context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                devEnabled = DevOptionsHelper.isDeveloperOptionsEnabled(context)
                adbEnabled = DevOptionsHelper.isAdbEnabled(context)
                // Сначала isAvailable: consume снимает флаг, и в обратном порядке возврат
                // без включённой службы съедал бы его — согласие не открылось бы уже никогда
                if (AccessibilityCaptureService.isAvailable() &&
                    AccessibilityCaptureService.consumeAutoPairPending()
                ) {
                    showAutoPairConsent = true
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // Подключение по-старому (Android 10 и ниже либо после `adb tcpip 5555`): обычный RSA поверх TCP.
    fun runLegacyConnect() {
        testing = true
        status = null
        scope.launch(Dispatchers.IO) {
            try {
                val port =
                    prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull() ?: 5555
                val keyPair = AdbKeyHelper.getKeyPair(context)
                val dadb = Dadb.create("127.0.0.1", port, keyPair)
                dadb.shell("echo ok")
                dadb.close()
                withContext(Dispatchers.Main) {
                    testing = false
                    status = "✓ ${context.getString(R.string.adb_test_success)}"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    testing = false
                    status =
                        String.format(context.getString(R.string.adb_test_failed), e.message ?: "?")
                }
            }
        }
    }

    // Автосопряжение через службу доступности. Запускается только после явного согласия.
    fun runAutoPair() {
        testing = true
        status = null
        scope.launch(Dispatchers.IO) {
            if (!AccessibilityCaptureService.isAvailable()) {
                // Просим службу вернуть нас обратно, как только пользователь её включит,
                // и заново открыть диалог согласия, чтобы сопряжение пошло дальше само.
                AccessibilityCaptureService.requestReturnToAppOnConnect()
                AccessibilityCaptureService.markAutoPairPending()
                withContext(Dispatchers.Main) {
                    testing = false
                    status = context.getString(R.string.adb_autopair_need_accessibility)
                    openAccessibilitySettings(context)
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.adb_enable_accessibility_toast,
                            context.getString(R.string.app_name)
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                status = context.getString(R.string.adb_autopair_waiting)
                DevOptionsHelper.openWirelessDebugging(context)
            }
            val result = AdbAutoPair.run(context)
            withContext(Dispatchers.Main) {
                testing = false
                status = when (result) {
                    is AdbAutoPair.Result.Paired ->
                        "✓ ${context.getString(R.string.adb_pair_success)}"

                    is AdbAutoPair.Result.NeedsAccessibility ->
                        context.getString(R.string.adb_autopair_need_accessibility)

                    is AdbAutoPair.Result.Timeout ->
                        context.getString(R.string.adb_autopair_timeout)

                    is AdbAutoPair.Result.Failed ->
                        String.format(context.getString(R.string.adb_pair_failed), result.message)
                }
                status?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                if (result is AdbAutoPair.Result.Paired) {
                    val launch =
                        context.packageManager.getLaunchIntentForPackage(context.packageName)
                    launch?.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    if (launch != null) try {
                        context.startActivity(launch)
                    } catch (_: Exception) {
                        // Экран настроек стороннего приложения может отсутствовать или быть
                        // закрыт прошивкой — тогда просто ничего не открываем.
                    }
                }
            }
        }
    }

    if (showAutoPairConsent) {
        AlertDialog(
            onDismissRequest = { showAutoPairConsent = false },
            title = { Text(stringResource(R.string.adb_autopair_consent_title)) },
            text = { Text(stringResource(R.string.adb_autopair_consent_text)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutoPairConsent = false
                    runAutoPair()
                }) { Text(stringResource(R.string.adb_autopair_consent_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutoPairConsent = false }) {
                    Text(stringResource(R.string.scanner_cancel))
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { if (!testing) onDismiss() },
        title = { Text(stringResource(R.string.adb_pair_title)) },
        text = {
            Column {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(R.string.adb_pair_instruction),
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(
                            R.string.adb_status_label,
                            if (devEnabled) onLabel else offLabel,
                            if (adbEnabled) onLabel else offLabel
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (devEnabled && adbEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (devEnabled) {
                                if (!DevOptionsHelper.openDeveloperOptions(context)) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.adb_toast_cannot_open_dev),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.adb_toast_dev_options_help),
                                    Toast.LENGTH_LONG
                                ).show()
                                DevOptionsHelper.openAboutDeviceForBuildNumber(context)
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (devEnabled) R.string.adb_btn_open_dev_options
                                else R.string.adb_btn_how_to_enable_dev_options
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (!DevOptionsHelper.openWirelessDebugging(context)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.adb_toast_cannot_open_wireless),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) { Text(stringResource(R.string.adb_btn_open_wireless_debug)) }

                    // Сначала подключение по-старому — Android 10 и ниже либо после `adb tcpip 5555`.
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.adb_legacy_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { runLegacyConnect() }
                    ) { Text(stringResource(R.string.adb_legacy_btn)) }

                    // Сопряжение для Android 11+ показываем ниже. Оно разовое, а кнопка
                    // автоматического режима читает код через службу доступности (после согласия).
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.adb_pair_code_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Путь «в одно касание»: сначала диалог согласия, затем приложение само
                        // читает код через доступность, находит порт по mDNS и сопрягается, не уводя
                        // пользователя с экрана. Требует службу доступности — во флейворе Google Play скрыт.
                        if (BuildConfig.HAS_ACCESSIBILITY) {
                            OutlinedButton(
                                enabled = !testing,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { showAutoPairConsent = true }
                            ) { Text(stringResource(R.string.adb_autopair_btn)) }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        // Ручной ввод спрятан под спойлер, чтобы не мешать навигации с пульта.
                        TextButton(
                            onClick = { manualExpanded = !manualExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = (if (manualExpanded) "▾ " else "▸ ") +
                                        stringResource(R.string.adb_pair_manual_label)
                            )
                        }
                        if (manualExpanded) {
                            Text(
                                text = stringResource(R.string.adb_pair_code_instruction),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pairPort,
                                onValueChange = {
                                    pairPort = it.filter { c -> c.isDigit() }.take(5)
                                },
                                label = { Text(stringResource(R.string.adb_pair_port_hint)) },
                                singleLine = true,
                                enabled = !testing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedTextField(
                                value = pairCode,
                                onValueChange = {
                                    pairCode = it.filter { c -> c.isDigit() }.take(6)
                                },
                                label = { Text(stringResource(R.string.adb_pair_code_hint)) },
                                singleLine = true,
                                enabled = !testing,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                enabled = !testing && pairCode.length == 6 && pairPort.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    testing = true
                                    status = null
                                    val code = pairCode
                                    val port = pairPort.toIntOrNull() ?: 0
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            val mgr = AppAdbConnectionManager.getInstance(context)
                                            // Сервер сопряжения adbd слушает на адресе локальной сети; запасной вариант — loopback.
                                            val host =
                                                io.github.muntashirakon.adb.android.AndroidUtils.getHostIpAddress(
                                                    context
                                                ) ?: "127.0.0.1"
                                            val ok = mgr.pair(host, port, code)
                                            withContext(Dispatchers.Main) {
                                                testing = false
                                                status =
                                                    if (ok) "✓ ${context.getString(R.string.adb_pair_success)}"
                                                    else String.format(
                                                        context.getString(R.string.adb_pair_failed),
                                                        "?"
                                                    )
                                            }
                                        } catch (e: Throwable) {
                                            withContext(Dispatchers.Main) {
                                                testing = false
                                                status = String.format(
                                                    context.getString(R.string.adb_pair_failed),
                                                    e.message ?: "?"
                                                )
                                            }
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(if (testing) R.string.adb_pairing else R.string.adb_pair_btn))
                            }
                        } // end if (manualExpanded)
                    }
                }

                // Состояние и прогресс всегда на виду, под областью прокрутки.
                val currentStatus = status
                if (currentStatus != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = currentStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentStatus.startsWith("✓"))
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                if (testing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    status = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val port =
                                prefs.getString(R.string.pref_key_adb_port, "5555")?.toIntOrNull()
                                    ?: 5555
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                // Android 11+: подключение по TLS (порт находит сам, иначе берёт введённый).
                                val mgr = AppAdbConnectionManager.getInstance(context)
                                if (!mgr.isConnected) {
                                    val auto = try {
                                        mgr.autoConnect(context, 8000)
                                    } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                                        throw e
                                    } catch (_: Exception) {
                                        false
                                    }
                                    if (!auto && port > 0) mgr.connect("127.0.0.1", port)
                                }
                                val ok = mgr.isConnected
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    status =
                                        if (ok) "✓ ${context.getString(R.string.adb_test_success)}"
                                        else String.format(
                                            context.getString(R.string.adb_test_failed),
                                            "not connected"
                                        )
                                }
                            } else {
                                // Android 10 и ниже: старый RSA поверх TCP (порт 5555).
                                val keyPair = AdbKeyHelper.getKeyPair(context)
                                val dadb = Dadb.create("127.0.0.1", port, keyPair)
                                dadb.shell("echo ok")
                                dadb.close()
                                withContext(Dispatchers.Main) {
                                    testing = false
                                    status = "✓ ${context.getString(R.string.adb_test_success)}"
                                }
                            }
                        } catch (e: io.github.muntashirakon.adb.AdbPairingRequiredException) {
                            withContext(Dispatchers.Main) {
                                testing = false
                                status = context.getString(R.string.error_adb_pairing_required)
                            }
                        } catch (e: Throwable) {
                            withContext(Dispatchers.Main) {
                                testing = false
                                status = String.format(
                                    context.getString(R.string.adb_test_failed),
                                    e.message ?: "unknown"
                                )
                            }
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.pref_btn_adb_pair))
            }
        },
        dismissButton = {
            TextButton(enabled = !testing, onClick = onDismiss) {
                Text(stringResource(R.string.scanner_cancel))
            }
        }
    )
}
