package io.musicassistant.companion.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.musicassistant.companion.ui.common.ImagePlaceholder
import kotlinx.coroutines.delay

/**
 * Mini player bar displayed at the bottom of screens when music is playing or paused. Tapping it
 * opens the full NowPlaying screen.
 */
@Composable
fun MiniPlayer(
        playerViewModel: PlayerViewModel,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
) {
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val title by playerViewModel.currentTrackTitle.collectAsState()
    val artist by playerViewModel.currentTrackArtist.collectAsState()
    val artworkUri by playerViewModel.currentArtworkUri.collectAsState()
    val isLive by playerViewModel.isLive.collectAsState()

    // Only show if there's a track
    if (title == null) return

    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isPlaying) {
        while (true) {
            positionMs = playerViewModel.currentPositionMs
            durationMs = playerViewModel.durationMs
            delay(1000)
        }
    }

    val progress = if (!isLive && durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

    Surface(
            modifier = modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            // Gradient progress bar
            val primaryColor = MaterialTheme.colorScheme.primary
            val trackColor = MaterialTheme.colorScheme.surfaceVariant
            Canvas(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                drawRect(color = trackColor)
                drawRect(
                        color = primaryColor,
                        size = Size(size.width * progress, size.height),
                )
            }

            Row(
                    modifier =
                            Modifier.fillMaxWidth()
                                    .clickable(onClick = onClick)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                val thumbnailShape = RoundedCornerShape(8.dp)
                val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                if (artworkUri != null) {
                    AsyncImage(
                            model = artworkUri,
                            contentDescription = null,
                            modifier = Modifier
                                    .size(44.dp)
                                    .clip(thumbnailShape)
                                    .border(1.dp, outlineColor, thumbnailShape),
                            contentScale = ContentScale.Crop
                    )
                } else {
                    ImagePlaceholder(
                            icon = Icons.Default.MusicNote,
                            size = 44.dp,
                            shape = thumbnailShape,
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                            text = title ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                    )
                    if (!artist.isNullOrEmpty()) {
                        Text(
                                text = artist ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                IconButton(
                        onClick = { if (isPlaying) playerViewModel.pause() else playerViewModel.play() },
                        modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                        onClick = { playerViewModel.next() },
                        modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
