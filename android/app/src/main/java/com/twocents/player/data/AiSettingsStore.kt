package com.twocents.player.data

import android.content.Context

class AiSettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AiServiceConfig {
        return AiServiceConfig(
            endpoint = preferences.getString(KEY_AI_ENDPOINT, null).orEmpty(),
            model = preferences.getString(KEY_AI_MODEL, null).orEmpty(),
            accessKey = preferences.getString(KEY_AI_ACCESS_KEY, null).orEmpty(),
        )
    }

    fun saveSettings(settings: AiServiceConfig) {
        preferences.edit()
            .putString(KEY_AI_ENDPOINT, settings.endpoint.trim())
            .putString(KEY_AI_MODEL, settings.model.trim())
            .putString(KEY_AI_ACCESS_KEY, settings.accessKey.trim())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "two_cents_player"
        const val KEY_AI_ENDPOINT = "ai_endpoint"
        const val KEY_AI_MODEL = "ai_model"
        const val KEY_AI_ACCESS_KEY = "ai_access_key"
    }
}
