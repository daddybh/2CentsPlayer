package com.twocents.player.data

const val MIN_EXPECTED_FULL_TRACK_DURATION_MS: Long = 60_000L
const val MIN_DURATION_CONSISTENCY_PERCENT: Long = 70L

fun isSuspiciousPlaybackDuration(
    catalogDurationMs: Long,
    resolvedDurationMs: Long,
): Boolean {
    if (resolvedDurationMs <= 0L) return false
    if (catalogDurationMs <= 0L) {
        return resolvedDurationMs < MIN_EXPECTED_FULL_TRACK_DURATION_MS
    }

    val isUnexpectedlyShort =
        catalogDurationMs >= MIN_EXPECTED_FULL_TRACK_DURATION_MS &&
            resolvedDurationMs < MIN_EXPECTED_FULL_TRACK_DURATION_MS
    val isLargeDurationMismatch =
        catalogDurationMs >= 90_000L &&
            resolvedDurationMs * 100L < catalogDurationMs * MIN_DURATION_CONSISTENCY_PERCENT

    return isUnexpectedlyShort || isLargeDurationMismatch
}
