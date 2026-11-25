package app.localtube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import app.localtube.data.Video
import app.localtube.data.formatTime
import app.localtube.data.formatViews
import app.localtube.player.Playback
import app.localtube.tubeApp
import coil.compose.AsyncImage

@UnstableApi
val LocalPlayback = staticCompositionLocalOf<Playback> { error("Playback not provided") }

/** Play [videos] from [index] and show the player. */
/** Close whatever is open and search YouTube for this text (used by tags). */
val LocalOpenSearch = staticCompositionLocalOf<(String) -> Unit> { { } }

val LocalPlay = staticCompositionLocalOf<(List<Video>, Int) -> Unit> { { _, _ -> } }

data class MenuAction(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val run: () -> Unit)

@Composable
fun Thumb(video: Video, modifier: Modifier = Modifier, progress: Float = 0f, playing: Boolean = false) {
    Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        if (video.isLocal) {
            // a frame taken from the file (made once, in the background); until then, an icon
            val tapp = androidx.compose.ui.platform.LocalContext.current.tubeApp
            val shot = remember(video.id, tapp.thumbs.version) { tapp.thumbs.existing(video) }
            androidx.compose.runtime.LaunchedEffect(video.id) { if (shot == null) tapp.thumbs.ensure(video) }
            if (shot != null) AsyncImage(shot, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(if (app.localtube.data.isAudioFile(video.title)) Icons.Filled.MusicNote else Icons.Filled.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center).size(40.dp))
        } else AsyncImage(video.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (video.live) {
            Text("LIVE", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD32F2F)).padding(horizontal = 5.dp, vertical = 1.dp))
        } else if (video.duration > 0) {
            Text(formatTime(video.duration), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp))
        }
        if (playing) {
            Icon(Icons.Filled.QueueMusic, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xCC000000)).padding(3.dp))
        }
        if (progress > 0f) {
            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                trackColor = Color(0x55FFFFFF))
        }
    }
}

@Composable
fun MoreMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.danger) MaterialTheme.colorScheme.error else Color.Unspecified) },
                    leadingIcon = a.icon?.let { ic -> { Icon(ic, null, tint = if (a.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } },
                    onClick = { open = false; a.run() },
                )
            }
        }
    }
}

/** Actions every video offers: play next, add to the queue, save to a playlist. */
@Composable
@UnstableApi
fun videoActions(video: Video, onSave: (Video) -> Unit, extra: List<MenuAction> = emptyList()): List<MenuAction> {
    val pb = LocalPlayback.current
    return listOf(
        MenuAction("Play next", Icons.Filled.SkipNext) { pb.playNext(video) },
        MenuAction("Add to queue", Icons.Filled.QueueMusic) { pb.addToQueue(video) },
        MenuAction("Save to playlist…", Icons.Filled.PlaylistAdd) { onSave(video) },
    ) + extra
}

/** A video in a list: thumbnail, title, channel and views, and a menu. */
/** A large video card, like the YouTube app's feed: full-width thumbnail, then title, channel and views. */
@Composable
fun VideoCard(video: Video, onClick: () -> Unit, actions: List<MenuAction>, modifier: Modifier = Modifier, progress: Float = 0f) {
    Column(modifier.fillMaxWidth().focusRing(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(bottom = 14.dp)) {
        Thumb(video, Modifier.fillMaxWidth().padding(horizontal = 12.dp), progress)
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val sub = listOf(video.channel, if (video.live) "LIVE" else formatViews(video.views)).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            if (actions.isNotEmpty()) MoreMenu(actions)
        }
    }
}

@Composable
fun VideoRow(
    video: Video,
    onClick: () -> Unit,
    actions: List<MenuAction>,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    highlighted: Boolean = false,
    subtitle: String? = null,
) {
    Row(
        modifier.fillMaxWidth()
            .focusRing()
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Thumb(video, Modifier.width(148.dp), progress, playing = highlighted)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val sub = subtitle ?: listOf(video.channel, formatViews(video.views)).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
        if (actions.isNotEmpty()) MoreMenu(actions)
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

@Composable
fun NameDialog(title: String, initial: String = "", confirm: String = "Save", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(80) }, label = { Text("Name") }, singleLine = true, keyboardOptions = KeyboardOptions.Default) },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) { onDone(text.trim()); onDismiss() } }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add or remove [video] from any playlist, or start a new one. */
@Composable
fun SaveToPlaylistDialog(video: Video, onDismiss: () -> Unit) {
    val store = LocalContext.current.tubeApp.store
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("New playlist", confirm = "Create", onDismiss = { creating = false }) { store.createPlaylist(it, listOf(video)) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to playlist") },
        text = {
            LazyColumn {
                item { ListItem(headlineContent = { Text("New playlist…") }, leadingContent = { Icon(Icons.Filled.PlaylistAdd, null) }, modifier = Modifier.focusRing().clickable { creating = true }) }
                items(store.playlists.toList(), key = { it.id }) { p ->
                    val has = p.items.any { it.id == video.id }
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.items.size} videos") },
                        leadingContent = { if (has) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.focusRing().clickable { store.updatePlaylist(p.copy(items = if (has) p.items.filter { it.id != video.id } else p.items + video)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
