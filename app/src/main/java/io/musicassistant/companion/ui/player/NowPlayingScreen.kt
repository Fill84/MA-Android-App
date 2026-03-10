package io.musicassistant.companion.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.RepeatOneOn
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ShuffleOn
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.RepeatMode
import io.musicassistant.companion.ui.common.ImagePlaceholder
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
        playerViewModel: PlayerViewModel,
        onBack: () -> Unit,
        onOpenQueue: () -> Unit
) {
        val isPlaying by playerViewModel.isPlaying.collectAsState()
        val title by playerViewModel.currentTrackTitle.collectAsState()
        val artist by playerViewModel.currentTrackArtist.collectAsState()
        val artworkUri by playerViewModel.currentArtworkUri.collectAsState()
        val isLive by playerViewModel.isLive.collectAsState()
        val queue by playerViewModel.queue.collectAsState()
        val player by playerViewModel.activePlayer.collectAsState()
        val allPlayers by playerViewModel.players.collectAsState()

        // Player picker dialog state
        var showPlayerPicker by remember { mutableStateOf(false) }

        // Position tracking
        var positionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var isSeeking by remember { mutableFloatStateOf(-1f) }

        // Poll position while playing
        LaunchedEffect(isPlaying) {
                while (true) {
                        positionMs = playerViewModel.currentPositionMs
                        durationMs = playerViewModel.durationMs
                        delay(500)
                }
        }

        Box(modifier = Modifier.fillMaxSize()) {
                // Gradient background
                Box(
                        modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                        listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                MaterialTheme.colorScheme.background,
                                        )
                                )
                        )
                )

                Column(
                        modifier =
                                Modifier.fillMaxSize()
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                ) {
                        // Top bar
                        Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                IconButton(onClick = onBack) {
                                        Icon(
                                                Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Minimize",
                                                tint = MaterialTheme.colorScheme.onBackground
                                        )
                                }
                                Text(
                                        text = "NOW PLAYING",
                                        style = MaterialTheme.typography.labelMedium,
                                        letterSpacing = 1.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(onClick = onOpenQueue) {
                                        Icon(
                                                Icons.AutoMirrored.Filled.QueueMusic,
                                                contentDescription = "Queue",
                                                tint = MaterialTheme.colorScheme.onBackground
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        // Album art with shadow
                        Box(
                                modifier =
                                        Modifier.fillMaxWidth(0.80f)
                                                .aspectRatio(1f)
                                                .shadow(
                                                        elevation = 24.dp,
                                                        shape = RoundedCornerShape(20.dp),
                                                        ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                                        spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                )
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                        ) {
                                if (artworkUri != null) {
                                        AsyncImage(
                                                model = artworkUri,
                                                contentDescription = "Album art",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                        )
                                } else {
                                        ImagePlaceholder(
                                                icon = Icons.Default.MusicNote,
                                                size = 200.dp,
                                                shape = RoundedCornerShape(20.dp),
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // Track info
                        Text(
                                text = title ?: "No track",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                                text = artist ?: "",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        if (isLive) {
                                // Live indicator instead of seek bar
                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                ) {
                                        Box(
                                                modifier = Modifier
                                                        .size(8.dp)
                                                        .background(
                                                                MaterialTheme.colorScheme.error,
                                                                CircleShape
                                                        )
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                                text = "LIVE",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error,
                                                letterSpacing = 1.sp
                                        )
                                }
                        } else {
                                // Seek bar for non-live content
                                val progress =
                                        if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
                                val displayProgress = if (isSeeking >= 0f) isSeeking else progress

                                Slider(
                                        value = displayProgress,
                                        onValueChange = { isSeeking = it },
                                        onValueChangeFinished = {
                                                if (isSeeking >= 0f) {
                                                        playerViewModel.seekTo((isSeeking * durationMs).toLong())
                                                        isSeeking = -1f
                                                }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors =
                                                SliderDefaults.colors(
                                                        thumbColor = MaterialTheme.colorScheme.primary,
                                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                                )
                                )

                                Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                        Text(
                                                text = formatTime(positionMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                                text = formatTime(durationMs),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Shuffle / Repeat row
                        Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                IconButton(onClick = { playerViewModel.toggleShuffle() }) {
                                        Icon(
                                                if (queue?.shuffleEnabled == true) Icons.Default.ShuffleOn
                                                else Icons.Default.Shuffle,
                                                contentDescription = "Shuffle",
                                                tint =
                                                        if (queue?.shuffleEnabled == true)
                                                                MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }

                                // Main controls
                                IconButton(
                                        onClick = { playerViewModel.previous() },
                                        modifier = Modifier.size(48.dp)
                                ) {
                                        Icon(
                                                Icons.Default.SkipPrevious,
                                                contentDescription = "Previous",
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onBackground
                                        )
                                }

                                IconButton(
                                        onClick = {
                                                if (isPlaying) playerViewModel.pause()
                                                else playerViewModel.play()
                                        },
                                        modifier =
                                                Modifier.size(68.dp)
                                                        .shadow(
                                                                elevation = 8.dp,
                                                                shape = CircleShape,
                                                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                                        )
                                                        .background(
                                                                MaterialTheme.colorScheme.primary,
                                                                CircleShape
                                                        )
                                ) {
                                        Icon(
                                                if (isPlaying) Icons.Default.Pause
                                                else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                modifier = Modifier.size(36.dp),
                                                tint = MaterialTheme.colorScheme.onPrimary
                                        )
                                }

                                IconButton(
                                        onClick = { playerViewModel.next() },
                                        modifier = Modifier.size(48.dp)
                                ) {
                                        Icon(
                                                Icons.Default.SkipNext,
                                                contentDescription = "Next",
                                                modifier = Modifier.size(32.dp),
                                                tint = MaterialTheme.colorScheme.onBackground
                                        )
                                }

                                IconButton(onClick = { playerViewModel.toggleRepeat() }) {
                                        val repeatIcon =
                                                when (queue?.repeatMode) {
                                                        RepeatMode.ONE -> Icons.Default.RepeatOneOn
                                                        RepeatMode.ALL -> Icons.Default.RepeatOn
                                                        else -> Icons.Default.Repeat
                                                }
                                        Icon(
                                                repeatIcon,
                                                contentDescription = "Repeat",
                                                tint =
                                                        if (queue?.repeatMode != RepeatMode.OFF)
                                                                MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Player picker button
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .border(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.outline,
                                                        RoundedCornerShape(12.dp)
                                                )
                                                .background(
                                                        MaterialTheme.colorScheme.surfaceVariant.copy(
                                                                alpha = 0.5f
                                                        )
                                                )
                                                .clickable { showPlayerPicker = true }
                                                .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                                Icon(
                                        Icons.Default.Speaker,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                        text = player?.name ?: "Select Player",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                )
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                }
        }

        // Player picker dialog
        if (showPlayerPicker) {
                AlertDialog(
                        onDismissRequest = { showPlayerPicker = false },
                        title = { Text("Select Player") },
                        text = {
                                LazyColumn {
                                        items(allPlayers, key = { it.playerId }) { p ->
                                                val isSelected = p.playerId == player?.playerId
                                                Row(
                                                        modifier =
                                                                Modifier.fillMaxWidth()
                                                                        .clickable {
                                                                                playerViewModel
                                                                                        .selectPlayer(
                                                                                                p.playerId
                                                                                        )
                                                                                showPlayerPicker =
                                                                                        false
                                                                        }
                                                                        .padding(
                                                                                vertical = 12.dp,
                                                                                horizontal = 4.dp
                                                                        ),
                                                        verticalAlignment =
                                                                Alignment.CenterVertically
                                                ) {
                                                        Icon(
                                                                Icons.Default.Speaker,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(24.dp),
                                                                tint =
                                                                        if (isSelected)
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                        else
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurfaceVariant
                                                        )
                                                        Spacer(modifier = Modifier.width(12.dp))
                                                        Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                        text = p.name,
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .bodyMedium,
                                                                        fontWeight =
                                                                                if (isSelected)
                                                                                        FontWeight
                                                                                                .Bold
                                                                                else
                                                                                        FontWeight
                                                                                                .Normal
                                                                )
                                                                Text(
                                                                        text =
                                                                                when (p.state) {
                                                                                        io.musicassistant
                                                                                                .companion
                                                                                                .data
                                                                                                .model
                                                                                                .PlayerState
                                                                                                .PLAYING ->
                                                                                                "Playing"
                                                                                        io.musicassistant
                                                                                                .companion
                                                                                                .data
                                                                                                .model
                                                                                                .PlayerState
                                                                                                .PAUSED ->
                                                                                                "Paused"
                                                                                        else ->
                                                                                                "Idle"
                                                                                },
                                                                        style =
                                                                                MaterialTheme
                                                                                        .typography
                                                                                        .labelSmall,
                                                                        color =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .onSurfaceVariant
                                                                )
                                                        }
                                                        if (isSelected) {
                                                                Icon(
                                                                        Icons.Default.Check,
                                                                        contentDescription =
                                                                                "Selected",
                                                                        tint =
                                                                                MaterialTheme
                                                                                        .colorScheme
                                                                                        .primary
                                                                )
                                                        }
                                                }
                                        }
                                }
                        },
                        confirmButton = {
                                TextButton(onClick = { showPlayerPicker = false }) { Text("Close") }
                        }
                )
        }
}

private fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
}
