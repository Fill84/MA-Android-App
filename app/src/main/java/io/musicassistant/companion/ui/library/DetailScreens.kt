package io.musicassistant.companion.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.common.ImagePlaceholder
import io.musicassistant.companion.ui.common.MediaContextMenuItem
import io.musicassistant.companion.ui.common.SectionTitle
import io.musicassistant.companion.ui.common.ShimmerTrackRow
import io.musicassistant.companion.ui.common.TrackRow
import io.musicassistant.companion.ui.common.formatDuration
import io.musicassistant.companion.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
        artistId: String,
        libraryViewModel: LibraryViewModel,
        playerViewModel: PlayerViewModel,
        onAlbumClick: (String) -> Unit,
        onBack: () -> Unit,
        onMediaLongClick: (MediaContextMenuItem) -> Unit = {}
) {
        val artist by libraryViewModel.artistDetail.collectAsState()
        val albums by libraryViewModel.artistAlbums.collectAsState()
        val tracks by libraryViewModel.artistTracks.collectAsState()
        val isLoading by libraryViewModel.artistDetailLoading.collectAsState()

        LaunchedEffect(artistId) { libraryViewModel.loadArtistDetail(artistId) }
        val topBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                .compositeOver(MaterialTheme.colorScheme.background)

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = {
                                        Text(
                                                artist?.name ?: "",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                        )
                                },
                                navigationIcon = {
                                        IconButton(onClick = onBack) {
                                                Icon(
                                                        Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back"
                                                )
                                        }
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
                        if (artist != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Artist header with gradient backdrop
                                        item {
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                        Box(
                                                                modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(220.dp)
                                                                        .background(
                                                                                Brush.verticalGradient(
                                                                                        listOf(
                                                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                                                                MaterialTheme.colorScheme.background,
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                                        Column(
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .padding(24.dp),
                                                                horizontalAlignment =
                                                                        Alignment.CenterHorizontally
                                                        ) {
                                                                val imageUrl =
                                                                        artist?.resolvedImage?.let {
                                                                                libraryViewModel
                                                                                        .getImageUrl(it)
                                                                        }
                                                                Box(
                                                                        modifier =
                                                                                Modifier.size(160.dp)
                                                                                        .shadow(16.dp, CircleShape)
                                                                                        .clip(CircleShape)
                                                                                        .background(
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .surfaceVariant
                                                                                        ),
                                                                        contentAlignment = Alignment.Center
                                                                ) {
                                                                        if (imageUrl != null) {
                                                                                AsyncImage(
                                                                                        model = imageUrl,
                                                                                        contentDescription =
                                                                                                null,
                                                                                        modifier =
                                                                                                Modifier.size(
                                                                                                        160.dp
                                                                                                ),
                                                                                        contentScale =
                                                                                                ContentScale
                                                                                                        .Crop
                                                                                )
                                                                        } else {
                                                                                ImagePlaceholder(
                                                                                        icon = Icons.Default.Person,
                                                                                        size = 160.dp,
                                                                                        shape = CircleShape,
                                                                                )
                                                                        }
                                                                }
                                                                Spacer(modifier = Modifier.height(16.dp))
                                                                Text(
                                                                        text = artist?.name ?: "",
                                                                        style =
                                                                                MaterialTheme.typography
                                                                                        .headlineSmall,
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                                Spacer(modifier = Modifier.height(12.dp))
                                                                PlayAllButton(
                                                                        text = "Play All",
                                                                        onClick = {
                                                                                artist?.uri?.let {
                                                                                        playerViewModel
                                                                                                .playMedia(
                                                                                                        it,
                                                                                                        MediaType.ARTIST,
                                                                                                        "play"
                                                                                                )
                                                                                }
                                                                        }
                                                                )
                                                        }
                                                }
                                        }

                                        // Albums section
                                        if (albums.isNotEmpty()) {
                                                item { SectionTitle("Albums") }
                                                item {
                                                        LazyRow(
                                                                contentPadding =
                                                                        PaddingValues(
                                                                                horizontal = 16.dp
                                                                        ),
                                                                horizontalArrangement =
                                                                        Arrangement.spacedBy(12.dp)
                                                        ) {
                                                                items(
                                                                        albums,
                                                                        key = { it.itemId }
                                                                ) { album ->
                                                                        AlbumCard(
                                                                                album = album,
                                                                                imageUrl =
                                                                                        album.resolvedImage
                                                                                                ?.let {
                                                                                                        libraryViewModel
                                                                                                                .getImageUrl(
                                                                                                                        it
                                                                                                                )
                                                                                                },
                                                                                onClick = {
                                                                                        onAlbumClick(
                                                                                                album.itemId
                                                                                        )
                                                                                }
                                                                        )
                                                                }
                                                        }
                                                }
                                        }

                                        // Top tracks section
                                        if (tracks.isNotEmpty()) {
                                                item { SectionTitle("Top Tracks") }
                                                items(tracks.take(10), key = { it.itemId }) { track
                                                        ->
                                                        TrackRow(
                                                                track = track,
                                                                imageUrl =
                                                                        track.resolvedImage?.let {
                                                                                libraryViewModel
                                                                                        .getImageUrl(
                                                                                                it
                                                                                        )
                                                                        },
                                                                onClick = {
                                                                        playerViewModel.playMedia(
                                                                                track.uri,
                                                                                MediaType.TRACK,
                                                                                "play"
                                                                        )
                                                                },
                                                                onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                                        )
                                                }
                                        }
                                }
                        }
                        if (isLoading) {
                                LoadingOverlay()
                        }
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
        albumId: String,
        libraryViewModel: LibraryViewModel,
        playerViewModel: PlayerViewModel,
        onBack: () -> Unit,
        onMediaLongClick: (MediaContextMenuItem) -> Unit = {}
) {
        val album by libraryViewModel.albumDetail.collectAsState()
        val tracks by libraryViewModel.albumTracks.collectAsState()
        val isLoading by libraryViewModel.albumDetailLoading.collectAsState()
        val queue by playerViewModel.queue.collectAsState()
        val isPlaying by playerViewModel.isPlaying.collectAsState()

        val currentTrackUri = queue?.currentItem?.mediaItem?.uri

        LaunchedEffect(albumId) { libraryViewModel.loadAlbumDetail(albumId) }
        val topBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                .compositeOver(MaterialTheme.colorScheme.background)

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = {
                                        Text(
                                                album?.name ?: "",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                        )
                                },
                                navigationIcon = {
                                        IconButton(onClick = onBack) {
                                                Icon(
                                                        Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back"
                                                )
                                        }
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
                        if (album != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Album header with gradient
                                        item {
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                        Box(
                                                                modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(260.dp)
                                                                        .background(
                                                                                Brush.verticalGradient(
                                                                                        listOf(
                                                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                                                                                MaterialTheme.colorScheme.background,
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                                        Column(
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .padding(24.dp),
                                                                horizontalAlignment =
                                                                        Alignment.CenterHorizontally
                                                        ) {
                                                                val imageUrl =
                                                                        album?.resolvedImage?.let {
                                                                                libraryViewModel
                                                                                        .getImageUrl(it)
                                                                        }
                                                                Box(
                                                                        modifier =
                                                                                Modifier.size(200.dp)
                                                                                        .shadow(16.dp, RoundedCornerShape(16.dp))
                                                                                        .clip(
                                                                                                RoundedCornerShape(
                                                                                                        16.dp
                                                                                                )
                                                                                        )
                                                                                        .background(
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .surfaceVariant
                                                                                        ),
                                                                        contentAlignment = Alignment.Center
                                                                ) {
                                                                        if (imageUrl != null) {
                                                                                AsyncImage(
                                                                                        model = imageUrl,
                                                                                        contentDescription =
                                                                                                null,
                                                                                        modifier =
                                                                                                Modifier.fillMaxSize(),
                                                                                        contentScale =
                                                                                                ContentScale
                                                                                                        .Crop
                                                                                )
                                                                        } else {
                                                                                ImagePlaceholder(
                                                                                        icon = Icons.Default.Album,
                                                                                        size = 200.dp,
                                                                                        shape = RoundedCornerShape(16.dp),
                                                                                )
                                                                        }
                                                                }
                                                                Spacer(modifier = Modifier.height(16.dp))
                                                                Text(
                                                                        text = album?.name ?: "",
                                                                        style =
                                                                                MaterialTheme.typography
                                                                                        .headlineSmall,
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                                val artistName =
                                                                        album?.artists?.firstOrNull()?.name
                                                                if (artistName != null) {
                                                                        Text(
                                                                                text = artistName,
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyLarge,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                                album?.year?.let { year ->
                                                                        Text(
                                                                                text = year.toString(),
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodySmall,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                                Spacer(modifier = Modifier.height(12.dp))
                                                                PlayAllButton(
                                                                        text = "Play Album",
                                                                        onClick = {
                                                                                album?.uri?.let {
                                                                                        playerViewModel
                                                                                                .playMedia(
                                                                                                        it,
                                                                                                        MediaType.ALBUM,
                                                                                                        "play"
                                                                                                )
                                                                                }
                                                                        }
                                                                )
                                                        }
                                                }
                                        }

                                        // Track list
                                        items(tracks, key = { it.itemId }) { track ->
                                                val isCurrentTrack = currentTrackUri == track.uri
                                                AlbumTrackRow(
                                                        track = track,
                                                        isCurrentTrack = isCurrentTrack,
                                                        isPlaying = isCurrentTrack && isPlaying,
                                                        onClick = {
                                                                if (isCurrentTrack) {
                                                                        if (isPlaying)
                                                                                playerViewModel
                                                                                        .pause()
                                                                        else playerViewModel.play()
                                                                } else {
                                                                        playerViewModel.playMedia(
                                                                                track.uri,
                                                                                MediaType.TRACK,
                                                                                "play"
                                                                        )
                                                                }
                                                        },
                                                        onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                                )
                                        }
                                }
                        }
                        if (isLoading) {
                                LoadingOverlay()
                        }
                }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
        playlistId: String,
        libraryViewModel: LibraryViewModel,
        playerViewModel: PlayerViewModel,
        onBack: () -> Unit,
        onMediaLongClick: (MediaContextMenuItem) -> Unit = {}
) {
        val playlist by libraryViewModel.playlistDetail.collectAsState()
        val tracks by libraryViewModel.playlistTracks.collectAsState()
        val isLoading by libraryViewModel.playlistDetailLoading.collectAsState()

        LaunchedEffect(playlistId) { libraryViewModel.loadPlaylistDetail(playlistId) }
        val topBarColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                .compositeOver(MaterialTheme.colorScheme.background)

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = {
                                        Text(
                                                playlist?.name ?: "",
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                        )
                                },
                                navigationIcon = {
                                        IconButton(onClick = onBack) {
                                                Icon(
                                                        Icons.AutoMirrored.Filled.ArrowBack,
                                                        contentDescription = "Back"
                                                )
                                        }
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
                        if (playlist != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Playlist header with gradient
                                        item {
                                                Box(modifier = Modifier.fillMaxWidth()) {
                                                        Box(
                                                                modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(240.dp)
                                                                        .background(
                                                                                Brush.verticalGradient(
                                                                                        listOf(
                                                                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                                                                                                MaterialTheme.colorScheme.background,
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                                        Column(
                                                                modifier =
                                                                        Modifier.fillMaxWidth()
                                                                                .padding(24.dp),
                                                                horizontalAlignment =
                                                                        Alignment.CenterHorizontally
                                                        ) {
                                                                val imageUrl =
                                                                        playlist?.resolvedImage?.let {
                                                                                libraryViewModel
                                                                                        .getImageUrl(it)
                                                                        }
                                                                Box(
                                                                        modifier =
                                                                                Modifier.size(180.dp)
                                                                                        .shadow(16.dp, RoundedCornerShape(16.dp))
                                                                                        .clip(
                                                                                                RoundedCornerShape(
                                                                                                        16.dp
                                                                                                )
                                                                                        )
                                                                                        .background(
                                                                                                MaterialTheme
                                                                                                        .colorScheme
                                                                                                        .surfaceVariant
                                                                                        ),
                                                                        contentAlignment = Alignment.Center
                                                                ) {
                                                                        if (imageUrl != null) {
                                                                                AsyncImage(
                                                                                        model = imageUrl,
                                                                                        contentDescription =
                                                                                                null,
                                                                                        modifier =
                                                                                                Modifier.fillMaxSize(),
                                                                                        contentScale =
                                                                                                ContentScale
                                                                                                        .Crop
                                                                                )
                                                                        } else {
                                                                                ImagePlaceholder(
                                                                                        icon = Icons.Default.MusicNote,
                                                                                        size = 180.dp,
                                                                                        shape = RoundedCornerShape(16.dp),
                                                                                )
                                                                        }
                                                                }
                                                                Spacer(modifier = Modifier.height(16.dp))
                                                                Text(
                                                                        text = playlist?.name ?: "",
                                                                        style =
                                                                                MaterialTheme.typography
                                                                                        .headlineSmall,
                                                                        fontWeight = FontWeight.Bold
                                                                )
                                                                if (playlist?.owner?.isNotEmpty() == true) {
                                                                        Text(
                                                                                text =
                                                                                        "by ${playlist?.owner}",
                                                                                style =
                                                                                        MaterialTheme
                                                                                                .typography
                                                                                                .bodyLarge,
                                                                                color =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
                                                                        )
                                                                }
                                                                Spacer(modifier = Modifier.height(8.dp))
                                                                Text(
                                                                        text = "${tracks.size} tracks",
                                                                        style =
                                                                                MaterialTheme.typography
                                                                                        .bodySmall,
                                                                        color =
                                                                                MaterialTheme.colorScheme
                                                                                        .onSurfaceVariant
                                                                )
                                                                Spacer(modifier = Modifier.height(12.dp))
                                                                PlayAllButton(
                                                                        text = "Play All",
                                                                        onClick = {
                                                                                playlist?.uri?.let {
                                                                                        playerViewModel
                                                                                                .playMedia(
                                                                                                        it,
                                                                                                        MediaType.PLAYLIST,
                                                                                                        "play"
                                                                                                )
                                                                                }
                                                                        }
                                                                )
                                                        }
                                                }
                                        }

                                        items(tracks, key = { it.itemId }) { track ->
                                                TrackRow(
                                                        track = track,
                                                        imageUrl =
                                                                track.resolvedImage?.let {
                                                                        libraryViewModel
                                                                                .getImageUrl(it)
                                                                },
                                                        onClick = {
                                                                playerViewModel.playMedia(
                                                                        track.uri,
                                                                        MediaType.TRACK,
                                                                        "play"
                                                                )
                                                        },
                                                        onLongClick = { onMediaLongClick(MediaContextMenuItem(track.name, track.uri, MediaType.TRACK)) }
                                                )
                                        }
                                }
                        }
                        if (isLoading) {
                                LoadingOverlay()
                        }
                }
        }
}

// ── Shared composables ──────────────────────────────────────

@Composable
private fun PlayAllButton(text: String, onClick: () -> Unit) {
        Button(
                onClick = onClick,
                shape = RoundedCornerShape(24.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 4.dp,
                        pressedElevation = 8.dp,
                ),
        ) {
                Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text)
        }
}

@Composable
private fun AlbumCard(album: Album, imageUrl: String?, onClick: () -> Unit) {
        Column(modifier = Modifier.width(130.dp).clickable(onClick = onClick).padding(4.dp)) {
                Surface(
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 1.dp,
                        shadowElevation = 4.dp,
                        modifier = Modifier.size(122.dp),
                ) {
                        Box(contentAlignment = Alignment.Center) {
                                if (imageUrl != null) {
                                        AsyncImage(
                                                model = imageUrl,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                        )
                                } else {
                                        ImagePlaceholder(
                                                icon = Icons.Default.Album,
                                                size = 122.dp,
                                                shape = RoundedCornerShape(14.dp),
                                        )
                                }
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
                album.year?.let { year ->
                        Text(
                                text = year.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                }
        }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
        track: Track,
        isCurrentTrack: Boolean = false,
        isPlaying: Boolean = false,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null
) {
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val trackColor =
                if (isCurrentTrack) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
        val subColor =
                if (isCurrentTrack) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant

        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .combinedClickable(
                                        onClick = onClick,
                                        onLongClick = if (onLongClick != null) {{
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                                onLongClick()
                                        }} else null
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
                // Track number or playing indicator
                Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        if (isCurrentTrack) {
                                Icon(
                                        if (isPlaying) Icons.Default.Pause
                                        else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                )
                        } else {
                                Text(
                                        text =
                                                if (track.trackNumber > 0)
                                                        track.trackNumber.toString()
                                                else "-",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                        }
                }
                Column(modifier = Modifier.weight(1f)) {
                        Text(
                                text = track.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = trackColor,
                                fontWeight = if (isCurrentTrack) FontWeight.Bold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                        val artistName = track.artists.firstOrNull()?.name
                        if (artistName != null) {
                                Text(
                                        text = artistName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = subColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                )
                        }
                }
                Text(
                        text = formatDuration(track.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = subColor
                )
        }
}

@Composable
private fun LoadingOverlay() {
        Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
                repeat(5) {
                        ShimmerTrackRow()
                }
        }
}
