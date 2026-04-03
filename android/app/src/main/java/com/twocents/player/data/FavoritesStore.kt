package com.twocents.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class FavoritesStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun loadFavorites(): List<Track> {
        val rawJson = preferences.getString(KEY_FAVORITES, null) ?: return emptyList()
        val array = runCatching { JSONArray(rawJson) }.getOrNull() ?: return emptyList()

        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    Track(
                        id = item.optString("id"),
                        source = TrackSource.fromStorageKey(item.optString("source")),
                        sourceId = item.optString("sourceId"),
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        album = item.optString("album"),
                        durationMs = item.optLong("durationMs"),
                        coverUrl = item.optString("coverUrl"),
                        isFavorite = true,
                    ).withCanonicalIdentity(),
                )
            }
        }.filter { it.id.isNotBlank() }
    }

    fun saveFavorites(tracks: List<Track>) {
        val array = JSONArray()
        tracks.forEach { track ->
            array.put(
                JSONObject()
                    .put("id", track.id)
                    .put("source", track.source.storageKey)
                    .put("sourceId", track.sourceTrackId())
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album)
                    .put("durationMs", track.durationMs)
                    .put("coverUrl", track.coverUrl),
            )
        }

        preferences.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "two_cents_player"
        const val KEY_FAVORITES = "favorite_tracks"
    }
}
