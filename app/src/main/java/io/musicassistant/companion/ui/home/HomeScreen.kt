package io.musicassistant.companion.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.Player
import io.musicassistant.companion.data.model.PlayerState
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
        homeViewModel: HomeViewModel,
        playerViewModel: PlayerViewModel,
        onAlbumClick: (String) -> Unit,
        onTrackClick: (Track) -> Unit
) {
    val players by homeViewModel.players.collectAsState()
    val recentlyPlayed by homeViewModel.recentlyPlayed.collectAsState()
    val recentAlbums by homeViewModel.recentAlbums.collectAsState()
    val recentTracks by homeViewModel.recentTracks.collectAsState()
    val randomArtists by homeViewModel.randomArtists.collectAsState()
    val randomAlbums by homeViewModel.randomAlbums.collectAsState()
    val favoriteTracks by homeViewModel.favoriteTracks.collectAsState()
    val favoriteRadios by homeViewModel.favoriteRadios.collectAsState()
    val isLoading by homeViewModel.isLoading.collectAsState()

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("Home", fontWeight = FontWeight.Bold) },
                        colors =
                                TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                )
                )
            }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // ── Players ─────────────────────────────────
                if (players.isNotEmpty()) {
                    SectionTitle("Players")
                    LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(players, key = { it.playerId }) { player ->
                            PlayerCard(player = player)
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Recently Played ─────────────────────────
                if (recentlyPlayed.isNotEmpty()) {
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
                                    onClick = { onTrackClick(track) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Recently Added Albums ───────────────────
                if (recentAlbums.isNotEmpty()) {
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
                                    onClick = { onAlbumClick(album.itemId) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Recently Added Tracks ───────────────────
                if (recentTracks.isNotEmpty()) {
                    SectionTitle("Recently Added Tracks")
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        recentTracks.forEach { track ->
                            TrackRow(
                                    track = track,
                                    imageUrl =
                                            track.resolvedImage?.let {
                                                homeViewModel.getImageUrl(it)
                                            },
                                    onClick = { onTrackClick(track) }
                            )
                        }
                    }
                }

                // ── Random Artists ───────────────────────────
                if (randomArtists.isNotEmpty()) {
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

                // ── Random Albums ────────────────────────────
                if (randomAlbums.isNotEmpty()) {
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
                                    onClick = { onAlbumClick(album.itemId) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Recently Favorited Tracks ────────────────
                if (favoriteTracks.isNotEmpty()) {
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
                                    onClick = { onTrackClick(track) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Favorite Radio Stations ──────────────────
                if (favoriteRadios.isNotEmpty()) {
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
                                            }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
            if (isLoading) {
                Box(
                        modifier =
                                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun PlayerCard(player: Player) {
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

    Column(
            modifier =
                    Modifier.width(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
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

@Composable
private fun TrackCard(track: Track, imageUrl: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
                modifier =
                        Modifier.size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = track.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
                text = track.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        val artistName = track.artists.firstOrNull()?.name
        if (artistName != null) {
            Text(
                    text = artistName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AlbumCard(album: Album, imageUrl: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable(onClick = onClick)) {
        Box(
                modifier =
                        Modifier.size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = album.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.Album,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
                text = album.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
        val artistName = album.artists.firstOrNull()?.name
        if (artistName != null) {
            Text(
                    text = artistName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, imageUrl: String?, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = track.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            val artistName = track.artists.firstOrNull()?.name
            if (artistName != null) {
                Text(
                        text = artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
                text = formatDuration(track.duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
private fun ArtistCard(artist: Artist, imageUrl: String?) {
    Column(modifier = Modifier.width(140.dp)) {
        Box(
                modifier =
                        Modifier.size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = artist.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
                text = artist.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RadioCard(radio: Radio, imageUrl: String?) {
    Column(modifier = Modifier.width(140.dp)) {
        Box(
                modifier =
                        Modifier.size(140.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = radio.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
                text = radio.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}
