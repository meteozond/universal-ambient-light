package com.vasmarfas.UniversalAmbientLight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.Manifest
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.vasmarfas.UniversalAmbientLight.common.AccessibilityCaptureService
import com.vasmarfas.UniversalAmbientLight.common.BootActivity
import com.vasmarfas.UniversalAmbientLight.common.ScreenGrabberService
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.LocaleHelper
import com.vasmarfas.UniversalAmbientLight.common.util.openAccessibilitySettings
import com.vasmarfas.UniversalAmbientLight.common.util.PermissionHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.common.util.ReviewHelper
import com.vasmarfas.UniversalAmbientLight.common.util.TclBypass
import com.vasmarfas.UniversalAmbientLight.common.util.UsbSerialPermissionHelper
import com.vasmarfas.UniversalAmbientLight.ui.home.EffectMode
import com.vasmarfas.UniversalAmbientLight.ui.home.next
import com.vasmarfas.UniversalAmbientLight.ui.navigation.AppNavHost
import com.vasmarfas.UniversalAmbientLight.ui.navigation.Screen
import com.vasmarfas.UniversalAmbientLight.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.onAttach(newBase))
    }

    private var mRecorderRunning by mutableStateOf(false)

    private var mSetupRequiredMessage by mutableStateOf<String?>(null)
    private var mMediaProjectionManager: MediaProjectionManager? = null
    private var mPermissionDeniedCount = 0
    private var mTclWarningShown = false
    private lateinit var appUpdateManager: AppUpdateManager
    private var currentEffect by mutableStateOf(EffectMode.RAINBOW)
    private var mSessionStartTime: Long? = null
    private var mSessionEverConnected: Boolean = false
    private var mSessionMethod: String? = null
    private var mSessionSource: String? = null
    private var mSessionProtocol: String? = null

    private var usbPermissionReceiverRegistered = false
    private var usbAttachReceiverRegistered = false

    private val usbAttachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED != intent.action) return

            val prefs = Preferences(this@MainActivity)
            val connectionType =
                prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
            if (!"adalight".equals(connectionType, ignoreCase = true)) return

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            // Only request permission for devices that our USB-Serial prober recognizes
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this@MainActivity,
                device = device,
                onReady = { /* permission granted, nothing else to do here */ },
                onDenied = null,
                showToast = true
            )
        }
    }

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val checked = intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TAG, false)
            val wasRunning = mRecorderRunning
            mRecorderRunning = checked

            val error = intent.getStringExtra(ScreenGrabberService.BROADCAST_ERROR)
            val tclBlocked =
                intent.getBooleanExtra(ScreenGrabberService.BROADCAST_TCL_BLOCKED, false)

            if (checked) mSessionEverConnected = true

            if (wasRunning && !checked) {
                val reason = when {
                    tclBlocked -> "tcl_blocked"
                    error != null -> "error"
                    else -> "service_stop"
                }
                finishCaptureSession(reason)
            }

            // Диалог и toast откладываем: onReceive выполняется на главном потоке, и показ
            // окна прямо здесь задерживает доставку остальных широковещаний.
            if (tclBlocked && !mTclWarningShown) {
                mTclWarningShown = true
                window.decorView.post {
                    TclBypass.showTclHelpDialog(this@MainActivity) { requestScreenCapture() }
                }
            } else if (error != null && !QuickTileService.isListening) {
                val errorMessage = error
                window.decorView.post {
                    Toast.makeText(baseContext, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable Edge-to-Edge mode manually to avoid using deprecated LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // which Google Play flags in Android 15. Android 15+ (API 35+) requires LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS by default.
        // This mode is available from Android 11 (API 30).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        AnalyticsHelper.logAppLaunched(this)

        mMediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Defer the Play update check to the next main-loop cycle — keeps onCreate cheap on TV.
        appUpdateManager = AppUpdateManagerFactory.create(this)
        window.decorView.post { checkForUpdates() }

        ContextCompat.registerReceiver(
            this,
            mMessageReceiver,
            IntentFilter(ScreenGrabberService.BROADCAST_FILTER),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        checkForInstance()

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }

        maybeRequestBatteryOptimizationExemption()

        setContent {
            AppTheme {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        navController = navController,
                        isRunning = mRecorderRunning,
                        onToggleClick = { toggleScreenCapture() },
                        onEffectsClick = {
                            currentEffect = currentEffect.next()
                            AnalyticsHelper.logEffectChanged(
                                this@MainActivity,
                                currentEffect.name.lowercase()
                            )
                        },
                        effectMode = currentEffect
                    )

                    mSetupRequiredMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { mSetupRequiredMessage = null },
                            title = { Text(stringResource(R.string.setup_required_title)) },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = {
                                    mSetupRequiredMessage = null
                                    navController.navigate(Screen.Settings.route)
                                }) {
                                    Text(stringResource(R.string.setup_required_open_settings))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { mSetupRequiredMessage = null }) {
                                    Text(stringResource(R.string.setup_required_cancel))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun maybeRequestBatteryOptimizationExemption() {
        if (PermissionHelper.isIgnoringBatteryOptimizations(this)) return

        val prefs = Preferences.defaultSharedPreferences(this)
        val keyLastAttempt = "battery_opt_exemption_last_attempt_ms"
        val lastAttempt = prefs.getLong(keyLastAttempt, 0L)
        val now = System.currentTimeMillis()
        val cooldownMs = 24L * 60L * 60L * 1000L // 24h
        if (now - lastAttempt < cooldownMs) return

        prefs.edit { putLong(keyLastAttempt, now) }

        AnalyticsHelper.logBatteryOptimizationRequested(this)

        PermissionHelper.requestIgnoreBatteryOptimizations(this)
    }

    override fun onResume() {
        super.onResume()

        // log battery-opt exemption grant once per install
        run {
            val p = Preferences.defaultSharedPreferences(this)
            val loggedKey = "battery_opt_granted_logged"
            if (!p.getBoolean(loggedKey, false) && PermissionHelper.isIgnoringBatteryOptimizations(
                    this
                )
            ) {
                AnalyticsHelper.logBatteryOptimizationGranted(this)
                p.edit { putBoolean(loggedKey, true) }
            }
        }

        appUpdateManager
            .appUpdateInfo
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability()
                    == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
                ) {
                    // If an in-app update is already running, resume the update.
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        REQUEST_UPDATE_CODE
                    )
                }
            }

        // Auto-request USB permission when a USB-Serial device is already connected while app is open.
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        if ("adalight".equals(connectionType, ignoreCase = true)) {
            UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
                context = this,
                device = null,
                onReady = { /* already granted */ },
                onDenied = null,
                showToast = false
            )
        }

        if (!usbAttachReceiverRegistered) {
            val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            ContextCompat.registerReceiver(
                this,
                usbAttachReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            usbAttachReceiverRegistered = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(mMessageReceiver)
        } catch (_: IllegalArgumentException) {
            // Context.unregisterReceiver, в отличие от LocalBroadcastManager, бросает
            // исключение, если приёмник не был зарегистрирован; для нас это не ошибка.
        }

        if (usbAttachReceiverRegistered) {
            try {
                unregisterReceiver(usbAttachReceiver)
            } catch (_: Exception) {
                // onPause и onDestroy могут снять приёмник оба; повторное снятие бросает
                // IllegalArgumentException, и это не ошибка.
            }
            usbAttachReceiverRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (usbAttachReceiverRegistered) {
            try {
                unregisterReceiver(usbAttachReceiver)
            } catch (_: Exception) {
                // onPause и onDestroy могут снять приёмник оба; повторное снятие бросает
                // IllegalArgumentException, и это не ошибка.
            }
            usbAttachReceiverRegistered = false
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return

        // shouldShowRequestPermissionRationale returns false both on the first ask AND
        // after "Don't ask again" — disambiguate via a one-time pref so we stop spamming.
        val prefs = Preferences.defaultSharedPreferences(this)
        val askedBefore = prefs.getBoolean(PREF_NOTIF_PERMISSION_ASKED, false)
        if (askedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.POST_NOTIFICATIONS
            )
        ) return

        prefs.edit { putBoolean(PREF_NOTIF_PERMISSION_ASKED, true) }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun beginCaptureSession(source: String, method: String, protocol: String) {
        mSessionStartTime = System.currentTimeMillis()
        mSessionEverConnected = false
        mSessionSource = source
        mSessionMethod = method
        mSessionProtocol = protocol
    }

    private fun finishCaptureSession(endReason: String) {
        val start = mSessionStartTime ?: return
        val durationSeconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        AnalyticsHelper.logScreenCaptureStopped(this, durationSeconds)
        AnalyticsHelper.logCaptureSessionSummary(
            this, durationSeconds,
            mSessionMethod, mSessionSource, mSessionProtocol,
            mSessionEverConnected, endReason
        )
        mSessionStartTime = null
        mSessionMethod = null
        mSessionSource = null
        mSessionProtocol = null
        mSessionEverConnected = false
    }

    private fun toggleScreenCapture() {
        if (!mRecorderRunning) {
            // Catch missing host/port/LED counts here — otherwise the user walks through the
            // overlay and MediaProjection dialogs only to get a toast from the service.
            ScreenGrabberService.validateSettings(this)?.let { error ->
                Log.d(TAG, "Start aborted, settings incomplete: ${error.code}")
                AnalyticsHelper.logServiceError(this, "setup_required_${error.code}", error.details)
                mSetupRequiredMessage = error.message
                return
            }

            val prefs = Preferences(this)
            prefs.putBoolean(R.string.pref_key_lighting_was_active, true)
            val captureSource =
                prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"

            if (captureSource == "camera") {
                requestCameraCapture()
            } else {
                ensureUsbPermissionForAdalight {
                    requestScreenCapture()
                }
            }
        } else {
            Preferences(this).putBoolean(R.string.pref_key_lighting_was_active, false)
            stopScreenRecorder()
            mRecorderRunning = false
            finishCaptureSession("user_stop")
        }
    }

    private fun requestScreenCapture() {
        val prefs = Preferences(this)
        val method = prefs.getString(R.string.pref_key_capture_method, "media_projection")

        if (method == "accessibility") {
            if (AccessibilityCaptureService.getInstance() == null) {
                Toast.makeText(
                    this,
                    getString(R.string.accessibility_enable_prompt),
                    Toast.LENGTH_LONG
                ).show()
                openAccessibilitySettings(this)
                return
            }
        }

        if (method != "media_projection") {
            Log.d(TAG, "Alternative capture mode ($method) enabled — starting service directly")
            // startScreencapRecorder now handles all alternative methods (renaming it to startAlternativeRecorder would be cleaner, but keeping name for compatibility with existing BootActivity method)
            BootActivity.startAlternativeRecorder(this)
            mRecorderRunning = true
            beginCaptureSession(
                source = "screen",
                method = method ?: "unknown",
                protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion")
                    ?: "hyperion"
            )
            return
        }

        // On TCL and other restricted devices, try shell bypass first
        if (TclBypass.isTclDevice() || TclBypass.isRestrictedManufacturer()) {
            Log.d(TAG, "Detected TCL/restricted device, trying shell bypass")
            TclBypass.tryShellBypass(this)
        }

        // Also try general shell permissions
        PermissionHelper.tryGrantProjectMediaViaShell(this)

        // Check overlay permission on first attempt
        if (mPermissionDeniedCount == 0 && !PermissionHelper.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission first")
            AnalyticsHelper.logPermissionRequested(this, "SYSTEM_ALERT_WINDOW")
            PermissionHelper.requestOverlayPermission(this, REQUEST_OVERLAY_PERMISSION)
            return
        }

        val projectionManager = mMediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager is null; cannot request screen capture")
            return
        }

        try {
            val captureIntent = projectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } catch (e: SecurityException) {
            Log.e(TAG, "Screen capture permission denied: " + e.message)
            mPermissionDeniedCount++
            if (TclBypass.isTclDevice()) {
                TclBypass.showTclHelpDialog(this) { requestScreenCapture() }
            } else {
                PermissionHelper.showFullPermissionDialog(this) { requestScreenCapture() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request screen capture: " + e.message)
            Toast.makeText(
                this,
                "Failed to request screen recording: " + e.message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun requestCameraCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            AnalyticsHelper.logPermissionRequested(this, "CAMERA")
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CAMERA_PERMISSION
            )
        } else {
            startCameraGrabber()
        }
    }

    private fun startCameraGrabber() {
        val prefs = Preferences(this)
        val protocol = prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
        AnalyticsHelper.logProtocolStarted(this, protocol)
        AnalyticsHelper.logScreenCaptureStarted(this, "camera")

        val intent = Intent(this, ScreenGrabberService::class.java)
        intent.action = ScreenGrabberService.ACTION_START_CAMERA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        mRecorderRunning = true
        beginCaptureSession("camera", "camera", protocol)

        ReviewHelper.onLightingStarted(this)
    }

    /**
     * Before starting screen capture for Adalight, check and request USB permission.
     * For Hyperion/WLED, just execute [onReady].
     */
    private fun ensureUsbPermissionForAdalight(onReady: () -> Unit) {
        val prefs = Preferences(this)
        val connectionType =
            prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"

        if (connectionType != "adalight") {
            onReady()
            return
        }

        UsbSerialPermissionHelper.ensurePermissionForSerialDevice(
            context = this,
            device = null,
            onReady = onReady,
            onDenied = null,
            showToast = true,
            force = true
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_UPDATE_CODE) {
            if (resultCode == RESULT_OK) {
                AnalyticsHelper.logAppUpdateCompleted(this)
            } else {
                Log.e(TAG, "Update flow failed! Result code: $resultCode")
                AnalyticsHelper.logAppUpdateCancelled(this)
                // If the update is cancelled or fails,
                // you can request to start the update again.
            }
        }
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode != RESULT_OK) {
                mPermissionDeniedCount++
                mRecorderRunning = false
                if (mPermissionDeniedCount >= 2) {
                    if (TclBypass.isTclDevice()) {
                        TclBypass.showTclHelpDialog(this) { requestScreenCapture() }
                    } else {
                        PermissionHelper.showFullPermissionDialog(this) { requestScreenCapture() }
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Screen recording permission was denied. Tap again to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
            mPermissionDeniedCount = 0
            mTclWarningShown = false
            Log.i(TAG, "Starting screen capture")
            if (data != null) {
                val prefs = Preferences(this)
                val protocol =
                    prefs.getString(R.string.pref_key_connection_type, "hyperion") ?: "hyperion"
                AnalyticsHelper.logProtocolStarted(this, protocol)
                AnalyticsHelper.logScreenCaptureStarted(this, protocol)

                startScreenRecorder(resultCode, data)
                mRecorderRunning = true
                beginCaptureSession("screen", "media_projection", protocol)

                ReviewHelper.onLightingStarted(this)
            }
        }
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (PermissionHelper.canDrawOverlays(this)) {
                AnalyticsHelper.logPermissionGranted(this, "SYSTEM_ALERT_WINDOW")
            } else {
                AnalyticsHelper.logPermissionDenied(this, "SYSTEM_ALERT_WINDOW")
            }
            window.decorView.postDelayed({ requestScreenCapture() }, 500)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty()) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    AnalyticsHelper.logPermissionGranted(this, "POST_NOTIFICATIONS")
                } else {
                    AnalyticsHelper.logPermissionDenied(this, "POST_NOTIFICATIONS")
                    Toast.makeText(
                        this,
                        "Notification permission is needed for the foreground service",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AnalyticsHelper.logPermissionGranted(this, "CAMERA")
                startCameraGrabber()
            } else {
                AnalyticsHelper.logPermissionDenied(this, "CAMERA")
                Toast.makeText(
                    this,
                    getString(R.string.camera_permission_required),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkForInstance() {
        if (isServiceRunning()) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.GET_STATUS
            startService(intent)
        }
    }

    fun startScreenRecorder(resultCode: Int, data: Intent) {
        BootActivity.startScreenRecorder(this, resultCode, data)
    }

    fun stopScreenRecorder() {
        if (mRecorderRunning) {
            val intent = Intent(this, ScreenGrabberService::class.java)
            intent.action = ScreenGrabberService.ACTION_EXIT
            startService(intent)
        }
    }

    private fun checkForUpdates() {
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo
        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                // This example applies an immediate update. To apply a flexible update
                // instead, pass in AppUpdateType.FLEXIBLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Request the update
                try {
                    AnalyticsHelper.logAppUpdateRequested(this, "immediate")
                    appUpdateManager.startUpdateFlowForResult(
                        appUpdateInfo,
                        AppUpdateType.IMMEDIATE,
                        this,
                        REQUEST_UPDATE_CODE
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start update flow", e)
                }
            }
        }
    }

    private fun isServiceRunning(): Boolean = ScreenGrabberService.sInstanceRunning

    companion object {
        const val REQUEST_MEDIA_PROJECTION = 1
        private const val REQUEST_NOTIFICATION_PERMISSION = 2
        private const val REQUEST_OVERLAY_PERMISSION = 3
        private const val REQUEST_UPDATE_CODE = 4
        private const val REQUEST_CAMERA_PERMISSION = 5
        private const val TAG = "MainActivity"
        private const val PREF_NOTIF_PERMISSION_ASKED = "notif_permission_asked"
    }
}
