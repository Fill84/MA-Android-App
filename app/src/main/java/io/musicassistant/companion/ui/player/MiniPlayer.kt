package io.musicassistant.companion.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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

    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f

    Column(
            modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        // Progress bar
        LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Row(
                modifier =
                        Modifier.fillMaxWidth()
                                .clickable(onClick = onClick)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                    modifier =
                            Modifier.size(40.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
            ) {
                if (artworkUri != null) {
                    AsyncImage(
                            model = artworkUri,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
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
                    onClick = { if (isPlaying) playerViewModel.pause() else playerViewModel.play() }
            ) {
                Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onSurface
                )
            }

            IconButton(onClick = { playerViewModel.next() }) {
                Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
