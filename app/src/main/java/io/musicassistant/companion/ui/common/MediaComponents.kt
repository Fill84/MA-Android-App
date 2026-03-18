package io.musicassistant.companion.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.Album
import io.musicassistant.companion.data.model.Artist
import io.musicassistant.companion.data.model.MediaType
import io.musicassistant.companion.data.model.Playlist
import io.musicassistant.companion.data.model.Radio
import io.musicassistant.companion.data.model.Track

// ── Context Menu ─────────────────────────────────────────────────

/** Represents an item selected for the context menu. */
data class MediaContextMenuItem(
        val name: String,
        val uri: String,
        val mediaType: MediaType
)

/**
 * Modal bottom sheet with play options for a media item.
 * Shows: Play Now, Play Now (clear queue), Play Next, Add to Queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaContextMenu(
        item: MediaContextMenuItem,
        onPlay: (uri: String, mediaType: MediaType, option: String) -> Unit,
        onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            // Header
            Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

            ContextMenuItem(
                    icon = Icons.Default.PlayArrow,
                    label = "Play Now",
                    onClick = { onPlay(item.uri, item.mediaType, "play"); onDismiss() }
            )
            ContextMenuItem(
                    icon = Icons.Default.PlayArrow,
                    label = "Play Now (clear queue)",
                    onClick = { onPlay(item.uri, item.mediaType, "replace"); onDismiss() }
            )
            ContextMenuItem(
                    icon = Icons.Default.SkipNext,
                    label = "Play Next",
                    onClick = { onPlay(item.uri, item.mediaType, "next"); onDismiss() }
            )
            ContextMenuItem(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    label = "Add to Queue",
                    onClick = { onPlay(item.uri, item.mediaType, "add"); onDismiss() }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ContextMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
            headlineContent = { Text(label) },
            leadingContent = {
                Icon(icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface)
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
            ),
            modifier = Modifier.clickable(onClick = onClick)
    )
}

// ── Row components (list items) ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistRow(
        artist: Artist,
        imageUrl: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        imageSize: Dp = 48.dp
) {
    val haptic = LocalHapticFeedback.current
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = if (onLongClick != null) {{
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongClick()
                                    }} else null
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(imageSize)
                                .clip(CircleShape)
                                .background(
                                        Brush.radialGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                        )
                                ),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(imageSize),
                        contentScale = ContentScale.Crop
                )
            } else {
                ImagePlaceholder(
                        icon = Icons.Default.Person,
                        size = imageSize,
                        shape = CircleShape,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
        track: Track,
        imageUrl: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        imageSize: Dp = 48.dp
) {
    val haptic = LocalHapticFeedback.current
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = if (onLongClick != null) {{
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongClick()
                                    }} else null
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(imageSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                        Brush.radialGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                        )
                                ),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(imageSize),
                        contentScale = ContentScale.Crop
                )
            } else {
                ImagePlaceholder(
                        icon = Icons.Default.MusicNote,
                        size = imageSize,
                        shape = RoundedCornerShape(8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumRow(
        album: Album,
        imageUrl: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        imageSize: Dp = 48.dp
) {
    val haptic = LocalHapticFeedback.current
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = if (onLongClick != null) {{
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongClick()
                                    }} else null
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(imageSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                        Brush.radialGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                        )
                                ),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(imageSize),
                        contentScale = ContentScale.Crop
                )
            } else {
                ImagePlaceholder(
                        icon = Icons.Default.Album,
                        size = imageSize,
                        shape = RoundedCornerShape(8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistRow(
        playlist: Playlist,
        imageUrl: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        imageSize: Dp = 48.dp
) {
    val haptic = LocalHapticFeedback.current
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = if (onLongClick != null) {{
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongClick()
                                    }} else null
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(imageSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                        Brush.radialGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                        )
                                ),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(imageSize),
                        contentScale = ContentScale.Crop
                )
            } else {
                ImagePlaceholder(
                        icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                        size = imageSize,
                        shape = RoundedCornerShape(8.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioRow(
        radio: Radio,
        imageUrl: String?,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)? = null,
        imageSize: Dp = 48.dp
) {
    val haptic = LocalHapticFeedback.current
    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .combinedClickable(
                                    onClick = onClick,
                                    onLongClick = if (onLongClick != null) {{
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onLongClick()
                                    }} else null
                            )
                            .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
                modifier =
                        Modifier.size(imageSize)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                        Brush.radialGradient(
                                                listOf(
                                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                                        MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                        )
                                ),
                contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.size(imageSize),
                        contentScale = ContentScale.Crop
                )
            } else {
                ImagePlaceholder(
                        icon = Icons.Default.Radio,
                        size = imageSize,
                        shape = RoundedCornerShape(8.dp),
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

// ── Card components (grid/horizontal scroll items) ───────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackCard(track: Track, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 140.dp) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = Modifier.width(size).combinedClickable(
            onClick = onClick,
            onLongClick = if (onLongClick != null) {{
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }} else null
    ).padding(4.dp)) {
        Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.size(size - 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = track.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.MusicNote,
                            size = size - 8.dp,
                            shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCard(album: Album, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 140.dp) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = Modifier.width(size).combinedClickable(
            onClick = onClick,
            onLongClick = if (onLongClick != null) {{
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }} else null
    ).padding(4.dp)) {
        Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.size(size - 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = album.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.Album,
                            size = size - 8.dp,
                            shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
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
fun ArtistCard(artist: Artist, imageUrl: String?, size: Dp = 140.dp) {
    Column(modifier = Modifier.width(size).padding(4.dp)) {
        Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.size(size - 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = artist.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.Person,
                            size = size - 8.dp,
                            shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
                text = artist.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RadioCard(radio: Radio, imageUrl: String?, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null, size: Dp = 140.dp) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = Modifier.width(size).combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = if (onLongClick != null) {{
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }} else null
    ).padding(4.dp)) {
        Surface(
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 1.dp,
                shadowElevation = 4.dp,
                modifier = Modifier.size(size - 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = radio.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.Radio,
                            size = size - 8.dp,
                            shape = RoundedCornerShape(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
                text = radio.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
        )
    }
}

// ── Grid items ───────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridItem(album: Album, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    Column(modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = onClick,
            onLongClick = if (onLongClick != null) {{
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }} else null
    ).padding(4.dp)) {
        Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = album.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.Album,
                            size = 140.dp,
                            shape = RoundedCornerShape(12.dp),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistGridItem(artist: Artist, imageUrl: String?, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    Column(
            modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = onClick,
                    onLongClick = if (onLongClick != null) {{
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }} else null
            ).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
                shape = CircleShape,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (imageUrl != null) {
                    AsyncImage(
                            model = imageUrl,
                            contentDescription = artist.name,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.Person,
                            size = 140.dp,
                            shape = CircleShape,
                    )
                }
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

// ── Utility ──────────────────────────────────────────────────────

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}
