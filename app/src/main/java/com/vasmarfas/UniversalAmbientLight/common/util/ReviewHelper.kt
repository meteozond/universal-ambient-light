package com.vasmarfas.UniversalAmbientLight.common.util

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Показ диалога оценки приложения в Google Play.
 */
object ReviewHelper {
    private const val TAG = "ReviewHelper"

    private const val PREF_KEY_LAST_REVIEW_REQUEST = "last_review_request_time"
    private const val PREF_KEY_LIGHTING_START_COUNT = "lighting_start_count"
    private const val PREF_KEY_REVIEW_DISMISSED = "review_dismissed"
    private const val PREF_KEY_REVIEW_COMPLETED = "review_completed"

    private const val MIN_DAYS_BETWEEN_REQUESTS = 3L
    private const val MIN_LIGHTING_STARTS = 5

    /**
     * Увеличивает счётчик запусков подсветки и проверяет, пора ли показать диалог оценки.
     */
    fun onLightingStarted(activity: Activity) {
        val prefs = Preferences.defaultSharedPreferences(activity)

        val currentCount = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        val newCount = currentCount + 1
        prefs.edit { putInt(PREF_KEY_LIGHTING_START_COUNT, newCount) }

        if (shouldShowReviewDialog(activity)) {
            requestReview(activity)
        }
    }

    /**
     * Проверяет, стоит ли показывать диалог оценки.
     */
    private fun shouldShowReviewDialog(context: Context): Boolean {
        val prefs = Preferences.defaultSharedPreferences(context)

        val reviewCompleted = prefs.getBoolean(PREF_KEY_REVIEW_COMPLETED, false)
        if (reviewCompleted) {
            return false
        }

        // Смотрим время последнего запроса, а не флаг отказа: Google Play из-за своих квот
        // мог диалог и не показать, а мы об этом не узнаем.
        val lastRequestTime = prefs.getLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        val now = System.currentTimeMillis()
        val daysSinceLastRequest = if (lastRequestTime > 0) {
            (now - lastRequestTime) / (1000 * 60 * 60 * 24)
        } else {
            -1L
        }

        val lightingStarts = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        if (lightingStarts < MIN_LIGHTING_STARTS) {
            return false
        }

        // Флаг отказа для блокировки не используем: диалог мог не появиться из-за квот Google Play
        return lastRequestTime == 0L || daysSinceLastRequest >= MIN_DAYS_BETWEEN_REQUESTS
    }

    /**
     * Проверяет, установлено ли приложение из Google Play.
     */
    private fun isInstalledFromPlayStore(context: Context): Boolean {
        return try {
            val installer = context.packageManager.getInstallerPackageName(context.packageName)
            installer == "com.android.vending" || installer == "com.google.android.feedback"
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Запрашивает показ диалога оценки.
     * 
     * Важно: у Google Play есть квоты, и диалог может не появиться даже после launchReviewFlow,
     * поэтому время запроса сохраняем только после успешного завершения потока.
     */
    private fun requestReview(activity: Activity) {
        val reviewManager: ReviewManager = ReviewManagerFactory.create(activity)
        val prefs = Preferences.defaultSharedPreferences(activity)

        val request = reviewManager.requestReviewFlow()
        request.addOnCompleteListener { requestTask ->
            if (requestTask.isSuccessful) {
                val reviewInfo: ReviewInfo = requestTask.result

                val flow = reviewManager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Время запроса сохраняем только после завершения потока: Google Play из-за квот
                    // мог диалог не показать, но спрашивать слишком часто всё равно не стоит.
                    // dismissed=true не ставим: мы не знаем, показали диалог на самом деле или нет,
                    // и опираемся только на время последнего запроса.
                    prefs.edit {
                        putLong(PREF_KEY_LAST_REVIEW_REQUEST, System.currentTimeMillis())
                    }
                }
            } else {
                val exception = requestTask.exception
                Log.e(TAG, "Failed to request review flow: ${exception?.message}", exception)
            }
        }
    }

    /**
     * Сбрасывает флаг отказа (для тестирования).
     */
    fun resetReviewState(context: Context) {
        val prefs = Preferences.defaultSharedPreferences(context)
        prefs.edit {
            putBoolean(PREF_KEY_REVIEW_DISMISSED, false)
            putBoolean(PREF_KEY_REVIEW_COMPLETED, false)
            putLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        }
        Log.d(TAG, "Review state reset")
    }

    /**
     * Сбрасывает все данные об оценке, включая счётчик запусков (для тестирования).
     */
    fun resetAllReviewData(context: Context) {
        val prefs = Preferences.defaultSharedPreferences(context)
        prefs.edit {
            putBoolean(PREF_KEY_REVIEW_DISMISSED, false)
            putBoolean(PREF_KEY_REVIEW_COMPLETED, false)
            putLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
            putInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        }
        Log.d(TAG, "All review data reset")
    }

    /**
     * Принудительно показывает диалог оценки — только для тестирования.
     */
    fun forceShowReview(activity: Activity) {
        Log.d(TAG, "Force showing review dialog (for testing)")
        requestReview(activity)
    }

    /**
     * Возвращает текущее состояние для диагностики.
     */
    fun getReviewState(context: Context): String {
        val prefs = Preferences.defaultSharedPreferences(context)
        val lightingStarts = prefs.getInt(PREF_KEY_LIGHTING_START_COUNT, 0)
        val dismissed = prefs.getBoolean(PREF_KEY_REVIEW_DISMISSED, false)
        val completed = prefs.getBoolean(PREF_KEY_REVIEW_COMPLETED, false)
        val lastRequestTime = prefs.getLong(PREF_KEY_LAST_REVIEW_REQUEST, 0L)
        val daysSinceLastRequest = if (lastRequestTime > 0) {
            (System.currentTimeMillis() - lastRequestTime) / (1000 * 60 * 60 * 24)
        } else {
            -1L
        }

        return "Lighting starts: $lightingStarts/$MIN_LIGHTING_STARTS, " +
                "Dismissed: $dismissed, " +
                "Completed: $completed, " +
                "Days since last request: $daysSinceLastRequest/$MIN_DAYS_BETWEEN_REQUESTS"
    }
}
