package io.musicassistant.companion.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import io.musicassistant.companion.data.model.QueueItem
import io.musicassistant.companion.ui.common.EmptyState
import io.musicassistant.companion.ui.common.ImagePlaceholder
import kotlin.math.roundToInt

@Composable
fun QueueScreen(playerViewModel: PlayerViewModel, onBack: () -> Unit) {
    val queueItems by playerViewModel.queueItems.collectAsState()
    val queue by playerViewModel.queue.collectAsState()
    val currentIndex = queue?.currentIndex

    // Drag-to-reorder state
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var targetIndex by remember { mutableIntStateOf(-1) }
    val haptic = LocalHapticFeedback.current

    // Local mutable list for optimistic reorder during drag
    var localItems by remember(queueItems) { mutableStateOf(queueItems) }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        playerViewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Header
            Box(
                modifier = Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
            ) {
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
                            text = "${localItems.size} tracks",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (localItems.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = "Queue is empty",
                    subtitle = "Play some music to build your queue",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = localItems,
                        key = { _, item -> item.queueItemId }
                    ) { index, item ->
                        val isCurrent = index == currentIndex
                        val isDragged = index == draggedIndex

                        val elevation by animateDpAsState(
                            targetValue = if (isDragged) 8.dp else 0.dp,
                            label = "dragElevation"
                        )

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragged) 1f else 0f)
                                .offset {
                                    IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0)
                                }
                                .then(if (isDragged) Modifier.shadow(elevation) else Modifier)
                        ) {
                            QueueItemRow(
                                item = item,
                                isCurrent = isCurrent,
                                showDelete = !isCurrent,
                                onClick = { playerViewModel.playQueueIndex(index) },
                                onDelete = { playerViewModel.deleteQueueItem(item.queueItemId) },
                                playerViewModel = playerViewModel,
                                onDragStart = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    draggedIndex = index
                                    targetIndex = index
                                    dragOffset = 0f
                                },
                                onDrag = { change ->
                                    dragOffset += change

                                    val itemHeightPx = 64.dp.value * 2.75f
                                    val newTarget = (draggedIndex + (dragOffset / itemHeightPx).roundToInt())
                                        .coerceIn(0, localItems.size - 1)

                                    if (newTarget != targetIndex) {
                                        val mutable = localItems.toMutableList()
                                        val moved = mutable.removeAt(targetIndex)
                                        mutable.add(newTarget, moved)
                                        localItems = mutable

                                        val direction = if (newTarget > targetIndex) -1 else 1
                                        dragOffset += direction * itemHeightPx
                                        draggedIndex = newTarget
                                        targetIndex = newTarget
                                    }
                                },
                                onDragEnd = {
                                    val originalIndex = queueItems.indexOfFirst {
                                        it.queueItemId == localItems[draggedIndex].queueItemId
                                    }
                                    val shift = draggedIndex - originalIndex
                                    if (shift != 0) {
                                        playerViewModel.moveQueueItem(
                                            localItems[draggedIndex].queueItemId,
                                            shift
                                        )
                                    }
                                    draggedIndex = -1
                                    dragOffset = 0f
                                    targetIndex = -1
                                },
                                onDragCancel = {
                                    draggedIndex = -1
                                    dragOffset = 0f
                                    targetIndex = -1
                                    localItems = queueItems
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    item: QueueItem,
    isCurrent: Boolean,
    showDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    playerViewModel: PlayerViewModel,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val backgroundColor = if (isCurrent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            .compositeOver(MaterialTheme.colorScheme.background)
    } else {
        MaterialTheme.colorScheme.background
    }
    val primaryColor = MaterialTheme.colorScheme.primary
    val accentWidth = 3.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .drawBehind {
                if (isCurrent) {
                    drawRect(
                        color = primaryColor,
                        topLeft = Offset.Zero,
                        size = Size(accentWidth.toPx(), size.height),
                    )
                }
            }
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        val thumbnailShape = RoundedCornerShape(8.dp)
        Box(
            modifier = Modifier.size(48.dp).clip(thumbnailShape),
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
                ImagePlaceholder(
                    icon = Icons.Default.MusicNote,
                    size = 48.dp,
                    shape = thumbnailShape,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
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

        // Delete button — hidden for currently playing track
        if (showDelete) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(36.dp))
        }

        // Drag handle
        Icon(
            Icons.Default.DragHandle,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .size(36.dp)
                .padding(8.dp)
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { onDragStart() },
                        onDrag = { change, offset ->
                            change.consume()
                            onDrag(offset.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragCancel() }
                    )
                }
        )
    }
}

private fun formatDuration(seconds: Int): String {
    if (seconds <= 0) return ""
    val min = seconds / 60
    val sec = seconds % 60
    return "$min:${sec.toString().padStart(2, '0')}"
}
