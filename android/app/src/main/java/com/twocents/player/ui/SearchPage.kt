package com.twocents.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.twocents.player.data.Track
import com.twocents.player.ui.theme.AccentCoral
import com.twocents.player.ui.theme.AccentMint
import com.twocents.player.ui.theme.AccentSky
import com.twocents.player.ui.theme.FavoriteRed
import com.twocents.player.ui.theme.MidnightBackground
import com.twocents.player.ui.theme.SurfacePrimary
import com.twocents.player.ui.theme.SurfaceSecondary
import com.twocents.player.ui.theme.TextMuted
import com.twocents.player.ui.theme.TextPrimary
import com.twocents.player.ui.theme.TextSecondary
import com.twocents.player.ui.theme.TextTertiary

@Composable
fun SearchPage(
    state: SearchUiState,
    currentTrackId: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onNavigateBack: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)

    val canPaginateCurrentQuery = state.activeQuery.isNotBlank() && state.activeQuery == state.query.trim()

    Column(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = TextSecondary,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "搜索歌曲",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "网易云和酷我会混排展示，并在结果里标注来源。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("输入歌名 / 歌手 / 专辑")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            trailingIcon = {
                IconButton(
                    onClick = onSearch,
                    enabled = state.query.isNotBlank() && !state.isLoading,
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = AccentMint,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "执行搜索",
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentMint,
                unfocusedBorderColor = TextMuted.copy(alpha = 0.65f),
                focusedLabelColor = AccentMint,
                unfocusedLabelColor = TextTertiary,
                cursorColor = AccentMint,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = SurfaceSecondary.copy(alpha = 0.64f),
                unfocusedContainerColor = SurfaceSecondary.copy(alpha = 0.38f),
                focusedTrailingIconColor = AccentMint,
                unfocusedTrailingIconColor = TextSecondary,
            ),
        )

        when {
            state.errorMessage != null -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SearchHintCard(
                        title = "搜索失败",
                        body = state.errorMessage,
                    )
                }
            }

            state.isLoading && state.results.isEmpty() -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SearchHintCard(
                        title = "正在搜索",
                        body = "稍等一下，正在聚合网易云和酷我的结果。",
                    )
                }
            }

            state.hasSearched && state.results.isEmpty() -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SearchHintCard(
                        title = "没有找到结果",
                        body = "换个关键词试试，比如完整歌名、歌手名或专辑名。",
                    )
                }
            }

            state.results.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.results, key = { it.id }) { result ->
                        SearchResultCard(
                            track = result,
                            isCurrentTrack = result.id == currentTrackId,
                            onClick = { onSelectTrack(result) },
                            onToggleFavorite = { onToggleFavorite(result) },
                        )
                    }

                    if (
                        state.isLoadingMore ||
                        state.loadMoreErrorMessage != null ||
                        canPaginateCurrentQuery
                    ) {
                        item(key = "search-pagination-footer") {
                            SearchPaginationFooter(
                                isLoadingMore = state.isLoadingMore,
                                loadMoreErrorMessage = state.loadMoreErrorMessage,
                                canLoadMore = canPaginateCurrentQuery && state.canLoadMore,
                                onRetry = onLoadMore,
                            )

                            if (
                                canPaginateCurrentQuery &&
                                state.canLoadMore &&
                                !state.isLoadingMore &&
                                state.loadMoreErrorMessage == null
                            ) {
                                LaunchedEffect(state.nextOffset) {
                                    onLoadMore()
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SearchHintCard(
                        title = "开始搜索",
                        body = "输入关键词后会混合展示网易云和酷我结果，并直接加入当前播放器上下文。",
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
fun SearchPaginationFooter(
    isLoadingMore: Boolean,
    loadMoreErrorMessage: String?,
    canLoadMore: Boolean,
    onRetry: () -> Unit,
) {
    when {
        isLoadingMore -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceSecondary.copy(alpha = 0.52f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = AccentMint,
                    )
                    Text(
                        text = "正在加载更多结果…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        }

        loadMoreErrorMessage != null -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceSecondary.copy(alpha = 0.52f),
                border = BorderStroke(1.dp, AccentCoral.copy(alpha = 0.32f)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onRetry)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "加载更多失败，点按重试",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = loadMoreErrorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
            }
        }

        canLoadMore -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceSecondary.copy(alpha = 0.40f),
            ) {
                Text(
                    text = "继续滚动，自动加载更多",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }

        else -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceSecondary.copy(alpha = 0.40f),
            ) {
                Text(
                    text = "已经到底了，没有更多结果。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

@Composable
fun SearchHintCard(
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SurfaceSecondary.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
fun SearchResultCard(
    track: Track,
    isCurrentTrack: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val backgroundBrush = if (isCurrentTrack) {
        Brush.linearGradient(
            colors = listOf(
                AccentMint.copy(alpha = 0.20f),
                AccentSky.copy(alpha = 0.10f),
            ),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                SurfaceSecondary.copy(alpha = 0.84f),
                SurfacePrimary.copy(alpha = 0.92f),
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundBrush)
            .border(
                width = 1.dp,
                color = if (isCurrentTrack) AccentMint.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SearchTrackArtwork(
                coverUrl = track.coverUrl,
                modifier = Modifier.size(56.dp),
                cornerRadius = 18.dp,
                fallbackTint = MidnightBackground,
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (track.album.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = track.album,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = Color.Black.copy(alpha = 0.16f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Text(
                        text = track.source.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentTrack) AccentMint else TextSecondary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = onToggleFavorite),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (track.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (track.isFavorite) "取消收藏" else "加入收藏",
                            tint = if (track.isFavorite) FavoriteRed else TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Text(
                    text = formatTrackDuration(track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrentTrack) AccentMint else TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun SearchTrackArtwork(
    coverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    fallbackTint: Color = MidnightBackground,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        AccentSky.copy(alpha = 0.88f),
                        AccentMint.copy(alpha = 0.88f),
                    ),
                ),
            ),
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = fallbackTint,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
    }
}

private fun formatTrackDuration(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
