package com.twocents.player.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlin.math.abs

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
    val lyricsState = viewModel.lyricsState
    val aiSettingsState = viewModel.aiSettingsState
    val aiRecommendationState = viewModel.aiRecommendationState
    val isSearchPageVisible = viewModel.isSearchPageVisible
    val currentTrack = playbackState.currentTrack
    val snackbarHostState = remember { SnackbarHostState() }
    val currentRecommendationReason = remember(
        currentTrack?.id,
        aiRecommendationState.tracks,
    ) {
        aiRecommendationState.tracks
            .firstOrNull { recommendation -> recommendation.track.id == currentTrack?.id }
            ?.reason
            ?.toDisplayRecommendationReason()
    }
    val orderedQueue = remember(playbackState.playlist, playbackState.currentIndex) {
        buildOrderedQueue(
            playlist = playbackState.playlist,
            currentIndex = playbackState.currentIndex,
        )
    }

    BindPlayer(viewModel)

    LaunchedEffect(aiRecommendationState.feedbackNotice?.id) {
        val notice = aiRecommendationState.feedbackNotice ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = notice.message,
            actionLabel = "撤销",
            withDismissAction = true,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.undoLastRadioDislike(notice.trackId)
        } else {
            viewModel.clearRadioFeedbackNotice()
        }
    }

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
                onOpenAiSettings = viewModel::openAiSettings,
                onSearch = viewModel::openSearch,
            )

            HeroArtwork(
                title = currentTrack?.title ?: "未选择歌曲",
                artist = currentTrack?.artist ?: "未知艺术家",
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
                isHeartModeActive = aiRecommendationState.isActive,
                isHeartModeLoading = aiRecommendationState.isLoading || aiRecommendationState.isLoadingMore,
                heartModeStatusLabel = aiRecommendationState.statusLabel,
                isHeartModeDegraded = aiRecommendationState.isDegraded,
                heartModeErrorMessage = aiRecommendationState.errorMessage,
                hasFavorites = favoritesState.tracks.isNotEmpty(),
                recommendationReason = currentRecommendationReason,
                onSeek = viewModel::seekTo,
                onPlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipNext,
                onSkipPrevious = viewModel::skipPrevious,
                onOpenLyrics = viewModel::openLyricsScreen,
                onToggleFavorite = viewModel::toggleFavorite,
                onToggleHeartMode = if (favoritesState.tracks.isEmpty()) {
                    viewModel::openSearch
                } else {
                    viewModel::toggleHeartMode
                },
                onDislikeRecommendation = viewModel::dislikeCurrentRadioTrack,
            )

            QueueSection(
                queue = orderedQueue,
                currentIndex = playbackState.currentIndex,
                isPlaying = playbackState.isPlaying,
                onSelectTrack = viewModel::selectPlaylistTrack,
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(20.dp),
        )
    }

    if (aiSettingsState.isVisible) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = viewModel::closeAiSettings,
            sheetState = sheetState,
            containerColor = SurfacePrimary,
            contentColor = TextPrimary,
            dragHandle = {
                BottomSheetDefaults.DragHandle(color = TextTertiary)
            },
        ) {
            AiSettingsSheet(
                state = aiSettingsState,
                favoriteCount = favoritesState.tracks.size,
                onEndpointChange = viewModel::updateAiEndpoint,
                onModelChange = viewModel::updateAiModel,
                onAccessKeyChange = viewModel::updateAiAccessKey,
                onSave = viewModel::saveAiSettings,
                onClose = viewModel::closeAiSettings,
            )
        }
    }

    SearchPageLayer(
        isVisible = isSearchPageVisible,
        state = searchState,
        currentTrackId = currentTrack?.id,
        onQueryChange = viewModel::updateSearchQuery,
        onSearch = viewModel::searchTracks,
        onLoadMore = viewModel::loadMoreSearchTracks,
        onNavigateBack = viewModel::closeSearch,
        onSelectTrack = viewModel::selectTrack,
        onToggleFavorite = viewModel::toggleFavorite,
    )

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

    if (lyricsState.isVisible) {
        LyricsDetailScreen(
            state = lyricsState,
            playbackState = playbackState,
            onClose = viewModel::closeLyricsScreen,
            onSeek = viewModel::seekTo,
            onPlayPause = viewModel::togglePlayPause,
            onSkipNext = viewModel::skipNext,
            onSkipPrevious = viewModel::skipPrevious,
            onToggleFavorite = viewModel::toggleFavorite,
        )
    }
}

@Composable
private fun SearchPageLayer(
    isVisible: Boolean,
    state: SearchUiState,
    currentTrackId: String?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onNavigateBack: () -> Unit,
    onSelectTrack: (Track) -> Unit,
    onToggleFavorite: (Track) -> Unit,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> fullWidth },
        ) + fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = slideOutHorizontally(
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> fullWidth },
        ) + fadeOut(animationSpec = tween(durationMillis = 180)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MidnightBackground,
                            SurfacePrimary,
                            MidnightBackground,
                        ),
                    ),
                ),
        ) {
            SearchPage(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                state = state,
                currentTrackId = currentTrackId,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onLoadMore = onLoadMore,
                onNavigateBack = onNavigateBack,
                onSelectTrack = onSelectTrack,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun LyricsDetailScreen(
    state: LyricsUiState,
    playbackState: com.twocents.player.data.PlaybackState,
    onClose: () -> Unit,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val currentTrack = playbackState.currentTrack
    val activeLineIndex = remember(state.lines, playbackState.currentPositionMs) {
        findActiveLyricLineIndex(
            lines = state.lines,
            currentPositionMs = playbackState.currentPositionMs,
        )
    }
    val lyricListState = rememberLazyListState()

    BackHandler(onBack = onClose)

    LaunchedEffect(activeLineIndex, state.lines.size) {
        if (activeLineIndex >= 0 && state.lines.isNotEmpty()) {
            lyricListState.animateScrollToItem((activeLineIndex - 3).coerceAtLeast(0))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MidnightBackground,
                        SurfacePrimary,
                        MidnightBackground,
                    ),
                ),
            ),
    ) {
        PlayerBackdrop(isPlaying = playbackState.isPlaying)

        Box(
            modifier = Modifier
                .size(320.dp)
                .align(Alignment.TopCenter)
                .offset(y = 86.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AccentSky.copy(alpha = 0.12f),
                            AccentMint.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                    ),
                )
                .blur(56.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeaderActionButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    contentDescription = "关闭歌词界面",
                    onClick = onClose,
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = currentTrack?.title ?: "歌词界面",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentTrack?.artist ?: "未知艺术家",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.size(44.dp))
            }

            Spacer(modifier = Modifier.height(18.dp))

            if (state.credits.isNotEmpty()) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    shape = RoundedCornerShape(26.dp),
                    color = Color.White.copy(alpha = 0.055f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.credits.forEach { credit ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = PillShape,
                                    color = Color.Black.copy(alpha = 0.16f),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                                ) {
                                    Text(
                                        text = credit.role,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextTertiary,
                                    )
                                }
                                Text(
                                    text = credit.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextPrimary,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(26.dp))
            }

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AccentMint)
                    }
                }

                state.lines.isNotEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            AccentMint.copy(alpha = 0.10f),
                                            Color.Transparent,
                                        ),
                                        radius = 620f,
                                    ),
                                ),
                        )

                        LazyColumn(
                            state = lyricListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            itemsIndexed(state.lines, key = { _, line -> "${line.timestampMs}-${line.text}" }) { index, line ->
                                val distance = abs(index - activeLineIndex)
                                val isActive = index == activeLineIndex

                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isActive) {
                                        Surface(
                                            shape = RoundedCornerShape(28.dp),
                                            color = Color.White.copy(alpha = 0.04f),
                                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                                        ) {
                                            Text(
                                                text = line.text,
                                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                                textAlign = TextAlign.Center,
                                                style = MaterialTheme.typography.headlineMedium.copy(
                                                    fontSize = 30.sp,
                                                    lineHeight = 40.sp,
                                                ),
                                                color = TextPrimary,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = line.text,
                                            modifier = Modifier.fillMaxWidth(),
                                            textAlign = TextAlign.Center,
                                            style = when {
                                                distance == 1 -> MaterialTheme.typography.titleLarge.copy(lineHeight = 34.sp)
                                                else -> MaterialTheme.typography.bodyLarge.copy(lineHeight = 31.sp)
                                            },
                                            color = when {
                                                distance == 1 -> TextSecondary.copy(alpha = 0.96f)
                                                distance == 2 -> TextTertiary.copy(alpha = 0.82f)
                                                else -> TextTertiary.copy(alpha = 0.54f)
                                            },
                                            fontWeight = FontWeight.Normal,
                                        )
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .height(84.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MidnightBackground,
                                            MidnightBackground.copy(alpha = 0f),
                                        ),
                                    ),
                                ),
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(92.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            MidnightBackground.copy(alpha = 0f),
                                            MidnightBackground,
                                        ),
                                    ),
                                ),
                        )
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = state.errorMessage ?: "暂无歌词",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            LyricsBottomControls(
                currentMs = playbackState.currentPositionMs,
                totalMs = currentTrack?.durationMs ?: 0L,
                isPlaying = playbackState.isPlaying,
                isPreparing = playbackState.isPreparing,
                isFavorite = currentTrack?.isFavorite == true,
                onSeek = onSeek,
                onPlayPause = onPlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
                onToggleFavorite = onToggleFavorite,
            )
        }
    }
}

@Composable
private fun LyricsBottomControls(
    currentMs: Long,
    totalMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    isFavorite: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val progress = if (totalMs > 0) currentMs.toFloat() / totalMs else 0f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PanelShape,
        color = Color.Black.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Slider(
                value = progress.coerceIn(0f, 1f),
                onValueChange = { onSeek((it * totalMs).toLong()) },
                colors = SliderDefaults.colors(
                    thumbColor = AccentMint,
                    activeTrackColor = AccentMint,
                    inactiveTrackColor = SurfaceSecondary.copy(alpha = 0.42f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .scale(scaleX = 1f, scaleY = 0.38f),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(currentMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Text(
                    text = formatTime(totalMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactTransportButton(
                    icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) {
                        "取消收藏当前歌曲"
                    } else {
                        "收藏当前歌曲"
                    },
                    onClick = onToggleFavorite,
                )

                CompactTransportButton(
                    icon = Icons.Default.SkipPrevious,
                    contentDescription = "上一曲",
                    onClick = onSkipPrevious,
                )

                Box(
                    modifier = Modifier
                        .size(width = 74.dp, height = 50.dp)
                        .shadow(10.dp, ControlShape)
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
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = MidnightBackground,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                CompactTransportButton(
                    icon = Icons.Default.SkipNext,
                    contentDescription = "下一曲",
                    onClick = onSkipNext,
                )
            }
        }
    }
}

private fun findActiveLyricLineIndex(
    lines: List<LyricLine>,
    currentPositionMs: Long,
): Int {
    if (lines.isEmpty()) return -1
    return lines.indexOfLast { it.timestampMs <= currentPositionMs }.takeIf { it >= 0 } ?: 0
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
    onOpenAiSettings: () -> Unit,
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
                onClickLabel = "打开收藏列表",
            )
            HeaderActionButton(
                icon = Icons.Default.Settings,
                contentDescription = "打开 AI 设置",
                onClick = onOpenAiSettings,
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
    title: String,
    artist: String,
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
    isHeartModeActive: Boolean,
    isHeartModeLoading: Boolean,
    heartModeStatusLabel: String?,
    isHeartModeDegraded: Boolean,
    heartModeErrorMessage: String?,
    hasFavorites: Boolean,
    recommendationReason: String?,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleHeartMode: () -> Unit,
    onDislikeRecommendation: () -> Unit,
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
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
                    .fillMaxWidth()
                    .align(Alignment.CenterStart)
                    .padding(top = 20.dp, end = 14.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "打开歌词",
                        onClick = onOpenLyrics,
                    )
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
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable(
                                        role = Role.Button,
                                        onClick = onToggleFavorite,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFavorite) {
                                        "取消收藏当前歌曲"
                                    } else {
                                        "收藏当前歌曲"
                                    },
                                    tint = favoriteTint,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

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
                            modifier = Modifier
                                .weight(1f)
                                .height(136.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "NOW SPINNING",
                                style = MaterialTheme.typography.labelLarge,
                                color = AccentMint.copy(alpha = 0.9f),
                            )

                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            Text(
                                text = artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            MetaChip(
                                icon = Icons.Default.Album,
                                text = when {
                                    album.isNotBlank() -> album
                                    queueCount > 0 -> "队列 ${formatQueuePosition(currentIndex, queueCount)}"
                                    else -> "单曲"
                                },
                            )

                            Text(
                                text = when {
                                    statusMessage != null -> statusMessage
                                    isPreparing -> "正在解析音频地址，请稍等一下。"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (statusMessage != null) AccentGold else TextTertiary,
                                modifier = Modifier.height(18.dp),
                                minLines = 1,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    if (isHeartModeActive) {
                        Spacer(modifier = Modifier.height(14.dp))
                        RecommendationReasonBar(
                            reason = recommendationReason
                                ?.takeIf(String::isNotBlank)
                                ?: "根据你的收藏和最近播放反馈",
                            onDislike = onDislikeRecommendation,
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    PlaybackConsole(
                        currentMs = currentMs,
                        totalMs = totalMs,
                        isPlaying = isPlaying,
                        isPreparing = isPreparing,
                        onSeek = onSeek,
                        onPlayPause = onPlayPause,
                        onSkipNext = onSkipNext,
                        onSkipPrevious = onSkipPrevious,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ExplorationRadioCard(
                        isActive = isHeartModeActive,
                        isLoading = isHeartModeLoading,
                        hasFavorites = hasFavorites,
                        statusLabel = heartModeStatusLabel,
                        isDegraded = isHeartModeDegraded,
                        errorMessage = heartModeErrorMessage,
                        onClick = onToggleHeartMode,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaybackConsole(
    currentMs: Long,
    totalMs: Long,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isPlaying) "PLAYING" else "READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = { onSeek((it * totalMs).toLong()) },
                    colors = SliderDefaults.colors(
                        thumbColor = AccentMint,
                        activeTrackColor = AccentMint,
                        inactiveTrackColor = SurfaceSecondary.copy(alpha = 0.58f),
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .scale(scaleX = 1f, scaleY = 0.58f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(currentMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Text(
                    text = formatTime(totalMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
            
            CompactPlaybackControls(
                isPlaying = isPlaying,
                isPreparing = isPreparing,
                onPlayPause = onPlayPause,
                onSkipNext = onSkipNext,
                onSkipPrevious = onSkipPrevious,
            )
        }
    }
}

@Composable
private fun CompactPlaybackControls(
    isPlaying: Boolean,
    isPreparing: Boolean,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
) {
    val playScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.97f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 260f),
        label = "play_button_scale",
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactTransportButton(
            icon = Icons.Default.SkipPrevious,
            contentDescription = "上一曲",
            onClick = onSkipPrevious,
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .size(width = 74.dp, height = 52.dp)
                .scale(playScale)
                .shadow(12.dp, ControlShape)
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
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = MidnightBackground,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        CompactTransportButton(
            icon = Icons.Default.SkipNext,
            contentDescription = "下一曲",
            onClick = onSkipNext,
        )
    }
}

@Composable
private fun CompactTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(width = 48.dp, height = 44.dp),
        shape = ControlShape,
        color = Color.Black.copy(alpha = 0.12f),
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
                tint = AccentSky,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RecommendationReasonBar(
    reason: String,
    onDislike: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        shape = RoundedCornerShape(20.dp),
        color = AccentSky.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, AccentSky.copy(alpha = 0.16f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentSky.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = AccentSky,
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "推荐依据",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentSky,
                )
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "减少类似歌曲推荐",
                        onClick = onDislike,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.ThumbDown,
                    contentDescription = "不喜欢这条推荐",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ExplorationRadioCard(
    isActive: Boolean,
    isLoading: Boolean,
    hasFavorites: Boolean,
    statusLabel: String?,
    isDegraded: Boolean,
    errorMessage: String?,
    onClick: () -> Unit,
) {
    val hasError = errorMessage != null || isDegraded
    val title = when {
        isLoading -> "正在准备探索电台"
        isActive -> "探索电台播放中"
        !hasFavorites -> "从喜欢的歌开始探索"
        hasError -> "探索暂时中断"
        else -> "探索电台"
    }
    val supportingText = when {
        isLoading -> statusLabel ?: "正在挑选第一首歌"
        isActive && hasError -> errorMessage ?: statusLabel ?: "正在使用已有队列继续播放"
        isActive -> statusLabel ?: "会根据跳过和收藏持续调整"
        !hasFavorites -> "先收藏几首歌曲，推荐会更准确"
        hasError -> errorMessage ?: statusLabel ?: "可以重新生成一组歌曲"
        else -> statusLabel ?: "根据收藏生成一组连续播放的歌曲"
    }
    val actionLabel = when {
        isLoading -> ""
        isActive -> "停止"
        !hasFavorites -> "去搜索"
        hasError -> "重试"
        else -> "开始"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .semantics {
                if (hasError) {
                    liveRegion = LiveRegionMode.Polite
                }
            }
            .clickable(
                enabled = !isLoading,
                role = Role.Button,
                onClickLabel = actionLabel.takeIf(String::isNotBlank),
                onClick = onClick,
            ),
        shape = RoundedCornerShape(22.dp),
        color = when {
            isActive -> AccentMint.copy(alpha = 0.08f)
            hasError -> AccentGold.copy(alpha = 0.07f)
            else -> Color.White.copy(alpha = 0.035f)
        },
        border = BorderStroke(
            1.dp,
            when {
                isActive -> AccentMint.copy(alpha = 0.26f)
                hasError -> AccentGold.copy(alpha = 0.24f)
                else -> Color.White.copy(alpha = 0.08f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(
                        when {
                            isActive -> AccentMint.copy(alpha = 0.16f)
                            hasError -> AccentGold.copy(alpha = 0.14f)
                            else -> SurfaceSecondary.copy(alpha = 0.72f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AccentMint,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Radio,
                        contentDescription = null,
                        tint = when {
                            isActive -> AccentMint
                            hasError -> AccentGold
                            else -> AccentSky
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasError) AccentGold else TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (!isLoading) {
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = PillShape,
                    color = if (isActive) {
                        AccentMint.copy(alpha = 0.14f)
                    } else {
                        SurfaceSecondary.copy(alpha = 0.72f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (isActive) AccentMint.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
                    ),
                ) {
                    Text(
                        text = actionLabel,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isActive) AccentMint else TextPrimary,
                    )
                }
            }
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
private fun AiSettingsSheet(
    state: AiSettingsUiState,
    favoriteCount: Int,
    onEndpointChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onAccessKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var isAccessKeyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "推荐增强",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "基础推荐开箱即用，AI 配置为可选增强。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }

            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭推荐增强设置",
                    tint = TextSecondary,
                )
            }
        }

        SearchHintCard(
            title = "什么时候会使用 AI",
            body = "相似歌曲不足时，应用才会调用 AI 补充候选。不填写也可以正常使用探索电台。",
        )

        OutlinedTextField(
            value = state.endpoint,
            onValueChange = onEndpointChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("AI 接口地址（可选）")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
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
            ),
        )

        OutlinedTextField(
            value = state.model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("模型名（可选）")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
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
            ),
        )

        OutlinedTextField(
            value = state.accessKey,
            onValueChange = onAccessKeyChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Access Key（可选）")
            },
            singleLine = true,
            visualTransformation = if (isAccessKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { isAccessKeyVisible = !isAccessKeyVisible }) {
                    Icon(
                        imageVector = if (isAccessKeyVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (isAccessKeyVisible) {
                            "隐藏 Access Key"
                        } else {
                            "显示 Access Key"
                        },
                    )
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
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
            ),
        )

        if (state.missingFields().isNotEmpty()) {
            SearchHintCard(
                title = "AI 增强尚未启用",
                body = "还未填写：" +
                    state.missingFields().joinToString(separator = "、") +
                    "。这些字段可以保持为空。",
            )
        }

        if (favoriteCount == 0) {
            SearchHintCard(
                title = "还没有推荐种子",
                body = "先去搜索并收藏几首喜欢的歌，再开始探索电台。",
            )
        }

        ActionPill(
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.PlayArrow,
            label = if (favoriteCount > 0) "保存并刷新电台" else "保存设置",
            onClick = onSave,
        )

        Spacer(modifier = Modifier.height(6.dp))
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
    onClickLabel: String? = null,
) {
    Surface(
        modifier = if (onClick != null) {
            Modifier.sizeIn(minHeight = 44.dp)
        } else {
            Modifier
        },
        shape = PillShape,
        color = SurfaceSecondary.copy(alpha = 0.76f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    enabled = onClick != null,
                    role = if (onClick != null) Role.Button else null,
                    onClickLabel = onClickLabel,
                    onClick = { onClick?.invoke() },
                )
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

private fun String.toDisplayRecommendationReason(): String {
    return when (trim()) {
        "Similar to your favorites" -> "来自你的收藏相似推荐"
        else -> trim()
    }
}
