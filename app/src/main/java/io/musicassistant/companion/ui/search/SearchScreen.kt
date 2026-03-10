package io.musicassistant.companion.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track
import io.musicassistant.companion.ui.common.AlbumRow
import io.musicassistant.companion.ui.common.ArtistRow
import io.musicassistant.companion.ui.common.EmptyState
import io.musicassistant.companion.ui.common.PlaylistRow
import io.musicassistant.companion.ui.common.RadioRow
import io.musicassistant.companion.ui.common.TrackRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
        searchViewModel: SearchViewModel,
        onArtistClick: (String) -> Unit,
        onAlbumClick: (String) -> Unit,
        onTrackClick: (Track) -> Unit,
        onPlaylistClick: (String) -> Unit,
        onRadioClick: (Radio) -> Unit = {}
) {
        val query by searchViewModel.query.collectAsState()
        val results by searchViewModel.results.collectAsState()
        val isSearching by searchViewModel.isSearching.collectAsState()

        val hasResults =
                results.artists.isNotEmpty() ||
                        results.albums.isNotEmpty() ||
                        results.tracks.isNotEmpty() ||
                        results.playlists.isNotEmpty() ||
                        results.radio.isNotEmpty()

        Scaffold(
                topBar = {
                        TopAppBar(
                                title = { Text("Search", fontWeight = FontWeight.Bold) },
                                colors =
                                        TopAppBarDefaults.topAppBarColors(
                                                containerColor = MaterialTheme.colorScheme.surface
                                        )
                        )
                }
        ) { innerPadding ->
                Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                        TextField(
                                value = query,
                                onValueChange = { searchViewModel.onQueryChanged(it) },
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                                .shadow(2.dp, RoundedCornerShape(28.dp)),
                                placeholder = { Text("Search artists, albums, tracks...") },
                                leadingIcon = {
                                        Icon(Icons.Default.Search, contentDescription = null)
                                },
                                trailingIcon = {
                                        if (query.isNotEmpty()) {
                                                IconButton(
                                                        onClick = {
                                                                searchViewModel.onQueryChanged("")
                                                        }
                                                ) {
                                                        Icon(
                                                                Icons.Default.Clear,
                                                                contentDescription = "Clear"
                                                        )
                                                }
                                        }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(28.dp),
                                colors =
                                        TextFieldDefaults.colors(
                                                focusedContainerColor =
                                                        MaterialTheme.colorScheme.surfaceContainer,
                                                unfocusedContainerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent,
                                                cursorColor = MaterialTheme.colorScheme.primary,
                                        )
                        )

                        if (isSearching) {
                                Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        } else if (query.isEmpty()) {
                                EmptyState(
                                        icon = Icons.Default.Search,
                                        title = "Search your library",
                                        subtitle = "Find artists, albums, tracks, and more",
                                )
                        } else if (query.length >= 2 && !hasResults) {
                                EmptyState(
                                        icon = Icons.Default.SearchOff,
                                        title = "No results found",
                                        subtitle = "Try a different search term",
                                )
                        } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        if (results.artists.isNotEmpty()) {
                                                item { SectionHeader("Artists") }
                                                items(
                                                        results.artists,
                                                        key = { "artist_${it.itemId}" }
                                                ) { artist ->
                                                        ArtistRow(
                                                                artist = artist,
                                                                imageUrl =
                                                                        artist.resolvedImage?.let {
                                                                                searchViewModel
                                                                                        .getImageUrl(
                                                                                                it
                                                                                        )
                                                                        },
                                                                onClick = {
                                                                        onArtistClick(artist.itemId)
                                                                },
                                                                imageSize = 44.dp
                                                        )
                                                }
                                        }
                                        if (results.albums.isNotEmpty()) {
                                                item { SectionHeader("Albums") }
                                                items(
                                                        results.albums,
                                                        key = { "album_${it.itemId}" }
                                                ) { album ->
                                                        AlbumRow(
                                                                album = album,
                                                                imageUrl =
                                                                        album.resolvedImage?.let {
                                                                                searchViewModel
                                                                                        .getImageUrl(
                                                                                                it
                                                                                        )
                                                                        },
                                                                onClick = {
                                                                        onAlbumClick(album.itemId)
                                                                }
                                                        )
                                                }
                                        }
                                        if (results.tracks.isNotEmpty()) {
                                                item { SectionHeader("Tracks") }
                                                items(
                                                        results.tracks,
                                                        key = { "track_${it.itemId}" }
                                                ) { track ->
                                                        TrackRow(
                                                                track = track,
                                                                imageUrl =
                                                                        track.resolvedImage?.let {
                                                                                searchViewModel
                                                                                        .getImageUrl(
                                                                                                it
                                                                                        )
                                                                        },
                                                                onClick = { onTrackClick(track) }
                                                        )
                                                }
                                        }
                                        if (results.playlists.isNotEmpty()) {
                                                item { SectionHeader("Playlists") }
                                                items(
                                                        results.playlists,
                                                        key = { "playlist_${it.itemId}" }
                                                ) { playlist ->
                                                        PlaylistRow(
                                                                playlist = playlist,
                                                                imageUrl =
                                                                        playlist.resolvedImage
                                                                                ?.let {
                                                                                        searchViewModel
                                                                                                .getImageUrl(
                                                                                                        it
                                                                                                )
                                                                                },
                                                                onClick = {
                                                                        onPlaylistClick(
                                                                                playlist.itemId
                                                                        )
                                                                },
                                                                imageSize = 44.dp
                                                        )
                                                }
                                        }
                                        if (results.radio.isNotEmpty()) {
                                                item { SectionHeader("Radio") }
                                                items(
                                                        results.radio,
                                                        key = { "radio_${it.itemId}" }
                                                ) { radio ->
                                                        RadioRow(
                                                                radio = radio,
                                                                imageUrl =
                                                                        radio.resolvedImage?.let {
                                                                                searchViewModel
                                                                                        .getImageUrl(
                                                                                                it
                                                                                        )
                                                                        },
                                                                onClick = { onRadioClick(radio) },
                                                                imageSize = 44.dp
                                                        )
                                                }
                                        }

                                        item { Spacer(modifier = Modifier.height(16.dp)) }
                                }
                        }
                }
        }
}

@Composable
private fun SectionHeader(title: String) {
        Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
        ) {
                Box(
                        modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                )
        }
}
