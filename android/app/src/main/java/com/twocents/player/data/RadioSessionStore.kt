package com.twocents.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class RadioSessionStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadRecommendations(): List<AiRecommendedTrack> {
        val rawJson = preferences.getString(KEY_RECOMMENDATIONS, null) ?: return emptyList()
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()

        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val trackJson = item.optJSONObject("track") ?: continue
                val track = Track(
                    id = trackJson.optString("id"),
                    source = TrackSource.fromStorageKey(trackJson.optString("source")),
                    sourceId = trackJson.optString("sourceId"),
                    title = trackJson.optString("title"),
                    artist = trackJson.optString("artist"),
                    album = trackJson.optString("album"),
                    durationMs = trackJson.optLong("durationMs"),
                    coverUrl = trackJson.optString("coverUrl"),
                ).withCanonicalIdentity()

                add(
                    AiRecommendedTrack(
                        track = track,
                        reason = item.optString("reason"),
                    ),
                )
            }
        }.filter { it.track.id.isNotBlank() }
    }

    fun saveRecommendations(recommendations: List<AiRecommendedTrack>) {
        val array = JSONArray()
        recommendations.forEach { recommendation ->
            val trackJson = JSONObject()
                .put("id", recommendation.track.id)
                .put("source", recommendation.track.source.storageKey)
                .put("sourceId", recommendation.track.sourceTrackId())
                .put("title", recommendation.track.title)
                .put("artist", recommendation.track.artist)
                .put("album", recommendation.track.album)
                .put("durationMs", recommendation.track.durationMs)
                .put("coverUrl", recommendation.track.coverUrl)

            array.put(
                JSONObject()
                    .put("track", trackJson)
                    .put("reason", recommendation.reason),
            )
        }

        preferences.edit().putString(KEY_RECOMMENDATIONS, array.toString()).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_RECOMMENDATIONS).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "two_cents_player"
        const val KEY_RECOMMENDATIONS = "radio_session_recommendations_v1"
    }
}
