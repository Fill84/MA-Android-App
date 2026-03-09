package io.musicassistant.companion.ui.library

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.player.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
        artistId: String,
        libraryViewModel: LibraryViewModel,
        playerViewModel: PlayerViewModel,
        onAlbumClick: (String) -> Unit,
        onBack: () -> Unit
) {
        val artist by libraryViewModel.artistDetail.collectAsState()
        val albums by libraryViewModel.artistAlbums.collectAsState()
        val tracks by libraryViewModel.artistTracks.collectAsState()
        val isLoading by libraryViewModel.artistDetailLoading.collectAsState()

        LaunchedEffect(artistId) { libraryViewModel.loadArtistDetail(artistId) }

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
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        )
                }
        ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (artist != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Artist header
                                        item {
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
                                                                        Icon(
                                                                                Icons.Default
                                                                                        .Person,
                                                                                contentDescription =
                                                                                        null,
                                                                                modifier =
                                                                                        Modifier.size(
                                                                                                64.dp
                                                                                        ),
                                                                                tint =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
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
                                                        Button(
                                                                onClick = {
                                                                        artist?.uri?.let {
                                                                                playerViewModel
                                                                                        .playMedia(
                                                                                                it,
                                                                                                MediaType
                                                                                                        .ARTIST,
                                                                                                "play"
                                                                                        )
                                                                        }
                                                                }
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.PlayArrow,
                                                                        contentDescription = null,
                                                                        modifier =
                                                                                Modifier.size(18.dp)
                                                                )
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.width(4.dp)
                                                                )
                                                                Text("Play All")
                                                        }
                                                }
                                        }

                                        // Albums section
                                        if (albums.isNotEmpty()) {
                                                item {
                                                        Text(
                                                                text = "Albums",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier =
                                                                        Modifier.padding(
                                                                                horizontal = 16.dp,
                                                                                vertical = 8.dp
                                                                        )
                                                        )
                                                }
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
                                                item {
                                                        Text(
                                                                text = "Top Tracks",
                                                                style =
                                                                        MaterialTheme.typography
                                                                                .titleMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier =
                                                                        Modifier.padding(
                                                                                start = 16.dp,
                                                                                end = 16.dp,
                                                                                top = 16.dp,
                                                                                bottom = 8.dp
                                                                        )
                                                        )
                                                }
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
                                                                }
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
        onBack: () -> Unit
) {
        val album by libraryViewModel.albumDetail.collectAsState()
        val tracks by libraryViewModel.albumTracks.collectAsState()
        val isLoading by libraryViewModel.albumDetailLoading.collectAsState()
        val queue by playerViewModel.queue.collectAsState()
        val isPlaying by playerViewModel.isPlaying.collectAsState()

        val currentTrackUri = queue?.currentItem?.mediaItem?.uri

        LaunchedEffect(albumId) { libraryViewModel.loadAlbumDetail(albumId) }

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
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        )
                }
        ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (album != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Album header
                                        item {
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
                                                                        Icon(
                                                                                Icons.Default.Album,
                                                                                contentDescription =
                                                                                        null,
                                                                                modifier =
                                                                                        Modifier.size(
                                                                                                64.dp
                                                                                        ),
                                                                                tint =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
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
                                                        Button(
                                                                onClick = {
                                                                        album?.uri?.let {
                                                                                playerViewModel
                                                                                        .playMedia(
                                                                                                it,
                                                                                                MediaType
                                                                                                        .ALBUM,
                                                                                                "play"
                                                                                        )
                                                                        }
                                                                }
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.PlayArrow,
                                                                        contentDescription = null,
                                                                        modifier =
                                                                                Modifier.size(18.dp)
                                                                )
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.width(4.dp)
                                                                )
                                                                Text("Play Album")
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
                                                        }
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
        onBack: () -> Unit
) {
        val playlist by libraryViewModel.playlistDetail.collectAsState()
        val tracks by libraryViewModel.playlistTracks.collectAsState()
        val isLoading by libraryViewModel.playlistDetailLoading.collectAsState()

        LaunchedEffect(playlistId) { libraryViewModel.loadPlaylistDetail(playlistId) }

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
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        )
                }
        ) { innerPadding ->
                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        if (playlist != null) {
                                LazyColumn(
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(bottom = 16.dp)
                                ) {
                                        // Playlist header
                                        item {
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
                                                                        Icon(
                                                                                Icons.Default
                                                                                        .MusicNote,
                                                                                contentDescription =
                                                                                        null,
                                                                                modifier =
                                                                                        Modifier.size(
                                                                                                64.dp
                                                                                        ),
                                                                                tint =
                                                                                        MaterialTheme
                                                                                                .colorScheme
                                                                                                .onSurfaceVariant
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
                                                        Button(
                                                                onClick = {
                                                                        playlist?.uri?.let {
                                                                                playerViewModel
                                                                                        .playMedia(
                                                                                                it,
                                                                                                MediaType
                                                                                                        .PLAYLIST,
                                                                                                "play"
                                                                                        )
                                                                        }
                                                                }
                                                        ) {
                                                                Icon(
                                                                        Icons.Default.PlayArrow,
                                                                        contentDescription = null,
                                                                        modifier =
                                                                                Modifier.size(18.dp)
                                                                )
                                                                Spacer(
                                                                        modifier =
                                                                                Modifier.width(4.dp)
                                                                )
                                                                Text("Play All")
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
                                                        }
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
private fun AlbumCard(album: Album, imageUrl: String?, onClick: () -> Unit) {
        Column(modifier = Modifier.width(130.dp).clickable(onClick = onClick)) {
                Box(
                        modifier =
                                Modifier.size(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
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
                                        Icons.Default.Album,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                )
                        }
                }
                Spacer(modifier = Modifier.height(4.dp))
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

@Composable
private fun AlbumTrackRow(
        track: Track,
        isCurrentTrack: Boolean = false,
        isPlaying: Boolean = false,
        onClick: () -> Unit
) {
        val trackColor =
                if (isCurrentTrack) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
        val subColor =
                if (isCurrentTrack) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant

        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .clickable(onClick = onClick)
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
private fun TrackRow(track: Track, imageUrl: String?, onClick: () -> Unit) {
        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .clickable(onClick = onClick)
                                .padding(horizontal = 16.dp, vertical = 6.dp),
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
                                        modifier = Modifier.size(44.dp),
                                        contentScale = ContentScale.Crop
                                )
                        } else {
                                Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
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
private fun LoadingOverlay() {
        Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
}
