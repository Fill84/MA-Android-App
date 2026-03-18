package io.musicassistant.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.common.AlbumCard
import io.musicassistant.companion.ui.common.MediaContextMenuItem
import io.musicassistant.companion.ui.common.AnimatedSection
import io.musicassistant.companion.ui.common.ArtistCard
import io.musicassistant.companion.ui.common.RadioCard
import io.musicassistant.companion.ui.common.SectionTitle
import io.musicassistant.companion.ui.common.ShimmerCard
import io.musicassistant.companion.ui.common.ShimmerTrackRow
import io.musicassistant.companion.ui.common.TrackCard
import io.musicassistant.companion.ui.common.TrackRow
import io.musicassistant.companion.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
        homeViewModel: HomeViewModel,
        playerViewModel: PlayerViewModel,
        onAlbumClick: (String) -> Unit,
        onTrackClick: (Track) -> Unit,
        onPlayerClick: (Player) -> Unit = {},
        onPlayerLongClick: (Player) -> Unit = {},
        onMediaLongClick: (MediaContextMenuItem) -> Unit = {}
) {
    val players by playerViewModel.players.collectAsState()
    val recentlyPlayed by homeViewModel.recentlyPlayed.collectAsState()
    val recentAlbums by homeViewModel.recentAlbums.collectAsState()
    val recentTracks by homeViewModel.recentTracks.collectAsState()
    val randomArtists by homeViewModel.randomArtists.collectAsState()
    val randomAlbums by homeViewModel.randomAlbums.collectAsState()
    val favoriteTracks by homeViewModel.favoriteTracks.collectAsState()
    val favoriteRadios by homeViewModel.favoriteRadios.collectAsState()
    val isLoading by homeViewModel.isLoading.collectAsState()

    val topBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            .compositeOver(MaterialTheme.colorScheme.background)

    Scaffold(
            topBar = {
                TopAppBar(
                        title = {
                            Text(
                                    "Music Assistant",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                            )
                        },
                        colors =
                                TopAppBarDefaults.topAppBarColors(
                                        containerColor = topBarColor
                                ),
                        windowInsets = androidx.compose.foundation.layout.WindowInsets(0)
                )
            },
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Gradient overlay at top
            Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp).background(
                            Brush.verticalGradient(
                                    listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                            MaterialTheme.colorScheme.background,
                                    )
                            )
                    )
            )

            if (isLoading) {
                // Shimmer loading placeholders
                Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                ) {
                    SectionTitle("Players")
                    LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(3) { ShimmerCard() }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionTitle("Recently Played")
                    LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(4) { ShimmerCard() }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    SectionTitle("Recently Added Tracks")
                    repeat(3) { ShimmerTrackRow() }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Players
                    AnimatedSection(visible = players.isNotEmpty()) {
                        Column {
                            SectionTitle("Players")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(players, key = { it.playerId }) { player ->
                                    PlayerCard(
                                            player = player,
                                            onClick = { onPlayerClick(player) },
                                            onLongClick = { onPlayerLongClick(player) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Recently Played
                    AnimatedSection(visible = recentlyPlayed.isNotEmpty()) {
                        Column {
                            SectionTitle("Recently Played")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(recentlyPlayed, key = { "rp_${it.itemId}" }) { track ->
                                    TrackCard(
                                            track = track,
                                            imageUrl =
                                                    track.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { onTrackClick(track) },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Recently Added Albums
                    AnimatedSection(visible = recentAlbums.isNotEmpty()) {
                        Column {
                            SectionTitle("Recently Added Albums")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(recentAlbums, key = { it.itemId }) { album ->
                                    AlbumCard(
                                            album = album,
                                            imageUrl =
                                                    album.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { onAlbumClick(album.itemId) },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(album.name, album.uri, MediaType.ALBUM)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Recently Added Tracks
                    AnimatedSection(visible = recentTracks.isNotEmpty()) {
                        Column {
                            SectionTitle("Recently Added Tracks")
                            Column(modifier = Modifier.padding(horizontal = 0.dp)) {
                                recentTracks.forEach { track ->
                                    TrackRow(
                                            track = track,
                                            imageUrl =
                                                    track.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { onTrackClick(track) },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                    )
                                }
                            }
                        }
                    }

                    // Random Artists
                    AnimatedSection(visible = randomArtists.isNotEmpty()) {
                        Column {
                            SectionTitle("Random Artists")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(randomArtists, key = { "ra_${it.itemId}" }) { artist ->
                                    ArtistCard(
                                            artist = artist,
                                            imageUrl =
                                                    artist.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Random Albums
                    AnimatedSection(visible = randomAlbums.isNotEmpty()) {
                        Column {
                            SectionTitle("Random Albums")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(randomAlbums, key = { "rab_${it.itemId}" }) { album ->
                                    AlbumCard(
                                            album = album,
                                            imageUrl =
                                                    album.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { onAlbumClick(album.itemId) },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(album.name, album.uri, MediaType.ALBUM)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Recently Favorited Tracks
                    AnimatedSection(visible = favoriteTracks.isNotEmpty()) {
                        Column {
                            SectionTitle("Recently Favorited Tracks")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(favoriteTracks, key = { "ft_${it.itemId}" }) { track ->
                                    TrackCard(
                                            track = track,
                                            imageUrl =
                                                    track.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { onTrackClick(track) },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    // Favorite Radio Stations
                    AnimatedSection(visible = favoriteRadios.isNotEmpty()) {
                        Column {
                            SectionTitle("Favorite Radio Stations")
                            LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(favoriteRadios, key = { "fr_${it.itemId}" }) { radio ->
                                    RadioCard(
                                            radio = radio,
                                            imageUrl =
                                                    radio.resolvedImage?.let {
                                                        homeViewModel.getImageUrl(it)
                                                    },
                                            onClick = { playerViewModel.playMedia(radio.uri, MediaType.RADIO, "play") },
                                            onLongClick = { onMediaLongClick(MediaContextMenuItem(radio.name, radio.uri, MediaType.RADIO)) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerCard(player: Player, onClick: () -> Unit, onLongClick: () -> Unit = {}) {
    val stateColor =
            when (player.state) {
                PlayerState.PLAYING -> MaterialTheme.colorScheme.primary
                PlayerState.PAUSED -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
    val stateText =
            when (player.state) {
                PlayerState.PLAYING -> "Playing"
                PlayerState.PAUSED -> "Paused"
                PlayerState.BUFFERING -> "Buffering"
                else -> "Idle"
            }

    Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.width(140.dp),
    ) {
        Column(
                modifier =
                        Modifier.combinedClickable(
                                onClick = onClick,
                                onLongClick = onLongClick
                        )
                                .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                    Icons.Default.Speaker,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = stateColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                    text = player.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            Text(
                    text = stateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor,
                    maxLines = 1
            )
        }
    }
}
