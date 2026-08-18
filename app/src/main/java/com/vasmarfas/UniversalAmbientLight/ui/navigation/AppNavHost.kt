package com.vasmarfas.UniversalAmbientLight.ui.navigation

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.camera.CameraSetupScreen
import com.vasmarfas.UniversalAmbientLight.ui.home.EffectMode
import com.vasmarfas.UniversalAmbientLight.ui.home.HelpDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.LowRatingDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.MainScreen
import com.vasmarfas.UniversalAmbientLight.ui.home.openGitHubIssues
import com.vasmarfas.UniversalAmbientLight.ui.home.openGooglePlayReview
import com.vasmarfas.UniversalAmbientLight.ui.home.RatingDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.SupportDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.UrlDialog
import com.vasmarfas.UniversalAmbientLight.ui.led.LedLayoutScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onEffectsClick: () -> Unit,
    effectMode: EffectMode,
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            val context = LocalContext.current
            // rememberSaveable: поворот экрана не должен закрывать открытые диалоги
            var showHelpDialog by rememberSaveable { mutableStateOf(false) }
            var showSupportDialog by rememberSaveable { mutableStateOf(false) }
            var showUrlDialog by rememberSaveable { mutableStateOf<String?>(null) }
            var showRatingDialog by rememberSaveable { mutableStateOf(false) }
            var showLowRatingDialog by rememberSaveable { mutableStateOf(false) }

            val isTv = remember {
                val uiModeManager =
                    context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            }

            // Источник захвата перечитывается при возврате с экрана настроек. Именно по
            // ON_RESUME записи стека: currentBackStackEntry — не snapshot-state, и ключ по
            // нему срабатывал бы в произвольные моменты рекомпозиции.
            var captureSource by remember {
                mutableStateOf(
                    Preferences(context).getString(R.string.pref_key_capture_source, "screen")
                        ?: "screen"
                )
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        captureSource =
                            Preferences(context).getString(R.string.pref_key_capture_source, "screen")
                                ?: "screen"
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "home", "MainScreen")
            }

            MainScreen(
                isRunning = isRunning,
                onToggleClick = onToggleClick,
                // singleTop: дребезг пульта на ТВ кладёт в стек два экрана настроек подряд
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                },
                onEffectsClick = onEffectsClick,
                effectMode = effectMode,
                captureSource = captureSource,
                onHelpClick = {
                    showHelpDialog = true
                    AnalyticsHelper.logHelpDialogOpened(context)
                },
                onSupportClick = {
                    showSupportDialog = true
                    AnalyticsHelper.logSupportDialogOpened(context)
                },
                onReportIssueClick = {
                    AnalyticsHelper.logSettingChanged(context, "report_issue_clicked", "true")
                    openGitHubIssues(context)
                },
                onLeaveReviewClick = {
                    AnalyticsHelper.logSettingChanged(context, "leave_review_clicked", "true")
                    showRatingDialog = true
                }
            )

            if (showHelpDialog) {
                HelpDialog(
                    onDismiss = { showHelpDialog = false },
                    onOpenGitHub = {
                        AnalyticsHelper.logHelpLinkOpened(context)
                        val url = context.getString(R.string.help_readme_url)
                        showHelpDialog = false

                        if (isTv) {
                            showUrlDialog = url
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                showUrlDialog = url
                            } catch (e: Exception) {
                                showUrlDialog = url
                            }
                        }
                    }
                )
            }

            if (showSupportDialog) {
                SupportDialog(
                    onDismiss = { showSupportDialog = false },
                    onOpenSupport = {
                        AnalyticsHelper.logSupportLinkOpened(context)
                        val url = context.getString(R.string.support_url)
                        showSupportDialog = false

                        if (isTv) {
                            showUrlDialog = url
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                showUrlDialog = url
                            } catch (e: Exception) {
                                showUrlDialog = url
                            }
                        }
                    }
                )
            }

            // Диалог оценки
            if (showRatingDialog) {
                RatingDialog(
                    onDismiss = { showRatingDialog = false },
                    onRatingSelected = { rating ->
                        showRatingDialog = false
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "rating_selected",
                            rating.toString()
                        )
                        if (rating >= 4) {
                            openGooglePlayReview(context)
                        } else {
                            showLowRatingDialog = true
                        }
                    }
                )
            }

            if (showLowRatingDialog) {
                LowRatingDialog(
                    onDismiss = { showLowRatingDialog = false },
                    onReportIssue = {
                        showLowRatingDialog = false
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "low_rating_report_issue",
                            "true"
                        )
                        openGitHubIssues(context)
                    }
                )
            }

            val urlToShow = showUrlDialog
            if (urlToShow != null && !showHelpDialog && !showSupportDialog && !showRatingDialog && !showLowRatingDialog) {
                UrlDialog(
                    url = urlToShow,
                    onDismiss = {
                        showUrlDialog = null
                    },
                    onOpenLink = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToShow))
                        try {
                            context.startActivity(intent)
                            showUrlDialog = null
                        } catch (e: Exception) {
                            // Если ссылку открыть не удалось, диалог оставляем открытым
                        }
                    }
                )
            }

        }
        composable(Screen.Settings.route) {
            val context = LocalContext.current
            // Состояние сбрасывается само с каждой новой записью стека; ключ по
            // currentBackStackEntry здесь ловил бы чужие переходы посреди анимации
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "settings", "SettingsScreen")
            }
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLedLayoutClick = {
                    navController.navigate(Screen.LedLayout.route) { launchSingleTop = true }
                },
                onCameraSetupClick = {
                    navController.navigate(Screen.CameraSetup.route) { launchSingleTop = true }
                }
            )
        }
        composable(Screen.LedLayout.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "led_layout", "LedLayoutScreen")
                AnalyticsHelper.logLedLayoutOpened(context)
            }
            LedLayoutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.CameraSetup.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "camera_setup", "CameraSetupScreen")
            }
            CameraSetupScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
