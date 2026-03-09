package io.musicassistant.companion.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(playerViewModel: PlayerViewModel, onBack: () -> Unit) {
    val queueItems by playerViewModel.queueItems.collectAsState()
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex = queue?.currentIndex

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header
        Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                        text = "Queue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                        text = "${queueItems.size} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (queueItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                        text = "Queue is empty",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(items = queueItems, key = { _, item -> item.queueItemId }) {
                        index,
                        item ->
                    val isCurrent = index == currentIndex
                    val dismissState =
                            rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart) {
                                            playerViewModel.deleteQueueItem(item.queueItemId)
                                            true
                                        } else false
                                    }
                            )

                    SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                        modifier =
                                                Modifier.fillMaxSize()
                                                        .background(MaterialTheme.colorScheme.error)
                                                        .padding(horizontal = 16.dp),
                                        contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.onError
                                    )
                                }
                            }
                    ) {
                        QueueItemRow(
                                item = item,
                                isCurrent = isCurrent,
                                index = index,
                                onClick = { playerViewModel.playQueueIndex(index) },
                                playerViewModel = playerViewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
        item: io.musicassistant.companion.data.model.QueueItem,
        isCurrent: Boolean,
        index: Int,
        onClick: () -> Unit,
        playerViewModel: PlayerViewModel
) {
    val backgroundColor =
            if (isCurrent) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            } else {
                MaterialTheme.colorScheme.background
            }

    Row(
            modifier =
                    Modifier.fillMaxWidth()
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .let { mod -> mod.then(Modifier.background(backgroundColor)) },
            verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Box(
                modifier =
                        Modifier.size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
        ) {
            val image = item.image ?: item.mediaItem?.image
            val imageUrl = image?.let { playerViewModel.getImageUrl(it) }
            if (imageUrl != null) {
                AsyncImage(
                        model = imageUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
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
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color =
                            if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            val artistNames = item.mediaItem?.artists?.joinToString(", ") { it.name } ?: ""
            if (artistNames.isNotEmpty()) {
                Text(
                        text = artistNames,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Duration
        Text(
                text = formatDuration(item.duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val min = seconds / 60
    val sec = seconds % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
