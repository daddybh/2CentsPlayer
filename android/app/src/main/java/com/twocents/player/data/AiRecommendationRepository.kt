package com.twocents.player.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiRecommendationRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build(),
) : RadioCandidateSource {

    fun requestRecommendations(
        settings: AiServiceConfig,
        favorites: List<Track>,
        skippedTracks: List<Track> = emptyList(),
        avoidTracks: List<Track> = emptyList(),
        limit: Int = 10,
    ): List<AiSuggestedTrack> {
        if (!settings.isComplete) {
            throw IOException("AI 配置不完整，请先填写接口地址、模型和 Access Key。")
        }

        val requestBody = JSONObject()
            .put("model", settings.model.trim())
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", buildSystemPrompt(limit)),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                buildUserPrompt(
                                    favorites = favorites,
                                    skippedTracks = skippedTracks,
                                    avoidTracks = avoidTracks,
                                    limit = limit,
                                ),
                            ),
                    ),
            )

        val request = Request.Builder()
            .url(settings.chatCompletionsUrl())
            .addHeader("Authorization", "Bearer ${settings.accessKey.trim()}")
            .post(
                requestBody
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractErrorMessage(body, response.code))
            }
            if (body.isBlank()) {
                throw IOException("AI 接口返回了空响应。")
            }

            val root = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("AI 接口返回的内容不是合法 JSON。")
            }

            val content = extractAssistantContent(root)
            if (content.isBlank()) {
                throw IOException("AI 接口没有返回推荐内容。")
            }

            return parseRecommendations(content).take(limit)
        }
    }

    override fun requestRadioCandidates(
        settings: AiServiceConfig,
        request: RadioRecommendationRequest,
    ): List<AiSuggestedTrack> {
        if (!settings.isComplete) {
            throw IOException("AI 配置不完整，请先填写接口地址、模型和 Access Key。")
        }

        val requestBody = JSONObject()
            .put("model", settings.model.trim())
            .put("stream", false)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", buildRadioSystemPrompt(request)),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildRadioUserPrompt(request)),
                    ),
            )

        val networkRequest = Request.Builder()
            .url(settings.chatCompletionsUrl())
            .addHeader("Authorization", "Bearer ${settings.accessKey.trim()}")
            .post(
                requestBody
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                    .toRequestBody(JSON_MEDIA_TYPE.toMediaType()),
            )
            .build()

        client.newCall(networkRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(extractErrorMessage(body, response.code))
            }
            if (body.isBlank()) {
                throw IOException("AI 接口返回了空响应。")
            }

            val root = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("AI 接口返回的内容不是合法 JSON。")
            }

            val content = extractAssistantContent(root)
            if (content.isBlank()) {
                throw IOException("AI 接口没有返回推荐内容。")
            }

            return parseRecommendations(content).take(request.rawCandidateLimit)
        }
    }

    private fun buildSystemPrompt(limit: Int): String = """
        你是一个音乐推荐助手。
        请根据用户的收藏歌曲，推荐 $limit 首风格贴近但不要完全重复的歌曲。
        必须只返回 JSON 对象，不要 Markdown，不要解释，不要代码块。
        JSON 格式必须为：
        {"recommendations":[{"title":"歌曲名","artist":"歌手名","reason":"一句中文推荐理由"}]}
        规则：
        1. 不要推荐用户收藏里已经出现过的同名歌曲。
        2. 尽量返回正式发行版本常用的歌曲名与主要歌手名。
        3. reason 必须是简短中文，一句即可。
        4. 不要推荐用户已经明确跳过过的歌曲。
        5. 不要和“已在队列中的歌曲”重复。
    """.trimIndent()

    private fun buildUserPrompt(
        favorites: List<Track>,
        skippedTracks: List<Track>,
        avoidTracks: List<Track>,
        limit: Int,
    ): String {
        val favoriteLines = favorites.toPromptLines(MAX_PROMPT_FAVORITES)
        val skippedLines = skippedTracks.toPromptLines(MAX_PROMPT_SKIPPED_TRACKS)
        val avoidLines = avoidTracks.toPromptLines(MAX_PROMPT_AVOID_TRACKS)

        return """
            请根据下面这些收藏歌曲推荐 $limit 首新歌。
            收藏列表：
            $favoriteLines
            
            用户明确跳过过这些歌，视为负反馈，请避开：
            ${skippedLines.ifBlank { "无" }}
            
            这些歌已经在当前 AI 队列里，不要重复推荐：
            ${avoidLines.ifBlank { "无" }}
        """.trimIndent()
    }

    private fun buildRadioSystemPrompt(request: RadioRecommendationRequest): String = """
        你是一个探索电台候选生成助手。
        请返回候选歌曲并按桶位分配，目标为 safe=${request.waveTargets.safeCount} adjacent=${request.waveTargets.adjacentCount} surprise=${request.waveTargets.surpriseCount}。
        必须只返回 JSON 对象，不要 Markdown，不要解释，不要代码块。
        JSON 格式必须为：
        {"recommendations":[{"title":"歌曲名","artist":"歌手名","reason":"一句中文推荐理由","bucket":"safe|adjacent|surprise"}]}
        规则：
        1. 结合用户提供的种子和正负反馈进行推荐。
        2. 避开用户指定的 track ids 与 artist keys。
        3. reason 必须是简短中文，一句即可。
    """.trimIndent()

    private fun buildRadioUserPrompt(request: RadioRecommendationRequest): String {
        val favoriteSeedLines = request.favoriteSeeds.toPromptLines(MAX_PROMPT_FAVORITES)

        return """
            Favorite seeds:
            ${favoriteSeedLines.ifBlank { "none" }}
            
            Positive track ids:
            ${request.positiveTrackIds.joinToString(", ").ifBlank { "none" }}
            
            Negative track ids:
            ${request.negativeTrackIds.joinToString(", ").ifBlank { "none" }}
            
            Avoid track ids:
            ${request.avoidTrackIds.joinToString(", ").ifBlank { "none" }}
            
            Avoid artist keys:
            ${request.avoidArtistKeys.joinToString(", ").ifBlank { "none" }}
        """.trimIndent()
    }

    private fun List<Track>.toPromptLines(limit: Int): String {
        return take(limit)
            .mapIndexed { index, track ->
                buildString {
                    append(index + 1)
                    append(". ")
                    append(track.title.ifBlank { "未知歌曲" })
                    append(" - ")
                    append(track.artist.ifBlank { "未知歌手" })
                    if (track.album.isNotBlank()) {
                        append(" | 专辑: ")
                        append(track.album)
                    }
                }
            }
            .joinToString(separator = "\n")
    }

    private fun extractAssistantContent(root: JSONObject): String {
        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw IOException("AI 接口返回缺少 choices 字段。")
        }

        val message = choices.optJSONObject(0)?.optJSONObject("message")
        val contentValue = message?.opt("content")

        return when (contentValue) {
            is String -> contentValue.trim()
            is JSONArray -> buildString {
                for (index in 0 until contentValue.length()) {
                    val item = contentValue.opt(index)
                    when (item) {
                        is String -> {
                            if (item.isNotBlank()) append(item)
                        }

                        is JSONObject -> {
                            val text = when (val textValue = item.opt("text")) {
                                is String -> textValue
                                is JSONObject -> textValue.optString("value")
                                else -> item.optString("content")
                            }
                            if (text.isNotBlank()) append(text)
                        }
                    }
                }
            }.trim()

            else -> ""
        }
    }

    private fun parseRecommendations(rawContent: String): List<AiSuggestedTrack> {
        val normalizedContent = rawContent
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val payload = sequenceOf(
            normalizedContent,
            extractJsonObject(normalizedContent),
        ).mapNotNull { candidate ->
            candidate?.takeIf { it.isNotBlank() }?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }
        }.firstOrNull() ?: throw IOException("AI 返回的推荐内容不是可解析的 JSON。")

        val items = payload.optJSONArray("recommendations")
            ?: payload.optJSONArray("tracks")
            ?: payload.optJSONArray("songs")
            ?: throw IOException("AI 返回缺少 recommendations 字段。")

        val seenKeys = mutableSetOf<String>()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                val artist = item.optString("artist").trim()
                val reason = item.optString("reason").trim()
                val bucket = when (item.optString("bucket").trim().lowercase()) {
                    "adjacent" -> RadioCandidateBucket.ADJACENT
                    "surprise" -> RadioCandidateBucket.SURPRISE
                    else -> RadioCandidateBucket.SAFE
                }
                if (title.isBlank() || artist.isBlank()) continue

                val dedupeKey = "${title.lowercase()}::${artist.lowercase()}"
                if (!seenKeys.add(dedupeKey)) continue

                add(
                    AiSuggestedTrack(
                        title = title,
                        artist = artist,
                        reason = reason,
                        bucket = bucket,
                    ),
                )
            }
        }
    }

    private fun extractJsonObject(content: String): String? {
        val startIndex = content.indexOf('{')
        val endIndex = content.lastIndexOf('}')
        if (startIndex == -1 || endIndex <= startIndex) return null
        return content.substring(startIndex, endIndex + 1)
    }

    private fun extractErrorMessage(
        rawBody: String,
        code: Int,
    ): String {
        if (rawBody.isBlank()) {
            return "AI 接口请求失败: HTTP $code"
        }

        val root = runCatching { JSONObject(rawBody) }.getOrNull()
        val message = root
            ?.optJSONObject("error")
            ?.optString("message")
            .orEmpty()
            .trim()

        return if (message.isNotBlank()) {
            "AI 接口请求失败: $message"
        } else {
            "AI 接口请求失败: HTTP $code"
        }
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val MAX_PROMPT_FAVORITES = 30
        const val MAX_PROMPT_SKIPPED_TRACKS = 20
        const val MAX_PROMPT_AVOID_TRACKS = 40
    }
}
