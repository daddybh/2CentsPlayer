package com.twocents.player.data

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
    private val scorer: RadioCandidateScorer = RadioCandidateScorer(),
    private val logger: RadioDiagnosticLogger = NoOpRadioDiagnosticLogger,
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
        logger.debug("═══ replenish START ═══ minAppend=$minimumRequiredAppend, hasFastSource=${fastCandidateSource != null}, hasAi=${settings.isComplete}")
        var attempts = 0
        var aiRequestCount = 0
        var workingSession = session
        var suggestionCount = 0
        var appended = emptyList<RadioResolvedCandidate>()

        while (attempts < MAX_ATTEMPTS && appended.size < minimumRequiredAppend) {
            val attemptStart = System.currentTimeMillis()
            logger.debug("--- attempt #$attempts START ---")
            val planningSession = workingSession.copy(
                queuedRecommendations = workingSession.queuedRecommendations + appended.map { it.recommendation },
            )
            val plannedRequest = planner.buildRequest(favorites, history, planningSession)
            val request = if (attempts > 0) {
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
                usedSeedIds = workingSession.usedSeedIds + request.favoriteSeeds.map(Track::id),
            )
            logger.debug(
                "  [种子] " + request.favoriteSeeds.joinToString(" | ") { seed ->
                    "${seed.title} - ${seed.artist} (${seed.id})"
                }.ifBlank { "无可用种子" },
            )

            val useFastSource = attempts == 0
                && fastCandidateSource != null

            val candidateStart = System.currentTimeMillis()
            val suggestions = if (useFastSource) {
                logger.debug("  [候选] 尝试快速源...")
                val fastStart = System.currentTimeMillis()
                val fastResult = runCatching {
                    fastCandidateSource!!.requestRadioCandidates(settings, request)
                }.getOrDefault(emptyList())
                val fastMs = System.currentTimeMillis() - fastStart
                logger.debug("  [候选] 快速源返回 ${fastResult.size} 条 (${fastMs}ms)")
                if (fastResult.isEmpty() && settings.isComplete) {
                    logger.debug("  [候选] 快速源无结果，fallback 到 AI...")
                    val aiStart = System.currentTimeMillis()
                    aiRequestCount += 1
                    val aiResult = runCatching {
                        candidateSource.requestRadioCandidates(settings, request)
                    }.getOrDefault(emptyList())
                    val aiMs = System.currentTimeMillis() - aiStart
                    logger.debug("  [候选] AI fallback 返回 ${aiResult.size} 条 (${aiMs}ms)")
                    aiResult
                } else {
                    fastResult
                }
            } else if (!settings.isComplete || aiRequestCount >= MAX_AI_REQUESTS_PER_REPLENISH) {
                emptyList()
            } else {
                logger.debug("  [候选] 使用 AI 源 (attempt=$attempts)...")
                val aiStart = System.currentTimeMillis()
                aiRequestCount += 1
                val result = runCatching {
                    candidateSource.requestRadioCandidates(settings, request)
                }.getOrDefault(emptyList())
                val aiMs = System.currentTimeMillis() - aiStart
                logger.debug("  [候选] AI 源返回 ${result.size} 条 (${aiMs}ms)")
                result
            }
            val candidateMs = System.currentTimeMillis() - candidateStart
            logger.debug("  [候选] 总耗时 ${candidateMs}ms, 共 ${suggestions.size} 条建议")
            suggestionCount += suggestions.size

            val existingQueue = workingSession.queuedRecommendations + appended.map { it.recommendation }
            val resolveStart = System.currentTimeMillis()
            val newlyAppended = if (minimumRequiredAppend <= 1) {
                logger.debug("  [解析] 快速启动模式 (min=1)")
                resolveFastStartCandidates(
                    suggestions = suggestions,
                    request = request,
                    existingQueue = existingQueue,
                    boundaryState = workingSession.boundaryState,
                )
            } else {
                logger.debug("  [解析] 批量匹配模式 (${suggestions.size} 条)")
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
                                matchedSeedIds = suggestion.matchedSeedIds,
                                retrievalSources = suggestion.retrievalSources,
                            )
                        }
                    }.awaitAll().filterNotNull()
                }
                val matchMs = System.currentTimeMillis() - matchStart
                logger.debug("  [解析] 匹配到 ${matchedCandidates.size} 首 (${matchMs}ms), 跳过播放地址解析（延迟到播放时）")

                composer.compose(
                    existingQueue = existingQueue,
                    candidates = scorer.score(matchedCandidates, request),
                    boundaryState = workingSession.boundaryState,
                )
            }
            val resolveMs = System.currentTimeMillis() - resolveStart
            val attemptMs = System.currentTimeMillis() - attemptStart
            logger.debug("--- attempt #$attempts END --- 新增 ${newlyAppended.size} 首, 解析 ${resolveMs}ms, 本轮总计 ${attemptMs}ms")
            val neededCount = (minimumRequiredAppend - appended.size).coerceAtLeast(0)
            val accepted = newlyAppended.take(neededCount)
            accepted.forEach { candidate ->
                val seedLabels = request.favoriteSeeds
                    .filter { it.id in candidate.matchedSeedIds }
                    .joinToString("/") { it.title }
                    .ifBlank { "-" }
                logger.debug(
                    "  [入队] ${candidate.recommendation.track.title} - " +
                        "${candidate.recommendation.track.artist} | " +
                        "score=${candidate.score}, bucket=${candidate.bucket}, " +
                        "seeds=$seedLabels, " +
                        "sources=${candidate.retrievalSources.joinToString("/").ifBlank { "-" }}",
                )
            }
            appended = appended + accepted

            if (appended.size >= minimumRequiredAppend) {
                break
            }

            val canTryAnotherSource = settings.isComplete &&
                aiRequestCount < MAX_AI_REQUESTS_PER_REPLENISH &&
                useFastSource &&
                suggestions.isNotEmpty()
            workingSession = workingSession.copy(
                boundaryState = RadioBoundaryState.RECOVERING,
                statusLabel = RadioBoundaryState.RECOVERING.statusLabel(),
                consecutiveLowYieldCount = workingSession.consecutiveLowYieldCount + 1,
            )
            if (!canTryAnotherSource) {
                break
            }
            attempts += 1
        }

        val totalMs = System.currentTimeMillis() - replenishStart
        logger.debug("═══ replenish END ═══ appended=${appended.size}, suggestions=$suggestionCount, attempts=${attempts+1}, aiRequests=$aiRequestCount, 总耗时 ${totalMs}ms")

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
                matchedSeedIds = suggestion.matchedSeedIds,
                retrievalSources = suggestion.retrievalSources,
            )
            val scoredCandidate = scorer.score(listOf(candidate), request).firstOrNull() ?: continue

            val appended = composer.compose(
                existingQueue = existingQueue,
                candidates = listOf(scoredCandidate),
                boundaryState = boundaryState,
            )
            if (appended.isNotEmpty()) {
                logger.debug("  [快速启动] 找到候选: ${matchedTrack.title} - ${matchedTrack.artist}, hasUrl=${matchedTrack.audioUrl.isNotBlank()}")
                return appended
            }
        }

        return emptyList()
    }

    companion object {
        const val MAX_ATTEMPTS: Int = 3
        const val MAX_AI_REQUESTS_PER_REPLENISH: Int = 1
        const val MIN_SAFE_APPEND: Int = 4
    }
}
