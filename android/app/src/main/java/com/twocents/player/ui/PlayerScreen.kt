package com.twocents.player.ui

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.twocents.player.data.Track
import com.twocents.player.ui.theme.AccentCoral
import com.twocents.player.ui.theme.AccentGold
import com.twocents.player.ui.theme.AccentMint
import com.twocents.player.ui.theme.AccentSky
import com.twocents.player.ui.theme.DeepOceanBackground
import com.twocents.player.ui.theme.FavoriteRed
import com.twocents.player.ui.theme.MidnightBackground
import com.twocents.player.ui.theme.SurfaceElevated
import com.twocents.player.ui.theme.SurfacePrimary
import com.twocents.player.ui.theme.SurfaceSecondary
import com.twocents.player.ui.theme.TextMuted
import com.twocents.player.ui.theme.TextPrimary
import com.twocents.player.ui.theme.TextSecondary
import com.twocents.player.ui.theme.TextTertiary

private val HeroShape = RoundedCornerShape(36.dp)
private val PanelShape = RoundedCornerShape(30.dp)
private val PillShape = RoundedCornerShape(999.dp)
private val FrameShape = RoundedCornerShape(18.dp)
private val ControlShape = RoundedCornerShape(20.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerApp(
    viewModel: PlayerViewModel = viewModel()
) {
    val playbackState = viewModel.playbackState
    val searchState = viewModel.searchState
    val favoritesState = viewModel.favoritesState
    val currentTrack = playbackState.currentTrack
    val orderedQueue = remember(playbackState.playlist, playbackState.currentIndex) {
        buildOrderedQueue(
            playlist = playbackState.playlist,
            currentIndex = playbackState.currentIndex,
        )
    }

    BindPlayer(viewModel)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MidnightBackground,
                        DeepOceanBackground,
                        MidnightBackground,
                    ),
                ),
            ),
    ) {
        PlayerBackdrop(isPlaying = playbackState.isPlaying)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PlayerHeader(
                favoriteCount = favoritesState.tracks.size,
                onOpenFavorites = viewModel::openFavorites,
                onSearch = viewModel::openSearch,
            )

            HeroArtwork(
                album = currentTrack?.album.orEmpty(),
                coverUrl = currentTrack?.coverUrl.orEmpty(),
                currentMs = playbackState.currentPositionMs,
                totalMs = currentTrack?.durationMs ?: 0L,
                isPlaying = playbackState.isPlaying,
                isPreparing = playbackState.isPreparing,
                isFavorite = currentTrack?.isFavorite == true,
                statusMessage = playbackState.statusMessage,
                currentIndex = playbackState.currentIndex,
                queueCount = playbackState.playlist.size,
                onSeek = viewModel::seekTo,
                onPlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipNext,
                onSkipPrevious = viewModel::skipPrevious,
                onToggleFavorite = viewModel::toggleFavorite,
            )

            TrackHeadline(
                title = currentTrack?.title ?: "未选择歌曲",
                artist = currentTrack?.artist ?: "未知艺术家",
                album = currentTrack?.album.orEmpty(),
                isPlaying = playbackState.isPlaying,
                currentIndex = playbackState.currentIndex,
                playlistSize = playbackState.playlist.size,
            )

            InsightStrip(
                isPlaying = playbackState.isPlaying,
                isPreparing = playbackState.isPreparing,
                currentMs = playbackState.currentPositionMs,
                totalMs = currentTrack?.durationMs ?: 0L,
                currentIndex = playbackState.currentIndex,
                playlistSize = playbackState.playlist.size,
            )

            QueueSection(
                queue = orderedQueue.take(4),
                currentIndex = playbackState.currentIndex,
                isPlaying = playbackState.isPlaying,
                onSelectTrack = viewModel::selectPlaylistTrack,
            )
        }
    }

    if (searchState.isVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeSearch,
            sheetState = sheetState,
            containerColor = SurfacePrimary,
            contentColor = TextPrimary,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = TextTertiary)
            },
        ) {
            SearchSheet(
                state = searchState,
                currentTrackId = currentTrack?.id,
                onQueryChange = viewModel::updateSearchQuery,
                onSearch = viewModel::searchTracks,
                onLoadMore = viewModel::loadMoreSearchTracks,
                onClose = viewModel::closeSearch,
                onSelectTrack = viewModel::selectTrack,
                onToggleFavorite = viewModel::toggleFavorite,
            )
        }
    }

    if (favoritesState.isVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeFavorites,
            sheetState = sheetState,
            containerColor = SurfacePrimary,
            contentColor = TextPrimary,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = TextTertiary)
            },
        ) {
            FavoritesSheet(
                state = favoritesState,
                currentTrackId = currentTrack?.id,
                onClose = viewModel::closeFavorites,
                onPlayAll = { viewModel.playFavoriteTracks(shuffle = false) },
                onShufflePlay = { viewModel.playFavoriteTracks(shuffle = true) },
                onSelectTrack = viewModel::selectFavoriteTrack,
                onToggleFavorite = viewModel::toggleFavorite,
            )
        }
    }
}

@Composable
private fun PlayerBackdrop(isPlaying: Boolean) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 0.34f else 0.18f,
        animationSpec = tween(durationMillis = 900),
        label = "backdrop_glow",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .blur(110.dp),
    ) {
        Box(
            modifier = Modifier
                .size(320.dp)
                .offset(x = (-90).dp, y = 24.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentSky.copy(alpha = glowAlpha * 0.75f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = 180.dp, y = 160.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentMint.copy(alpha = glowAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .offset(x = 80.dp, y = 520.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentCoral.copy(alpha = glowAlpha * 0.45f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun PlayerHeader(
    favoriteCount: Int,
    onOpenFavorites: () -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "CURATED SESSION",
                style = MaterialTheme.typography.labelLarge,
                color = AccentMint.copy(alpha = 0.92f),
            )
            Text(
                text = "2Cents Player",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiniInfoPill(
                icon = Icons.Default.Favorite,
                label = favoriteCount.toString(),
                onClick = onOpenFavorites,
            )
            HeaderActionButton(
                icon = Icons.Default.Search,
                contentDescription = "打开搜索",
                onClick = onSearch,
            )
        }
    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = SurfaceSecondary.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = TextPrimary,
            )
        }
    }
}

@Composable
private fun HeroArtwork(
    album: String,
    coverUrl: String,
    currentMs: Long,
    totalMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    isFavorite: Boolean,
    statusMessage: String?,
    currentIndex: Int,
    queueCount: Int,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val motionEnabled = rememberMotionEnabled()
    val rotationTransition = rememberInfiniteTransition(label = "hero_disc")
    val discRotation by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "disc_rotation",
    )
    val favoriteTint by animateColorAsState(
        targetValue = if (isFavorite) FavoriteRed else TextPrimary,
        animationSpec = tween(durationMillis = 260),
        label = "favorite_tint",
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val coverSize = (maxWidth * 0.34f).coerceIn(116.dp, 142.dp)
        val discSize = (coverSize * 1.2f).coerceIn(136.dp, 176.dp)
        val cardHeight = coverSize + 218.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight),
        ) {
            HeroDisc(
                modifier = Modifier
                    .size(discSize)
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 22.dp)
                    .graphicsLayer {
                        rotationZ = if (motionEnabled && isPlaying) discRotation else 0f
                    },
                isPlaying = isPlaying,
            )

            Box(
                modifier = Modifier
                    .height(cardHeight)
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
                    .shadow(24.dp, HeroShape)
                    .clip(HeroShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                SurfaceElevated,
                                SurfaceSecondary,
                                SurfacePrimary,
                            ),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.08f), HeroShape)
                    .padding(24.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    AccentSky.copy(alpha = 0.22f),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MetaChip(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            text = if (queueCount > 0) "SET ${formatQueuePosition(currentIndex, queueCount)}" else "SINGLE",
                        )

                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(onClick = onToggleFavorite),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = "收藏当前歌曲",
                                    tint = favoriteTint,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(coverSize)
                                .clip(RoundedCornerShape(30.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(30.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            TrackArtwork(
                                coverUrl = coverUrl,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 30.dp,
                                fallbackTint = AccentMint,
                            )
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "NOW SPINNING",
                                style = MaterialTheme.typography.labelLarge,
                                color = AccentMint.copy(alpha = 0.9f),
                            )

                            Text(
                                text = album.ifBlank { "Tonight's Selection" },
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            MetaChip(
                                icon = Icons.Default.Album,
                                text = if (queueCount > 0) "VOL ${"%02d".format(currentIndex + 1)}" else "VOL --",
                            )

                            if (statusMessage != null || isPreparing) {
                                Text(
                                    text = statusMessage ?: "正在解析音频地址，请稍等一下。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (statusMessage != null) AccentGold else TextTertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    ProgressBar(
                        currentMs = currentMs,
                        totalMs = totalMs,
                        onSeek = onSeek,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PlaybackControls(
                        isPlaying = isPlaying,
                        isPreparing = isPreparing,
                        onPlayPause = onPlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroDisc(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
) {
    Box(
        modifier = modifier
            .shadow(20.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    colors = listOf(
                        AccentMint.copy(alpha = 0.72f),
                        AccentSky.copy(alpha = 0.58f),
                        AccentGold.copy(alpha = 0.44f),
                        AccentMint.copy(alpha = 0.72f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(0.9f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SurfaceSecondary,
                            SurfacePrimary,
                            MidnightBackground,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.82f - (index * 0.12f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.06f),
                            shape = CircleShape,
                        ),
                )
            }

            Box(
                modifier = Modifier
                    .size(66.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentMint, AccentSky),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isPlaying) "LIVE" else "REST",
                    style = MaterialTheme.typography.labelLarge,
                    color = MidnightBackground,
                )
            }
        }
    }
}

@Composable
private fun TrackHeadline(
    title: String,
    artist: String,
    album: String,
    isPlaying: Boolean,
    currentIndex: Int,
    playlistSize: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.displayMedium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = artist,
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetaChip(
                icon = Icons.Default.Album,
                text = album.ifBlank { "单曲" },
            )
            MetaChip(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                text = "队列 ${formatQueuePosition(currentIndex, playlistSize)}",
            )
            MetaChip(
                icon = Icons.Default.GraphicEq,
                text = if (isPlaying) "正在播放" else "已暂停",
            )
        }
    }
}

@Composable
private fun MetaChip(
    icon: ImageVector,
    text: String,
) {
    Surface(
        shape = PillShape,
        color = SurfaceSecondary.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentSky,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun InsightStrip(
    isPlaying: Boolean,
    isPreparing: Boolean,
    currentMs: Long,
    totalMs: Long,
    currentIndex: Int,
    playlistSize: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        InsightCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.GraphicEq,
            label = "状态",
            value = when {
                isPreparing -> "准备中"
                isPlaying -> "播放中"
                else -> "暂停"
            },
        )
        InsightCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Default.Schedule,
            label = "剩余",
            value = formatTime((totalMs - currentMs).coerceAtLeast(0L)),
        )
        InsightCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.QueueMusic,
            label = "队列",
            value = formatQueuePosition(currentIndex, playlistSize),
        )
    }
}

@Composable
private fun InsightCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = SurfacePrimary.copy(alpha = 0.74f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentMint,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FrameShape,
        color = Color.Black.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PLAYBACK",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
                Text(
                    text = formatTime(currentMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                )
            }

            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { onSeek((it * totalMs).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = AccentMint,
                    activeTrackColor = AccentMint,
                    inactiveTrackColor = SurfaceSecondary.copy(alpha = 0.75f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NOW ${formatTime(currentMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Text(
                    text = "END ${formatTime(totalMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isPreparing: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.96f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 260f),
        label = "play_button_scale",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = FrameShape,
        color = Color.Black.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareControlButton(
                icon = Icons.Default.SkipPrevious,
                modifier = Modifier.weight(1f),
                contentDescription = "上一曲",
                onClick = onSkipPrevious,
            )

            Box(
                modifier = Modifier
                    .weight(1.18f)
                    .height(76.dp)
                    .scale(playScale)
                    .shadow(18.dp, ControlShape)
                    .clip(ControlShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(AccentMint, AccentSky),
                        ),
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.14f), ControlShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onPlayPause,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isPreparing) {
                    CircularProgressIndicator(
                        color = MidnightBackground,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(34.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = MidnightBackground,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            SquareControlButton(
                icon = Icons.Default.SkipNext,
                modifier = Modifier.weight(1f),
                contentDescription = "下一曲",
                onClick = onSkipNext,
            )
        }
    }
}

@Composable
private fun SquareControlButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(68.dp),
        shape = ControlShape,
        color = Color.Black.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = AccentSky,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun QueueSection(
    queue: List<Pair<Int, Track>>,
    currentIndex: Int,
    isPlaying: Boolean,
    onSelectTrack: (Int) -> Unit,
) {
    GlassPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "播放队列",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                Text(
                    text = "点一下列表就能直接切歌。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
            MiniInfoPill(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                label = queue.size.toString(),
            )
        }

        if (queue.isEmpty()) {
            SearchHintCard(
                title = "队列还是空的",
                body = "先搜一首歌或从收藏里播放，队列就会出现在这里。",
            )
        } else {
            queue.forEachIndexed { order, (trackIndex, track) ->
                QueueRow(
                    track = track,
                    trackIndex = trackIndex,
                    isCurrent = trackIndex == currentIndex,
                    isPlaying = isPlaying,
                    order = order,
                    onClick = { onSelectTrack(trackIndex) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    track: Track,
    trackIndex: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    order: Int,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val backgroundBrush = if (isCurrent) {
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
                color = if (isCurrent) AccentMint.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.06f),
                shape = shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (isCurrent) {
                            Brush.linearGradient(
                                colors = listOf(AccentMint, AccentSky),
                            )
                        } else {
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.08f),
                                    Color.White.copy(alpha = 0.04f),
                                ),
                            )
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent && isPlaying) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MidnightBackground,
                    )
                } else {
                    Text(
                        text = "%02d".format(trackIndex + 1),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isCurrent) MidnightBackground else TextPrimary,
                    )
                }
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
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = when {
                        isCurrent && isPlaying -> "当前播放"
                        isCurrent -> "当前歌曲"
                        order == 1 -> "下一首"
                        else -> "待播放"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrent) AccentMint else TextTertiary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun SearchSheet(
    state: SearchUiState,
    currentTrackId: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onClose: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    val canPaginateCurrentQuery = state.activeQuery.isNotBlank() && state.activeQuery == state.query.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "搜索网易云音乐",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "把外部搜索也放进同一套播放器体验里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭搜索",
                    tint = TextSecondary,
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
                SearchHintCard(
                    title = "搜索失败",
                    body = state.errorMessage,
                )
            }

            state.isLoading && state.results.isEmpty() -> {
                SearchHintCard(
                    title = "正在搜索",
                    body = "稍等一下，正在从网易云网页端抓取结果。",
                )
            }

            state.hasSearched && state.results.isEmpty() -> {
                SearchHintCard(
                    title = "没有找到结果",
                    body = "换个关键词试试，比如完整歌名、歌手名或专辑名。",
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
                SearchHintCard(
                    title = "开始搜索",
                    body = "这里已经接好了网易云搜索，输入关键词后就能把结果直接加入当前播放器上下文。",
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun SearchPaginationFooter(
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
private fun SearchHintCard(
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
private fun SearchResultCard(
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
            TrackArtwork(
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
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(36.dp),
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
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    text = formatTime(track.durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isCurrentTrack) AccentMint else TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun FavoritesSheet(
    state: FavoritesUiState,
    currentTrackId: String?,
    onClose: () -> Unit,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "我的收藏",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "把喜欢的歌单独拎出来，支持整组播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭收藏列表",
                    tint = TextSecondary,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionPill(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.PlayArrow,
                label = "播放全部",
                onClick = onPlayAll,
                enabled = state.tracks.isNotEmpty(),
            )
            ActionPill(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.Shuffle,
                label = "随机播放",
                onClick = onShufflePlay,
                enabled = state.tracks.isNotEmpty(),
            )
        }

        if (state.tracks.isEmpty()) {
            SearchHintCard(
                title = "收藏还是空的",
                body = "在搜索结果或播放器里点一下爱心，就会出现在这里。",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.tracks, key = { it.id }) { favorite ->
                    SearchResultCard(
                        track = favorite,
                        isCurrentTrack = favorite.id == currentTrackId,
                        onClick = { onSelectTrack(favorite) },
                        onToggleFavorite = { onToggleFavorite(favorite) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun ActionPill(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = modifier,
        shape = PillShape,
        color = if (enabled) SurfaceSecondary.copy(alpha = 0.86f) else SurfaceSecondary.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) AccentMint else TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) TextPrimary else TextMuted,
            )
        }
    }
}

@Composable
private fun TrackArtwork(
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

@Composable
private fun MiniInfoPill(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        shape = PillShape,
        color = SurfaceSecondary.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier
                .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AccentMint,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextPrimary,
            )
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceElevated.copy(alpha = 0.32f),
                            SurfaceSecondary.copy(alpha = 0.76f),
                            SurfacePrimary.copy(alpha = 0.94f),
                        ),
                    ),
                )
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun rememberMotionEnabled(): Boolean = remember {
    ValueAnimator.areAnimatorsEnabled()
}

private fun buildOrderedQueue(
    playlist: List<Track>,
    currentIndex: Int,
): List<Pair<Int, Track>> {
    if (playlist.isEmpty()) return emptyList()

    return List(playlist.size) { offset ->
        val index = (currentIndex + offset) % playlist.size
        index to playlist[index]
    }
}

private fun formatQueuePosition(
    currentIndex: Int,
    playlistSize: Int,
): String {
    if (playlistSize <= 0) return "0/0"
    return "${currentIndex + 1}/${playlistSize}"
}

private fun formatTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
