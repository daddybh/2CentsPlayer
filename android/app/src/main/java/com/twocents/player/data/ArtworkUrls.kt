package com.twocents.player.data

private const val DEFAULT_ARTWORK_SIZE_PX = 512
private val neteaseArtworkUrlPattern = Regex(
    pattern = "^https?://p\\d+\\.music\\.126\\.net/",
    option = RegexOption.IGNORE_CASE,
)

fun String.optimizedArtworkUrl(sizePx: Int = DEFAULT_ARTWORK_SIZE_PX): String {
    val normalizedUrl = trim()
    if (normalizedUrl.isEmpty() || !neteaseArtworkUrlPattern.containsMatchIn(normalizedUrl)) {
        return normalizedUrl
    }

    val safeSizePx = sizePx.coerceIn(64, 1_024)
    return "${normalizedUrl.substringBefore('?').substringBefore('#')}?param=${safeSizePx}y$safeSizePx"
}
