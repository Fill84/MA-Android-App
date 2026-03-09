package io.musicassistant.companion.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
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
                        // Search bar
                        TextField(
                                value = query,
                                onValueChange = { searchViewModel.onQueryChanged(it) },
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp, vertical = 8.dp),
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
                                shape = RoundedCornerShape(24.dp),
                                colors =
                                        TextFieldDefaults.colors(
                                                focusedContainerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                unfocusedContainerColor =
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent
                                        )
                        )

                        if (isSearching) {
                                Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        } else if (query.length >= 2 && !hasResults) {
                                Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center
                                ) {
                                        Text(
                                                text = "No results found",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        // Artists
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
                                                                }
                                                        )
                                                }
                                        }
                                        // Albums
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
                                        // Tracks
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
                                        // Playlists
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
                                                                }
                                                        )
                                                }
                                        }
                                        // Radio
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
                                                                onClick = { onRadioClick(radio) }
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
        Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
}

@Composable
private fun ArtistRow(artist: Artist, imageUrl: String?, onClick: () -> Unit) {
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
                                        .clip(CircleShape)
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
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
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
private fun AlbumRow(album: Album, imageUrl: String?, onClick: () -> Unit) {
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
                                        Icons.Default.Album,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                )
                        }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                        Text(
                                text = album.name,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                        val artistName = album.artists.firstOrNull()?.name
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
                                        modifier = Modifier.size(22.dp)
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
                                        Icons.AutoMirrored.Filled.PlaylistPlay,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                )
                        }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                        Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.bodyMedium,
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
                                        Icons.Default.Radio,
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp)
                                )
                        }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                        text = radio.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
        }
}

private fun formatDuration(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
}
