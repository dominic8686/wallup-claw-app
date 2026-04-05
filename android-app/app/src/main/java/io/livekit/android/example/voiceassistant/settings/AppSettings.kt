package io.livekit.android.example.voiceassistant.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hermes_settings")

enum class CallMode(val value: String) {
    MANUAL("manual"),
    WAKEWORD("wakeword");

    companion object {
        fun fromValue(value: String) = entries.find { it.value == value } ?: MANUAL
    }
}

data class WakeWordModelInfo(
    val id: String,
    val displayName: String,
    val assetPath: String,
)

val BUNDLED_MODELS = listOf(
    WakeWordModelInfo("jarvis_v2", "Hey Jarvis (v2)", "wakeword_models/jarvis_v2.onnx"),
    WakeWordModelInfo("hey_jarvis", "Hey Jarvis (v0.1)", "wakeword_models/hey_jarvis_v0.1.onnx"),
    WakeWordModelInfo("alexa", "Alexa", "wakeword_models/alexa_v0.1.onnx"),
    WakeWordModelInfo("hey_mycroft", "Hey Mycroft", "wakeword_models/hey_mycroft_v0.1.onnx"),
)

enum class UpdateInterval(val id: String, val displayName: String, val millis: Long) {
    EVERY_5MIN("every_5min", "Every 5 minutes", 300_000L),
    EVERY_15MIN("every_15min", "Every 15 minutes", 900_000L),
    EVERY_30MIN("every_30min", "Every 30 minutes", 1_800_000L),
    HOURLY("hourly", "Every hour", 3_600_000L),
    EVERY_6H("every_6h", "Every 6 hours", 21_600_000L),
    EVERY_12H("every_12h", "Every 12 hours", 43_200_000L),
    DAILY("daily", "Every day", 86_400_000L),
    WEEKLY("weekly", "Every week", 604_800_000L);

    companion object {
        fun fromId(id: String) = entries.find { it.id == id } ?: DAILY
    }
}

class AppSettings(private val context: Context) {

    companion object {
        private val CALL_MODE_KEY = stringPreferencesKey("call_mode")
        private val WAKEWORD_MODEL_KEY = stringPreferencesKey("wakeword_model")
        private val WAKEWORD_SENSITIVITY_KEY = floatPreferencesKey("wakeword_sensitivity")
        private val HA_URL_KEY = stringPreferencesKey("ha_url")
        private val HA_AUTO_DETECTED_KEY = booleanPreferencesKey("ha_auto_detected")
        private val LIVEKIT_SERVER_URL_KEY = stringPreferencesKey("livekit_server_url")
        private val TOKEN_SERVER_URL_KEY = stringPreferencesKey("token_server_url")
        private val AVATAR_ENABLED_KEY = booleanPreferencesKey("avatar_enabled")
        private val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        private val DEVICE_DISPLAY_NAME_KEY = stringPreferencesKey("device_display_name")
        private val DEVICE_ROOM_LOCATION_KEY = stringPreferencesKey("device_room_location")
        private val AUTO_UPDATE_KEY = booleanPreferencesKey("auto_update_enabled")
        private val UPDATE_INTERVAL_KEY = stringPreferencesKey("update_check_interval")
        private val LAST_UPDATE_CHECK_KEY = longPreferencesKey("last_update_check")

        const val DEFAULT_HA_URL = "http://homeassistant.local:8123"
        const val DEFAULT_LIVEKIT_URL = "ws://192.168.211.153:7880"
        const val DEFAULT_TOKEN_SERVER_URL = "http://192.168.211.153:8090"
        const val DEFAULT_DEVICE_ID = ""
        const val DEFAULT_DEVICE_DISPLAY_NAME = ""
        const val DEFAULT_DEVICE_ROOM_LOCATION = ""
    }

    val callMode: Flow<CallMode> = context.dataStore.data.map { prefs ->
        CallMode.fromValue(prefs[CALL_MODE_KEY] ?: CallMode.MANUAL.value)
    }

    val wakeWordModel: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[WAKEWORD_MODEL_KEY] ?: BUNDLED_MODELS.first().id
    }

    val wakeWordSensitivity: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[WAKEWORD_SENSITIVITY_KEY] ?: 0.5f
    }

    val haUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[HA_URL_KEY] ?: DEFAULT_HA_URL
    }

    val haAutoDetected: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[HA_AUTO_DETECTED_KEY] ?: false
    }

    val livekitServerUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[LIVEKIT_SERVER_URL_KEY] ?: DEFAULT_LIVEKIT_URL
    }

    val tokenServerUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[TOKEN_SERVER_URL_KEY] ?: DEFAULT_TOKEN_SERVER_URL
    }

    val avatarEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AVATAR_ENABLED_KEY] ?: false
    }

    val deviceId
        prefs[DEVICE_ID_KEY] ?: DEFAULT_DEVICE_ID
    }

    val deviceDisplayName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_DISPLAY_NAME_KEY] ?: DEFAULT_DEVICE_DISPLAY_NAME
    }

    val deviceRoomLocation: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DEVICE_ROOM_LOCATION_KEY] ?: DEFAULT_DEVICE_ROOM_LOCATION
    }

    val autoUpdateEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[AUTO_UPDATE_KEY] ?: false
    }

    val updateCheckInterval: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[UPDATE_INTERVAL_KEY] ?: UpdateInterval.DAILY.id
    }

    val lastUpdateCheck: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[LAST_UPDATE_CHECK_KEY] ?: 0L
    }

    suspend fun setCallMode(mode: CallMode) {
        context.dataStore.edit { it[CALL_MODE_KEY] = mode.value }
    }

    suspend fun setWakeWordModel(modelId: String) {
        context.dataStore.edit { it[WAKEWORD_MODEL_KEY] = modelId }
    }

    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { it[WAKEWORD_SENSITIVITY_KEY] = sensitivity }
    }

    suspend fun setHaUrl(url: String) {
        context.dataStore.edit { it[HA_URL_KEY] = url }
    }

    suspend fun setHaAutoDetected(detected: Boolean) {
        context.dataStore.edit { it[HA_AUTO_DETECTED_KEY] = detected }
    }

    suspend fun setLivekitServerUrl(url: String) {
        context.dataStore.edit { it[LIVEKIT_SERVER_URL_KEY] = url }
    }

    suspend fun setTokenServerUrl(url: String) {
        context.dataStore.edit { it[TOKEN_SERVER_URL_KEY] = url }
    }

    suspend fun setAvatarEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AVATAR_ENABLED_KEY] = enabled }
    }

    suspend fun setDeviceId
        context.dataStore.edit { it[DEVICE_ID_KEY] = id }
    }

    suspend fun setDeviceDisplayName(name: String) {
        context.dataStore.edit { it[DEVICE_DISPLAY_NAME_KEY] = name }
    }

    suspend fun setDeviceRoomLocation(location: String) {
        context.dataStore.edit { it[DEVICE_ROOM_LOCATION_KEY] = location }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_KEY] = enabled }
    }

    suspend fun setUpdateCheckInterval(intervalId: String) {
        context.dataStore.edit { it[UPDATE_INTERVAL_KEY] = intervalId }
    }

    suspend fun setLastUpdateCheck(timestamp: Long) {
        context.dataStore.edit { it[LAST_UPDATE_CHECK_KEY] = timestamp }
    }
}
