package com.resumepilot.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.resumepilot.app.llm.LLMConfig
import com.resumepilot.app.llm.LLMProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "resume_pilot_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        private val LLM_PROVIDER = stringPreferencesKey("llm_provider")
        private val LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val LLM_MODEL_NAME = stringPreferencesKey("llm_model_name")
        private val LLM_TEMPERATURE = floatPreferencesKey("llm_temperature")
        private val LLM_MAX_TOKENS = intPreferencesKey("llm_max_tokens")
        private val DEFAULT_WAIT_MS = longPreferencesKey("default_wait_ms")
        private val RANDOM_OFFSET = intPreferencesKey("random_offset")
        private val MAX_STEPS_PER_TASK = intPreferencesKey("max_steps_per_task")
        private val ENABLE_OCR_FALLBACK = booleanPreferencesKey("enable_ocr_fallback")
        private val ENABLE_REPLAY_CONFIRMATION = booleanPreferencesKey("enable_replay_confirmation")
    }

    // ====== LLM 配置 ======
    val llmConfigFlow: Flow<LLMConfig> = context.dataStore.data.map { prefs ->
        LLMConfig(
            provider = LLMProvider.fromName(prefs[LLM_PROVIDER] ?: "OpenAI"),
            apiKey = prefs[LLM_API_KEY] ?: "",
            baseUrl = prefs[LLM_BASE_URL] ?: "",
            modelName = prefs[LLM_MODEL_NAME] ?: "gpt-4o",
            temperature = prefs[LLM_TEMPERATURE] ?: 0.1f,
            maxTokens = prefs[LLM_MAX_TOKENS] ?: 4096
        )
    }

    suspend fun getLLMConfig(): LLMConfig = llmConfigFlow.first()

    suspend fun saveLLMConfig(config: LLMConfig) {
        context.dataStore.edit { prefs ->
            prefs[LLM_PROVIDER] = config.provider.name
            prefs[LLM_API_KEY] = config.apiKey
            prefs[LLM_BASE_URL] = config.baseUrl
            prefs[LLM_MODEL_NAME] = config.modelName
            prefs[LLM_TEMPERATURE] = config.temperature
            prefs[LLM_MAX_TOKENS] = config.maxTokens
        }
    }

    // ====== 执行配置 ======
    val defaultWaitMs: Flow<Long> = context.dataStore.data.map { it[DEFAULT_WAIT_MS] ?: 500L }
    val randomOffset: Flow<Int> = context.dataStore.data.map { it[RANDOM_OFFSET] ?: 5 }
    val maxStepsPerTask: Flow<Int> = context.dataStore.data.map { it[MAX_STEPS_PER_TASK] ?: 50 }
    val enableOcrFallback: Flow<Boolean> = context.dataStore.data.map { it[ENABLE_OCR_FALLBACK] ?: true }
    val enableReplayConfirmation: Flow<Boolean> = context.dataStore.data.map { it[ENABLE_REPLAY_CONFIRMATION] ?: true }
}