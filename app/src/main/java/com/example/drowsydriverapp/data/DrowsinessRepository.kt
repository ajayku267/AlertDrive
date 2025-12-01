package com.example.drowsydriverapp.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.drowsydriverapp.data.models.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.*

private val Context.dataStore by preferencesDataStore(name = "drowsiness_settings")
private val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")

// Alert settings keys
private val WARNING_THRESHOLD = floatPreferencesKey("alert_warning_threshold")
private val SEVERE_THRESHOLD = floatPreferencesKey("alert_severe_threshold")
private val CRITICAL_THRESHOLD = floatPreferencesKey("alert_critical_threshold")
private val SOUND_ENABLED = booleanPreferencesKey("alert_sound_enabled")
private val VIBRATION_ENABLED = booleanPreferencesKey("alert_vibration_enabled")
private val VOICE_ENABLED = booleanPreferencesKey("alert_voice_enabled")
private val ROUTE_TO_BT_MEDIA = booleanPreferencesKey("alert_route_bt_media")
private val LANGUAGE_CODE = stringPreferencesKey("alert_language_code")

class DrowsinessRepository(private val context: Context) {
    private val database = DrowsinessDatabase.getDatabase(context)
    private val drowsinessEventDao = database.drowsinessEventDao()

    val alertSettingsFlow: Flow<AlertSettings> =
        context.dataStore.data.map { prefs ->
            AlertSettings(
                warningThreshold = prefs[WARNING_THRESHOLD] ?: 0.4f,
                severeThreshold = prefs[SEVERE_THRESHOLD] ?: 0.6f,
                criticalThreshold = prefs[CRITICAL_THRESHOLD] ?: 0.8f,
                soundEnabled = prefs[SOUND_ENABLED] ?: true,
                vibrationEnabled = prefs[VIBRATION_ENABLED] ?: true,
                voiceEnabled = prefs[VOICE_ENABLED] ?: false,
                languageCode = prefs[LANGUAGE_CODE] ?: "en",
                routeToBluetoothMedia = prefs[ROUTE_TO_BT_MEDIA] ?: true
            )
        }

    suspend fun startNewSession(): String {
        val sessionId = UUID.randomUUID().toString()
        context.dataStore.edit { preferences ->
            preferences[CURRENT_SESSION_ID] = sessionId
        }
        logDrowsinessEvent(
            eventType = EventType.SESSION_START,
            sessionId = sessionId,
            eyeOpenness = 1f,
            blinkCount = 0,
            headRotationX = 0f,
            headRotationY = 0f,
            headRotationZ = 0f,
            drowsinessScore = 0f,
            confidence = 1f
        )
        return sessionId
    }

    suspend fun updateAlertSettings(transform: (AlertSettings) -> AlertSettings) {
        context.dataStore.edit { prefs ->
            val current = AlertSettings(
                warningThreshold = prefs[WARNING_THRESHOLD] ?: 0.4f,
                severeThreshold = prefs[SEVERE_THRESHOLD] ?: 0.6f,
                criticalThreshold = prefs[CRITICAL_THRESHOLD] ?: 0.8f,
                soundEnabled = prefs[SOUND_ENABLED] ?: true,
                vibrationEnabled = prefs[VIBRATION_ENABLED] ?: true,
                voiceEnabled = prefs[VOICE_ENABLED] ?: false,
                languageCode = prefs[LANGUAGE_CODE] ?: "en",
                routeToBluetoothMedia = prefs[ROUTE_TO_BT_MEDIA] ?: true
            )
            val updated = transform(current)
            prefs[WARNING_THRESHOLD] = updated.warningThreshold
            prefs[SEVERE_THRESHOLD] = updated.severeThreshold
            prefs[CRITICAL_THRESHOLD] = updated.criticalThreshold
            prefs[SOUND_ENABLED] = updated.soundEnabled
            prefs[VIBRATION_ENABLED] = updated.vibrationEnabled
            prefs[VOICE_ENABLED] = updated.voiceEnabled
            prefs[LANGUAGE_CODE] = updated.languageCode
            prefs[ROUTE_TO_BT_MEDIA] = updated.routeToBluetoothMedia
        }
    }

    fun getSessionEventsFlow(sessionId: String): Flow<List<DrowsinessEvent>> {
        return drowsinessEventDao.getSessionEvents(sessionId)
    }

    suspend fun logDrowsinessEvent(
        eventType: EventType,
        sessionId: String,
        eyeOpenness: Float,
        blinkCount: Int,
        headRotationX: Float,
        headRotationY: Float,
        headRotationZ: Float,
        drowsinessScore: Float,
        confidence: Float
    ) {
        val event = DrowsinessEvent(
            sessionId = sessionId,
            eventType = eventType,
            eyeOpenness = eyeOpenness,
            blinkCount = blinkCount,
            headRotationX = headRotationX,
            headRotationY = headRotationY,
            headRotationZ = headRotationZ,
            drowsinessScore = drowsinessScore,
            confidence = confidence
        )
        drowsinessEventDao.insertEvent(event)
    }

    suspend fun endSession(sessionId: String) {
        logDrowsinessEvent(
            eventType = EventType.SESSION_END,
            sessionId = sessionId,
            eyeOpenness = 1f,
            blinkCount = 0,
            headRotationX = 0f,
            headRotationY = 0f,
            headRotationZ = 0f,
            drowsinessScore = 0f,
            confidence = 1f
        )
    }

    suspend fun getSessionStatistics(sessionId: String): SessionStatistics {
        val drowsyEvents = drowsinessEventDao.getDrowsyEventsForSession(sessionId)
        val avgDrowsinessScore = drowsinessEventDao.getAverageDrowsinessScore(sessionId)
        val avgConfidence = drowsinessEventDao.getAverageConfidence(sessionId)

        return SessionStatistics(
            totalEvents = drowsyEvents.size,
            drowsyEvents = drowsyEvents.size,
            averageDrowsinessScore = avgDrowsinessScore,
            averageConfidence = avgConfidence
        )
    }

    suspend fun exportSessionData(sessionId: String): String {
        val events = drowsinessEventDao.getSessionEvents(sessionId)
        // Convert events to CSV or JSON format
        return events.toString() // Simplified for now
    }
}
