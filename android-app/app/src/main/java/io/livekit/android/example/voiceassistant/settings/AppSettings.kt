package io.livekit.android.example.voiceassistant.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

class AppSettings(private val context: Context) {

    companion object {
        private val CALL_MODE_KEY = stringPreferencesKey("call_mode")
        private val WAKEWORD_MODEL_KEY = stringPreferencesKey("wakeword_model")
        private val WAKEWORD_SENSITIVITY_KEY = floatPreferencesKey("wakeword_sensitivity")
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

    suspend fun setCallMode(mode: CallMode) {
        context.dataStore.edit { it[CALL_MODE_KEY] = mode.value }
    }

    suspend fun setWakeWordModel(modelId: String) {
        context.dataStore.edit { it[WAKEWORD_MODEL_KEY] = modelId }
    }

    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { it[WAKEWORD_SENSITIVITY_KEY] = sensitivity }
    }
}
