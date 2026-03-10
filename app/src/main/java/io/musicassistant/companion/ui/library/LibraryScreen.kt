package io.musicassistant.companion.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.common.AlbumGridItem
import io.musicassistant.companion.ui.common.ArtistRow
import io.musicassistant.companion.ui.common.PlaylistRow
import io.musicassistant.companion.ui.common.RadioRow
import io.musicassistant.companion.ui.common.ShimmerTrackRow
import io.musicassistant.companion.ui.common.TrackRow

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
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = {
                        TabRowDefaults.PrimaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(
                                        selectedTab,
                                        matchContentSize = true,
                                ),
                                height = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                        )
                    }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                        title,
                                        fontWeight = if (selectedTab == index) FontWeight.SemiBold
                                                     else FontWeight.Normal,
                                        color = if (selectedTab == index)
                                                    MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
            columns = GridCells.Adaptive(150.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
            LoadingIndicator()
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

@Composable
private fun LoadingIndicator() {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        repeat(3) {
            ShimmerTrackRow()
            Spacer(Modifier.height(4.dp))
        }
    }
}
