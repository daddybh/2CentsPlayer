package com.twocents.player.data

fun classifySkip(
    positionMs: Long,
    durationMs: Long,
): RadioFeedbackType {
    val progress = playbackProgress(positionMs, durationMs)
    return if (positionMs <= 30_000L || progress != null && progress <= 0.25) {
        RadioFeedbackType.STRONG_NEGATIVE
    } else {
        RadioFeedbackType.MILD_NEGATIVE
    }
}

fun classifyCompletion(
    positionMs: Long,
    durationMs: Long,
): RadioFeedbackType? {
    val progress = playbackProgress(positionMs, durationMs)
    return if (positionMs >= 120_000L || progress != null && progress >= 0.70) {
        RadioFeedbackType.POSITIVE
    } else {
        null
    }
}

private fun playbackProgress(
    positionMs: Long,
    durationMs: Long,
): Double? {
    if (durationMs <= 0L) return null
    return positionMs.coerceAtLeast(0L).toDouble() / durationMs.toDouble()
}
