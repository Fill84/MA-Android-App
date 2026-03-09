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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.Playlist
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track

private val tabs = listOf("Artists", "Albums", "Tracks", "Playlists", "Radio")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
        libraryViewModel: LibraryViewModel,
        onArtistClick: (String) -> Unit,
        onAlbumClick: (String) -> Unit,
        onTrackClick: (Track) -> Unit,
        onPlaylistClick: (String) -> Unit,
        onRadioClick: (Radio) -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
            topBar = {
                TopAppBar(
                        title = { Text("Library", fontWeight = FontWeight.Bold) },
                        colors =
                                TopAppBarDefaults.topAppBarColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                )
                )
            }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 16.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                    )
                }
            }

            when (selectedTab) {
                0 -> ArtistsTab(libraryViewModel, onArtistClick)
                1 -> AlbumsTab(libraryViewModel, onAlbumClick)
                2 -> TracksTab(libraryViewModel, onTrackClick)
                3 -> PlaylistsTab(libraryViewModel, onPlaylistClick)
                4 -> RadiosTab(libraryViewModel, onRadioClick)
            }
        }
    }
}

@Composable
private fun ArtistsTab(viewModel: LibraryViewModel, onArtistClick: (String) -> Unit) {
    val artists by viewModel.artists.collectAsState()
    val isLoading by viewModel.artistsLoading.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { if (artists.isEmpty()) viewModel.loadArtists() }

    // Load more when near the end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= artists.size - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && artists.isNotEmpty()) viewModel.loadArtists()
    }

    LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(artists, key = { it.itemId }) { artist ->
            ArtistRow(
                    artist = artist,
                    imageUrl = artist.resolvedImage?.let { viewModel.getImageUrl(it) },
                    onClick = { onArtistClick(artist.itemId) }
            )
        }
        if (isLoading) {
            item { LoadingIndicator() }
        }
    }
}

@Composable
private fun AlbumsTab(viewModel: LibraryViewModel, onAlbumClick: (String) -> Unit) {
    val albums by viewModel.albums.collectAsState()
    val isLoading by viewModel.albumsLoading.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) { if (albums.isEmpty()) viewModel.loadAlbums() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= albums.size - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && albums.isNotEmpty()) viewModel.loadAlbums()
    }

    LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums, key = { it.itemId }) { album ->
            AlbumGridItem(
                    album = album,
                    imageUrl = album.resolvedImage?.let { viewModel.getImageUrl(it) },
                    onClick = { onAlbumClick(album.itemId) }
            )
        }
    }

    if (isLoading && albums.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun TracksTab(viewModel: LibraryViewModel, onTrackClick: (Track) -> Unit) {
    val tracks by viewModel.tracks.collectAsState()
    val isLoading by viewModel.tracksLoading.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { if (tracks.isEmpty()) viewModel.loadTracks() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= tracks.size - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && tracks.isNotEmpty()) viewModel.loadTracks()
    }

    LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(tracks, key = { it.itemId }) { track ->
            TrackRow(
                    track = track,
                    imageUrl = track.resolvedImage?.let { viewModel.getImageUrl(it) },
                    onClick = { onTrackClick(track) }
            )
        }
        if (isLoading) {
            item { LoadingIndicator() }
        }
    }
}

@Composable
private fun PlaylistsTab(viewModel: LibraryViewModel, onPlaylistClick: (String) -> Unit) {
    val playlists by viewModel.playlists.collectAsState()
    val isLoading by viewModel.playlistsLoading.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { if (playlists.isEmpty()) viewModel.loadPlaylists() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= playlists.size - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && playlists.isNotEmpty()) viewModel.loadPlaylists()
    }

    LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(playlists, key = { it.itemId }) { playlist ->
            PlaylistRow(
                    playlist = playlist,
                    imageUrl = playlist.resolvedImage?.let { viewModel.getImageUrl(it) },
                    onClick = { onPlaylistClick(playlist.itemId) }
            )
        }
        if (isLoading) {
            item { LoadingIndicator() }
        }
    }
}

@Composable
private fun RadiosTab(viewModel: LibraryViewModel, onRadioClick: (Radio) -> Unit) {
    val radios by viewModel.radios.collectAsState()
    val isLoading by viewModel.radiosLoading.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { if (radios.isEmpty()) viewModel.loadRadios() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= radios.size - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && radios.isNotEmpty()) viewModel.loadRadios()
    }

    LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(radios, key = { it.itemId }) { radio ->
            RadioRow(
                    radio = radio,
                    imageUrl = radio.resolvedImage?.let { viewModel.getImageUrl(it) },
                    onClick = { onRadioClick(radio) }
            )
        }
        if (isLoading) {
            item { LoadingIndicator() }
        }
    }
}

// ── Row components ────────────────────────────────────────────

@Composable
private fun ArtistRow(artist: Artist, imageUrl: String?, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AlbumGridItem(album: Album, imageUrl: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
                modifier =
                        Modifier.fillMaxWidth()
                                .height(140.dp)
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
                        modifier = Modifier.size(48.dp)
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

@Composable
private fun PlaylistRow(playlist: Playlist, imageUrl: String?, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            if (playlist.owner.isNotEmpty()) {
                Text(
                        text = playlist.owner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RadioRow(radio: Radio, imageUrl: String?, onClick: () -> Unit) {
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .clickable(onClick = onClick)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                        Icons.Default.Radio,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
                text = radio.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoadingIndicator() {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
