package com.twocents.player.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.twocents.player.data.Track
import com.twocents.player.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp(
    viewModel: PlayerViewModel = viewModel()
) {
    val state = viewModel.playbackState
    val track = state.currentTrack
    val searchState = viewModel.searchState

    Scaffold(
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "2Cents Player",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::openSearch) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = TextPrimary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            DarkBackground,
                            DarkSurface,
                            DarkBackground,
                        )
                    )
                ),
        ) {
            // Ambient glow behind album art
            AmbientGlow(isPlaying = state.isPlaying)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                // Album Art
                AlbumArt(
                    isPlaying = state.isPlaying,
                    modifier = Modifier.weight(1f, fill = false),
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Track Info
                TrackInfo(
                    title = track?.title ?: "未选择歌曲",
                    artist = track?.artist ?: "未知艺术家",
                    album = track?.album ?: "",
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Progress Bar
                ProgressBar(
                    currentMs = state.currentPositionMs,
                    totalMs = track?.durationMs ?: 0L,
                    onSeek = viewModel::seekTo,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Playback Controls
                PlaybackControls(
                    isPlaying = state.isPlaying,
                    isFavorite = track?.isFavorite ?: false,
                    onPlayPause = viewModel::togglePlayPause,
                    onSkipNext = viewModel::skipNext,
                    onSkipPrevious = viewModel::skipPrevious,
                    onToggleFavorite = viewModel::toggleFavorite,
                )

                Spacer(modifier = Modifier.height(48.dp))
            }
        }

        if (searchState.isVisible) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = viewModel::closeSearch,
                sheetState = sheetState,
                containerColor = DarkCard,
                dragHandle = {
                    BottomSheetDefaults.DragHandle(color = TextTertiary)
                },
            ) {
                SearchSheet(
                    state = searchState,
                    currentTrackId = track?.id,
                    onQueryChange = viewModel::updateSearchQuery,
                    onSearch = viewModel::searchTracks,
                    onClose = viewModel::closeSearch,
                    onSelectTrack = viewModel::selectTrack,
                )
            }
        }
    }
}

// ─── Album Art ───────────────────────────────────────────────────────────────

@Composable
private fun AlbumArt(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rotate")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "disc_rotation",
    )

    val scale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "scale",
    )

    Box(
        modifier = modifier
            .size(280.dp)
            .scale(scale)
            .graphicsLayer {
                rotationZ = if (isPlaying) rotation else 0f
            },
        contentAlignment = Alignment.Center,
    ) {
        // Outer disc ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .shadow(24.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.sweepGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.8f),
                            AccentPink.copy(alpha = 0.6f),
                            AccentCyan.copy(alpha = 0.4f),
                            AccentPurple.copy(alpha = 0.8f),
                        )
                    )
                ),
        )

        // Inner disc surface
        Box(
            modifier = Modifier
                .size(260.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            DarkSurfaceVariant,
                            DarkSurface,
                            DarkCard,
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Groove lines (vinyl effect)
            for (i in 1..5) {
                Box(
                    modifier = Modifier
                        .size((60 + i * 36).dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .then(
                            Modifier.background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        TextTertiary.copy(alpha = 0.08f),
                                        Color.Transparent,
                                    )
                                )
                            )
                        ),
                )
            }

            // Center label
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentPurple, AccentPink),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
    }
}

// ─── Ambient Glow ───────────────────────────────────────────────────────────

@Composable
private fun AmbientGlow(isPlaying: Boolean) {
    val alpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.3f else 0.1f,
        animationSpec = tween(durationMillis = 800),
        label = "glow_alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .blur(120.dp),
    ) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-50).dp, y = 100.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = alpha),
                            Color.Transparent,
                        )
                    )
                ),
        )
        Box(
            modifier = Modifier
                .size(250.dp)
                .offset(x = 150.dp, y = 300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentPink.copy(alpha = alpha * 0.7f),
                            Color.Transparent,
                        )
                    )
                ),
        )
    }
}

// ─── Track Info ─────────────────────────────────────────────────────────────

@Composable
private fun TrackInfo(
    title: String,
    artist: String,
    album: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (album.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Progress Bar ───────────────────────────────────────────────────────────

@Composable
private fun SearchSheet(
    state: SearchUiState,
    currentTrackId: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClose: () -> Unit,
    onSelectTrack: (Track) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索网易云音乐",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭搜索",
                    tint = TextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("输入歌名 / 歌手 / 专辑") },
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
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                            color = AccentPurple,
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
                focusedBorderColor = AccentPurple,
                unfocusedBorderColor = DarkSurfaceVariant,
                focusedLabelColor = AccentPurple,
                cursorColor = AccentPurple,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            state.errorMessage != null -> {
                SearchFeedback(
                    title = "搜索失败",
                    body = state.errorMessage,
                )
            }

            state.isLoading && state.results.isEmpty() -> {
                SearchFeedback(
                    title = "正在搜索",
                    body = "稍等一下，正在从网易云网页端拉取结果。",
                )
            }

            state.hasSearched && state.results.isEmpty() -> {
                SearchFeedback(
                    title = "没有找到结果",
                    body = "换个关键词试试，比如歌名、歌手名或专辑名。",
                )
            }

            state.results.isNotEmpty() -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.results, key = { it.id }) { result ->
                        SearchResultCard(
                            track = result,
                            isCurrentTrack = result.id == currentTrackId,
                            onClick = { onSelectTrack(result) },
                        )
                    }
                }
            }

            else -> {
                SearchFeedback(
                    title = "开始搜索",
                    body = "这个入口已经接到网易云网页搜索，输入关键词后就能拿到歌曲列表。",
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun SearchFeedback(
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = DarkSurface,
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
private fun SearchResultCard(
    track: Track,
    isCurrentTrack: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = if (isCurrentTrack) DarkSurfaceVariant else DarkSurface,
        tonalElevation = if (isCurrentTrack) 6.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentPurple.copy(alpha = 0.8f), AccentPink.copy(alpha = 0.8f)),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                )
            }

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
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = formatTime(track.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrentTrack) AccentPurple else TextTertiary,
            )
        }
    }
}

@Composable
private fun ProgressBar(
    currentMs: Long,
    totalMs: Long,
    onSeek: (Long) -> Unit,
) {
    val progress = if (totalMs > 0) currentMs.toFloat() / totalMs else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = { onSeek((it * totalMs).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = AccentPurple,
                activeTrackColor = AccentPurple,
                inactiveTrackColor = DarkSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(currentMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Text(
                text = formatTime(totalMs),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

// ─── Playback Controls ─────────────────────────────────────────────────────

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isFavorite: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val favoriteColor by animateColorAsState(
        targetValue = if (isFavorite) FavoriteRed else TextTertiary,
        animationSpec = tween(300),
        label = "fav_color",
    )

    val playScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.4f),
        label = "play_scale",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Favorite button
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "收藏",
                tint = favoriteColor,
                modifier = Modifier.size(28.dp),
            )
        }

        // Previous
        IconButton(
            onClick = onSkipPrevious,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一曲",
                tint = TextPrimary,
                modifier = Modifier.size(36.dp),
            )
        }

        // Play / Pause (large center button)
        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(playScale)
                .shadow(16.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(AccentPurple, AccentPink),
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onPlayPause,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }

        // Next
        IconButton(
            onClick = onSkipNext,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一曲",
                tint = TextPrimary,
                modifier = Modifier.size(36.dp),
            )
        }

        // Spacer to balance with favorite button
        Spacer(modifier = Modifier.size(48.dp))
    }
}
