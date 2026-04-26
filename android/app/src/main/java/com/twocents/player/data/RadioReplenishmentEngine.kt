package com.twocents.player.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class RadioReplenishmentResult(
    val appendedRecommendations: List<AiRecommendedTrack>,
    val suggestionCount: Int,
    val updatedSession: RadioSessionState,
)

class RadioReplenishmentEngine(
    private val candidateSource: RadioCandidateSource,
    private val trackLookup: RadioTrackLookup,
    private val fastCandidateSource: RadioCandidateSource? = null,
    private val planner: RadioRecommendationPlanner = RadioRecommendationPlanner(),
    private val composer: RadioQueueComposer = RadioQueueComposer(),
) {
    suspend fun replenish(
        settings: AiServiceConfig,
        favorites: List<Track>,
        history: RadioHistorySnapshot,
        session: RadioSessionState,
        minimumRequiredAppend: Int = MIN_SAFE_APPEND,
        requestTransform: (RadioRecommendationRequest) -> RadioRecommendationRequest = { it },
    ): RadioReplenishmentResult {
        val replenishStart = System.currentTimeMillis()
        Log.d(TAG, "═══ replenish START ═══ minAppend=$minimumRequiredAppend, hasLastFm=${settings.hasLastFm}, hasAi=${settings.isComplete}")
        var attempts = 0
        var workingSession = session
        var suggestionCount = 0
        var appended = emptyList<RadioResolvedCandidate>()
        var forceRecoveringRetry = false

        while (attempts < MAX_ATTEMPTS && appended.size < minimumRequiredAppend) {
            val attemptStart = System.currentTimeMillis()
            Log.d(TAG, "--- attempt #$attempts START ---")
            val planningSession = workingSession.copy(
                queuedRecommendations = workingSession.queuedRecommendations + appended.map { it.recommendation },
            )
            val plannedRequest = planner.buildRequest(favorites, history, planningSession)
            val request = if (forceRecoveringRetry) {
                plannedRequest.copy(
                    boundaryState = RadioBoundaryState.RECOVERING,
                    waveTargets = RadioWaveTargets(5, 1, 0),
                )
            } else {
                requestTransform(plannedRequest)
            }
            workingSession = workingSession.copy(
                boundaryState = request.boundaryState,
                statusLabel = request.boundaryState.statusLabel(),
            )

            val useFastSource = attempts == 0
                && !forceRecoveringRetry
                && fastCandidateSource != null
                && settings.hasLastFm

            val candidateStart = System.currentTimeMillis()
            val suggestions = if (useFastSource) {
                Log.d(TAG, "  [候选] 尝试 Last.fm 快速源...")
                val fastStart = System.currentTimeMillis()
                val fastResult = runCatching {
                    fastCandidateSource!!.requestRadioCandidates(settings, request)
                }.getOrDefault(emptyList())
                val fastMs = System.currentTimeMillis() - fastStart
                Log.d(TAG, "  [候选] Last.fm 返回 ${fastResult.size} 条 (${fastMs}ms)")
                if (fastResult.isEmpty()) {
                    Log.d(TAG, "  [候选] Last.fm 无结果，fallback 到 AI...")
                    val aiStart = System.currentTimeMillis()
                    val aiResult = runCatching {
                        candidateSource.requestRadioCandidates(settings, request)
                    }.getOrDefault(emptyList())
                    val aiMs = System.currentTimeMillis() - aiStart
                    Log.d(TAG, "  [候选] AI fallback 返回 ${aiResult.size} 条 (${aiMs}ms)")
                    aiResult
                } else {
                    fastResult
                }
            } else {
                Log.d(TAG, "  [候选] 使用 AI 源 (attempt=$attempts)...")
                val aiStart = System.currentTimeMillis()
                val result = candidateSource.requestRadioCandidates(settings, request)
                val aiMs = System.currentTimeMillis() - aiStart
                Log.d(TAG, "  [候选] AI 源返回 ${result.size} 条 (${aiMs}ms)")
                result
            }
            val candidateMs = System.currentTimeMillis() - candidateStart
            Log.d(TAG, "  [候选] 总耗时 ${candidateMs}ms, 共 ${suggestions.size} 条建议")
            suggestionCount += suggestions.size

            val existingQueue = workingSession.queuedRecommendations + appended.map { it.recommendation }
            val resolveStart = System.currentTimeMillis()
            val newlyAppended = if (minimumRequiredAppend <= 1) {
                Log.d(TAG, "  [解析] 快速启动模式 (min=1)")
                resolveFastStartCandidates(
                    suggestions = suggestions,
                    request = request,
                    existingQueue = existingQueue,
                    boundaryState = workingSession.boundaryState,
                )
            } else {
                Log.d(TAG, "  [解析] 批量匹配模式 (${suggestions.size} 条)")
                val matchStart = System.currentTimeMillis()
                val matchedCandidates = coroutineScope {
                    suggestions.map { suggestion ->
                        async(Dispatchers.IO) {
                            val matchedTrack = suggestion.resolvedTrack ?: trackLookup.findBestMatchTrack(
                                title = suggestion.title,
                                artist = suggestion.artist,
                            ) ?: return@async null

                            RadioResolvedCandidate(
                                recommendation = AiRecommendedTrack(
                                    track = matchedTrack,
                                    reason = suggestion.reason,
                                ),
                                bucket = suggestion.bucket,
                            )
                        }
                    }.awaitAll().filterNotNull()
                }.filterNot { candidate ->
                    candidate.recommendation.track.isLocallyExcluded(request)
                }
                val matchMs = System.currentTimeMillis() - matchStart
                Log.d(TAG, "  [解析] 匹配到 ${matchedCandidates.size} 首 (${matchMs}ms), 跳过播放地址解析（延迟到播放时）")

                composer.compose(
                    existingQueue = existingQueue,
                    candidates = matchedCandidates,
                    boundaryState = workingSession.boundaryState,
                )
            }
            val resolveMs = System.currentTimeMillis() - resolveStart
            val attemptMs = System.currentTimeMillis() - attemptStart
            Log.d(TAG, "--- attempt #$attempts END --- 新增 ${newlyAppended.size} 首, 解析 ${resolveMs}ms, 本轮总计 ${attemptMs}ms")
            appended = appended + newlyAppended

            if (appended.size >= minimumRequiredAppend) {
                break
            }

            workingSession = workingSession.copy(
                boundaryState = RadioBoundaryState.RECOVERING,
                statusLabel = RadioBoundaryState.RECOVERING.statusLabel(),
                consecutiveLowYieldCount = workingSession.consecutiveLowYieldCount + 1,
            )
            forceRecoveringRetry = true
            attempts += 1
        }

        val totalMs = System.currentTimeMillis() - replenishStart
        Log.d(TAG, "═══ replenish END ═══ appended=${appended.size}, suggestions=$suggestionCount, attempts=${attempts+1}, 总耗时 ${totalMs}ms")

        val nextQueue = workingSession.queuedRecommendations + appended.map { it.recommendation }

        return RadioReplenishmentResult(
            appendedRecommendations = appended.map { it.recommendation },
            suggestionCount = suggestionCount,
            updatedSession = workingSession.copy(
                queuedRecommendations = nextQueue,
                statusLabel = if (
                    appended.isEmpty() &&
                    workingSession.statusLabel == RadioBoundaryState.RECOVERING.statusLabel()
                ) {
                    RadioBoundaryState.RECOVERING.statusLabel()
                } else {
                    workingSession.statusLabel
                },
            ),
        )
    }

    private fun RadioBoundaryState.statusLabel(): String {
        return when (this) {
            RadioBoundaryState.BALANCED -> "探索中"
            RadioBoundaryState.EXPANDING -> "正在扩圈"
            RadioBoundaryState.RECOVERING -> "回到熟悉区"
        }
    }

    private fun Track.isLocallyExcluded(request: RadioRecommendationRequest): Boolean {
        return id in request.negativeTrackIds ||
            id in request.avoidTrackIds ||
            radioArtistKey() in request.avoidArtistKeys
    }

    private fun Track.radioArtistKey(): String {
        return artist
            .split(',', '、', '/', '&')
            .firstOrNull()
            .orEmpty()
            .trim()
            .lowercase()
    }

    private suspend fun resolveFastStartCandidates(
        suggestions: List<AiSuggestedTrack>,
        request: RadioRecommendationRequest,
        existingQueue: List<AiRecommendedTrack>,
        boundaryState: RadioBoundaryState,
    ): List<RadioResolvedCandidate> {
        for (suggestion in suggestions) {
            val matchedTrack = suggestion.resolvedTrack ?: trackLookup.findBestMatchTrack(
                title = suggestion.title,
                artist = suggestion.artist,
            ) ?: continue

            val candidate = RadioResolvedCandidate(
                recommendation = AiRecommendedTrack(
                    track = matchedTrack,
                    reason = suggestion.reason,
                ),
                bucket = suggestion.bucket,
            )
            if (candidate.recommendation.track.isLocallyExcluded(request)) continue

            val appended = composer.compose(
                existingQueue = existingQueue,
                candidates = listOf(candidate),
                boundaryState = boundaryState,
            )
            if (appended.isNotEmpty()) {
                Log.d(TAG, "  [快速启动] 找到候选: ${matchedTrack.title} - ${matchedTrack.artist}, hasUrl=${matchedTrack.audioUrl.isNotBlank()}")
                return appended
            }
        }

        return emptyList()
    }

    companion object {
        private const val TAG = "RadioEngine"
        const val MAX_ATTEMPTS: Int = 3
        const val MIN_SAFE_APPEND: Int = 4
    }
}
