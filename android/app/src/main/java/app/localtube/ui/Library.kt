package app.localtube.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.localtube.data.Playlist
import app.localtube.data.Video
import app.localtube.data.formatTime
import app.localtube.data.qualityLabel
import app.localtube.tubeApp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val QUALITIES = listOf(360, 480, 720, 1080, 1440, 2160)

/** Playlists and watch history. [openId] is the playlist being viewed, if any. */
@Composable
@UnstableApi
fun LibraryScreen(openId: String?, setOpenId: (String?) -> Unit, onSave: (Video) -> Unit) {
    val app = LocalContext.current.tubeApp
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val open = openId?.let { id -> app.store.playlists.firstOrNull { it.id == id } }
    if (open != null) { PlaylistDetail(open, onBack = { setOpenId(null) }, onSave = onSave); return }

    Column(Modifier.fillMaxSize()) {
        Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Playlists") })
            Tab(tab == 1, { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) PlaylistsTab(setOpenId) else HistoryTab(onSave)
    }
}

@Composable
@UnstableApi
private fun PlaylistsTab(open: (String?) -> Unit) {
    val store = LocalContext.current.tubeApp.store
    val play = LocalPlay.current
    var creating by remember { mutableStateOf(false) }
    if (creating) NameDialog("New playlist", confirm = "Create", onDismiss = { creating = false }) { open(store.createPlaylist(it).id) }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        if (store.playlists.isEmpty()) {
            EmptyState("No playlists yet", "Use “Save to playlist” on any video, or create one here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(store.playlists.toList(), key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("${p.items.size} video${if (p.items.size == 1) "" else "s"}") },
                        leadingContent = { p.items.firstOrNull()?.let { Thumb(it, Modifier.width(96.dp)) } },
                        trailingContent = { IconButton({ if (p.items.isNotEmpty()) play(p.items, 0) }) { Icon(Icons.Filled.PlayArrow, "Play ${p.name}") } },
                        modifier = Modifier.focusRing().clickable { open(p.id) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New playlist") },
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
@UnstableApi
fun PlaylistDetail(p: Playlist, onBack: () -> Unit, onSave: (Video) -> Unit) {
    val store = LocalContext.current.tubeApp.store
    val pb = LocalPlayback.current
    val play = LocalPlay.current
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (renaming) NameDialog("Rename playlist", p.name, onDismiss = { renaming = false }) { store.updatePlaylist(p.copy(name = it)) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${p.name}”?") },
        text = { Text("The videos themselves aren't affected.") },
        confirmButton = { TextButton({ store.deletePlaylist(p.id); confirmDelete = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp, 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.items.size} video${if (p.items.size == 1) "" else "s"} · ${formatTime(p.items.sumOf { it.duration })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MoreMenu(listOf(
                MenuAction("Add all to queue", Icons.Filled.QueueMusic) { p.items.forEach { pb.addToQueue(it) } },
                MenuAction("Rename", Icons.Filled.Edit) { renaming = true },
                MenuAction("Delete playlist", Icons.Filled.Delete, danger = true) { confirmDelete = true },
            ))
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ if (p.items.isNotEmpty()) play(p.items, 0) }, enabled = p.items.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, null); Text("  Play all") }
            FilledTonalButton({ if (p.items.isNotEmpty()) play(p.items.shuffled(), 0) }, enabled = p.items.isNotEmpty()) { Icon(Icons.Filled.Shuffle, null); Text("  Shuffle") }
        }
        if (p.items.isEmpty()) {
            EmptyState("This playlist is empty", "Use “Save to playlist” on any video to add it here.")
        } else {
            LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
                items(p.items.size, key = { p.items[it].id }) { i ->
                    val v = p.items[i]
                    val extra = buildList {
                        if (i > 0) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { store.updatePlaylist(p.copy(items = p.items.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })) })
                        if (i < p.items.size - 1) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { store.updatePlaylist(p.copy(items = p.items.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) })) })
                        add(MenuAction("Remove from playlist", Icons.Filled.Delete, danger = true) { store.updatePlaylist(p.copy(items = p.items.filterIndexed { j, _ -> j != i })) })
                    }
                    VideoRow(v, onClick = { play(p.items, i) }, actions = videoActions(v, onSave, extra))
                }
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.now())
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())
        else -> d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }
}

@Composable
@UnstableApi
private fun HistoryTab(onSave: (Video) -> Unit) {
    val app = LocalContext.current.tubeApp
    val store = app.store
    val play = LocalPlay.current
    var filter by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear watch history?") },
        text = { Text("This removes the list of videos you've played. Where you stopped in each video is kept.") },
        confirmButton = { TextButton({ store.clearHistory(); confirmClear = false }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )

    val rows = store.history.filter { filter.isBlank() || it.video.title.contains(filter, true) || it.video.channel.contains(filter, true) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp, 8.dp, 0.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Search watch history") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton({ if (store.history.isNotEmpty()) confirmClear = true }) { Icon(Icons.Filled.Delete, "Clear history") }
        }
        if (!store.saveHistory) Text("Watch history is turned off in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp, 8.dp))
        if (rows.isEmpty()) {
            EmptyState(if (store.history.isEmpty()) "Nothing here yet" else "No matches", if (store.history.isEmpty()) "Videos you play will be listed here, newest first." else "Nothing in your history matches that.")
        } else {
            var lastDay = ""
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                rows.forEach { h ->
                    val day = dayLabel(h.at)
                    if (day != lastDay) { lastDay = day; item(key = "d-$day-${h.video.id}") { Text(day, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(20.dp, 14.dp, 20.dp, 4.dp)) } }
                    item(key = h.video.id) {
                        VideoRow(
                            h.video, onClick = { play(listOf(h.video), 0) },
                            actions = videoActions(h.video, onSave, listOf(MenuAction("Remove from history", Icons.Filled.Delete, danger = true) { store.removeHistory(h.video.id) })),
                            progress = progressOf(app, h.video),
                        )
                    }
                }
            }
        }
    }
}

@Composable
@UnstableApi
fun SettingsScreen(onChangeServer: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val s = app.store
    val pb = LocalPlayback.current

    fun changed(streams: Boolean) { s.saveSettings(); pb.reload(streams) }
    val ctx = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset recommendations?") },
        text = { Text("Home stops using what you watched until now and starts fresh from what you watch next. Your watch history is kept.") },
        confirmButton = { TextButton({ s.resetRecommendations(); app.feed.reset(); confirmReset = false; android.widget.Toast.makeText(ctx, "Recommendations reset", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Reset") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))

        SettingHeader("Videos come from")
        ChipRow("Source", listOf("device" to (if (app.useTv) "This TV" else "This phone"), "pc" to "My PC"), s.source) { pick ->
            when {
                pick == s.source -> Unit
                pick == "pc" && s.server.isBlank() -> onChangeServer() // needs the PC's address first
                else -> { s.source = pick; s.saveConnection(); changed(true) }
            }
        }
        if (s.source == "pc") {
            ListItem(
                headlineContent = { Text(s.server) },
                supportingContent = { Text("Your PC running Kinora") },
                trailingContent = { TextButton(onChangeServer) { Text("Change") } },
            )
        } else {
            Text(
                "The ${if (app.useTv) "TV" else "phone"} looks videos up itself: nothing to set up, and the PC can be off. Pick My PC for 1440p and 4K, or if a video won't play.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp),
            )
        }
        HorizontalDivider()

        SettingHeader("Appearance")
        ChipRow("Theme", listOf("system" to "System", "light" to "Light", "dark" to "Dark"), s.theme) { s.theme = it; s.saveSettings() }
        ChipRow("Layout", listOf("auto" to "Automatic", "phone" to "Phone", "tv" to "TV"), s.layout) { s.layout = it; s.saveSettings() }
        ListItem(
            headlineContent = { Text("Dynamic color") },
            supportingContent = { Text("Match your wallpaper (Android 12 and newer)") },
            trailingContent = { Switch(s.dynamicColor, { s.dynamicColor = it; s.saveSettings() }) },
        )
        HorizontalDivider()

        SettingHeader("Recommendations")
        ListItem(
            headlineContent = { Text("Reset recommendations") },
            supportingContent = { Text("Tired of the same kind of videos? Start fresh. Your history stays.") },
            trailingContent = { TextButton({ confirmReset = true }) { Text("Reset") } },
        )
        HorizontalDivider()

        SettingHeader("Playback defaults")
        ChipRow("Stream type", listOf("video" to "Video", "audio" to "Audio only"), s.stream) { s.stream = it; changed(true) }
        ChipRow("Video quality", QUALITIES.map { it.toString() to qualityLabel(it) }, s.quality.toString()) { s.quality = it.toInt(); changed(true) }
        Text("1440p and 4K are VP9: only for phones that decode it smoothly, and they use a lot of Wi-Fi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp))
        ListItem(
            headlineContent = { Text("Autoplay next") },
            supportingContent = { Text("Continue with the next item in the queue") },
            trailingContent = { Switch(s.autoplay, { s.autoplay = it; changed(false) }) },
        )
        ListItem(
            headlineContent = { Text("Save watch history") },
            trailingContent = { Switch(s.saveHistory, { s.saveHistory = it; s.saveSettings() }) },
        )
        Text("These apply to everything. The tune button in the player overrides stream, quality and autoplay for what you're watching now, without changing these.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp, 4.dp))
    }
}

@Composable
private fun SettingHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp))
}

@Composable
fun ChipRow(label: String, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) -> FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(text) }) }
        }
    }
}
