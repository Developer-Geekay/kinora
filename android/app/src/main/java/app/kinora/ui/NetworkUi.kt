package app.kinora.ui

import app.kinora.ui.tv.TvCard
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.kinora.data.ApiError
import app.kinora.data.LanScan
import app.kinora.data.SmbEntry
import app.kinora.data.SmbServer
import app.kinora.data.Video
import app.kinora.data.isAudioFile
import app.kinora.data.isMediaFile
import app.kinora.data.smbVideoId
import app.kinora.tubeApp
import kotlinx.coroutines.launch

/** Where the Network screen is: no server chosen yet, or inside a server at [path] ("" = its list of shares). */
class NetworkState {
    var server by mutableStateOf<SmbServer?>(null)
    var path by mutableStateOf("")
    val canGoUp get() = server != null
    var sortKey by mutableStateOf("name")   // name | date | size
    var sortDesc by mutableStateOf(false)
    fun sortBy(key: String) { if (sortKey == key) sortDesc = !sortDesc else { sortKey = key; sortDesc = key != "name" } } // newest / biggest first
    /** Set when the screen's content changes, so the TV remote's focus is put back on the new list (nothing on a touch screen). */
    var restoreFocus by mutableStateOf(false)
    fun up() { restoreFocus = true; if (path.isEmpty()) server = null else path = path.substringBeforeLast('/', "") }
    fun open(s: SmbServer) { restoreFocus = true; server = s; path = "" }
}

/**
 * Files on computers and NAS boxes on the home network: add a server (or find one), browse its shares and folders, play.
 * [entry] is where the TV's remote lands when it moves in from the side rail.
 */
@Composable
@UnstableApi
fun NetworkScreen(state: NetworkState, entry: FocusRequester? = null) {
    val s = state.server
    if (s == null) ServerList(state, entry) else Browser(state, s, entry)
}

private fun Modifier.entryIf(first: Boolean, entry: FocusRequester?) = if (first && entry != null) focusRequester(entry) else this

// ---------------------------------------------------------------------------------------------------------------------
// the list of saved servers
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ServerList(state: NetworkState, entry: FocusRequester?) {
    val app = LocalContext.current.tubeApp
    val scope = rememberCoroutineScope()
    val servers = app.store.smbServers
    var editing by remember { mutableStateOf<SmbServer?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }       // non-null = the add dialog is open (value = a host to prefill)
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { if (state.restoreFocus) { state.restoreFocus = false; if (entry != null) runCatching { entry.requestFocus() } } }

    // TV: a dialog that closes (or a removed server) would leave the remote's focus on the side menu. Put it back in the
    // list: on the server just saved, else on the button the dialog was opened from.
    val list = rememberLazyListState()
    val target = remember { FocusRequester() }
    val addBtn = remember { FocusRequester() }
    val findBtn = remember { FocusRequester() }
    var focusOn by remember { mutableStateOf<String?>(null) } // a server's id, ADD or FIND
    LaunchedEffect(focusOn) {
        val want = focusOn ?: return@LaunchedEffect
        if (entry != null) {
            val row = servers.indexOfFirst { it.id == want }
            if (row >= 0) list.scrollToItem(row) // the row above stays in view
            delay(250) // after the closing dialog has handed the focus back to the screen
            runCatching { when (want) { ADD -> addBtn; FIND -> findBtn; else -> target }.requestFocus() }
        }
        focusOn = null
    }

    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Network", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp, 16.dp, 24.dp, 4.dp))
            Text(
                "Play videos and music from a computer or NAS on your home network (Windows shares, Samba, most NAS boxes). They stay on your network: nothing is uploaded.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp, 0.dp, 24.dp, 12.dp).widthIn(max = 760.dp),
            )
        }
        itemsIndexed(servers, key = { _, sv -> sv.id }) { i, sv ->
            ListItem(
                headlineContent = { Text(sv.name) },
                supportingContent = { Text(sv.host + if (sv.user.isNotBlank()) " · ${sv.user}" else " · guest") },
                leadingContent = { Icon(Icons.Filled.Dns, null) },
                trailingContent = {
                    Row {
                        IconButton({ editing = sv }) { Icon(Icons.Filled.Edit, "Edit ${sv.name}") }
                        IconButton({
                            val next = servers.getOrNull(i + 1) ?: servers.getOrNull(i - 1)
                            app.smb.forget(sv.id); app.store.removeSmbServer(sv.id)
                            focusOn = next?.id ?: ADD
                        }) { Icon(Icons.Filled.Delete, "Remove ${sv.name}") }
                    }
                },
                modifier = Modifier.entryIf(i == 0, entry).then(if (sv.id == focusOn) Modifier.focusRequester(target) else Modifier).focusRing().clickable { state.open(sv) },
            )
        }
        item {
            Row(Modifier.padding(24.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button({ adding = "" }, Modifier.entryIf(servers.isEmpty(), entry).focusRequester(addBtn).focusRing(PillShape)) { Text("Add server") }
                // stays enabled while looking (a disabled button would drop the remote's focus); a second press does nothing
                OutlinedButton(
                    {
                        if (!scanning) {
                            scanning = true; found = null
                            scope.launch { found = LanScan.findSmbHosts(); scanning = false }
                        }
                    },
                    Modifier.focusRequester(findBtn).focusRing(PillShape),
                ) {
                    Box(Modifier.size(18.dp), Alignment.Center) {
                        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(if (scanning) "Looking…" else "Find network")
                }
            }
        }
    }

    found?.let { hosts ->
        val close = { found = null; focusOn = FIND }
        AlertDialog(
            onDismissRequest = close,
            title = { Text(if (hosts.isEmpty()) "No servers found" else "Servers on your network") },
            text = {
                if (hosts.isEmpty()) Text("Nothing answered on the SMB port. Check that file sharing is on and that this device is on the same Wi-Fi or network, or add the address by hand.")
                else Column {
                    hosts.forEach { h ->
                        ListItem(
                            headlineContent = { Text(h) },
                            supportingContent = { Text(if (servers.any { it.host == h }) "Already added" else "Tap to add") },
                            leadingContent = { Icon(Icons.Filled.Dns, null) },
                            modifier = Modifier.focusRing().clickable { found = null; adding = h },
                        )
                    }
                }
            },
            confirmButton = { TextButton(close) { Text("Close") } },
        )
    }
    adding?.let { host -> ServerDialog(initial = null, prefillHost = host, onDismiss = { saved -> adding = null; focusOn = saved ?: ADD }) }
    editing?.let { sv -> ServerDialog(initial = sv, prefillHost = "", onDismiss = { saved -> editing = null; focusOn = saved ?: sv.id }) }
}

private const val ADD = "add"
private const val FIND = "find"

@Composable
private fun ServerDialog(initial: SmbServer?, prefillHost: String, onDismiss: (savedId: String?) -> Unit) {
    val app = LocalContext.current.tubeApp
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: prefillHost) }
    var user by remember { mutableStateOf(initial?.user ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        val h = host.trim().removePrefix("smb://").removePrefix("\\\\").trim('/', '\\')
        if (h.isEmpty()) { error = "Enter the computer's address, like 192.168.1.20 or NAS."; return }
        val server = SmbServer(
            id = initial?.id ?: "s" + System.currentTimeMillis().toString(36),
            name = name.trim().ifBlank { h }, host = h, user = user.trim(), password = password, domain = domain.trim(),
        )
        if (busy) return
        busy = true
        val retry = error != null // after one failure the next press saves anyway (the computer may just be off right now)
        scope.launch {
            try {
                if (!retry) app.smb.test(server)
                app.smb.forget(server.id); app.store.saveSmbServer(server); onDismiss(server.id)
            } catch (e: ApiError) {
                error = e.message + " Press Save again to keep it anyway."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss(null) },
        title = { Text(if (initial == null) "Add a server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // above the fields: under them it was cut off on a TV, where the form fills the whole height
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(host, { host = it; error = null }, label = { Text("Address") }, placeholder = { Text("192.168.1.20 or NAS") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it; error = null }, label = { Text("User name (blank = guest)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(domain, { domain = it }, label = { Text("Domain / workgroup (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            // the spinner sits where the label was, so the button keeps its size while connecting
            Button(::save, Modifier.focusRing(PillShape)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Save", Modifier.alpha(if (busy) 0f else 1f))
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                }
            }
        },
        dismissButton = { TextButton({ onDismiss(null) }, enabled = !busy) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------------------------------------------------
// inside a server
// ---------------------------------------------------------------------------------------------------------------------

/** Folders first (always by name), then files by the chosen key. */
private fun sortEntries(all: List<SmbEntry>, key: String, desc: Boolean): List<SmbEntry> {
    val byName = compareBy<SmbEntry> { it.name.lowercase() }
    val dirs = all.filter { it.isDir }.sortedWith(if (key == "name" && desc) byName.reversed() else byName)
    val cmp: Comparator<SmbEntry> = when (key) {
        "date" -> compareBy<SmbEntry> { it.modified }.thenBy { it.name.lowercase() }
        "size" -> compareBy<SmbEntry> { it.size }.thenBy { it.name.lowercase() }
        else -> byName
    }
    val files = all.filter { !it.isDir }.sortedWith(if (desc) cmp.reversed() else cmp)
    return dirs + files
}

/** Name / Date / Size. Pressing the one already chosen flips the direction. */
@Composable
private fun SortRow(state: NetworkState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("name" to "Name", "date" to "Date", "size" to "Size").forEach { (k, label) ->
            val on = state.sortKey == k
            FocusChip(
                selected = on, onClick = { state.sortBy(k) },
                label = { Text(label) },
                leadingIcon = if (on) ({ Icon(if (state.sortDesc) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward, null) }) else null,
            )
        }
    }
}

@Composable
@UnstableApi
private fun Browser(state: NetworkState, server: SmbServer, entry: FocusRequester?) {
    val app = LocalContext.current.tubeApp
    val play = LocalPlay.current
    val path = state.path
    var entries by remember(server.id, path) { mutableStateOf<List<SmbEntry>?>(null) }
    var error by remember(server.id, path) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val upRow = remember { FocusRequester() }

    LaunchedEffect(server.id, path, attempt) {
        entries = null; error = null
        try { entries = app.smb.list(server, path) } catch (e: ApiError) { error = e.message }
    }
    // the row you pressed is gone from the screen: put the remote's focus back on the new list (TV only)
    LaunchedEffect(entries) {
        if (state.restoreFocus && entries != null) { state.restoreFocus = false; if (entry != null) runCatching { upRow.requestFocus() } }
    }

    val sorted = remember(entries, state.sortKey, state.sortDesc) { sortEntries(entries.orEmpty(), state.sortKey, state.sortDesc) }
    val files = sorted.filter { !it.isDir && isMediaFile(it.name) }
    val crumbs = listOf(server.name) + path.split('/').filter { it.isNotEmpty() }
    fun open(e: SmbEntry) {
        if (e.isDir) { state.restoreFocus = true; state.path = e.path }
        else {
            val videos = files.map { f -> Video(smbVideoId(server, f.path), f.name.substringBeforeLast('.', f.name), server.name) }
            play(videos, files.indexOfFirst { it.path == e.path }.coerceAtLeast(0))
        }
    }

    if (entry != null) { // TV: cards with thumbnails, in a grid
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(240.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val full: androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope.() -> androidx.compose.foundation.lazy.grid.GridItemSpan =
                { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }
            item(span = full) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(crumbs.joinToString("  ›  "), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    SortRow(state)
                }
            }
            item { TvTile(Icons.Filled.ArrowUpward, if (path.isEmpty()) "All servers" else "Up", "", Modifier.focusRequester(upRow).focusRequester(entry)) { state.up() } }
            when {
                error != null -> item(span = full) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Couldn't open this", style = MaterialTheme.typography.titleMedium)
                        Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton({ attempt++ }, Modifier.focusRing(PillShape)) { Text("Try again") }
                    }
                }
                entries == null -> item(span = full) { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
                sorted.isEmpty() -> item(span = full) {
                    Text(if (path.isEmpty()) "This server shares nothing with this login." else "No folders or videos here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                else -> itemsIndexed(sorted, key = { _, e -> e.path }) { _, e ->
                    if (e.isDir) TvTile(if (path.isEmpty()) Icons.Filled.Dns else Icons.Filled.Folder, e.name, "") { open(e) }
                    else {
                        val v = Video(smbVideoId(server, e.path), e.name.substringBeforeLast('.', e.name), listOf(sizeText(e.size), dateText(e.modified)).filter { it.isNotBlank() }.joinToString(" · "))
                        TvCard(v, progressOf(app, v), onClick = { open(e) }, onMenu = {}, width = null)
                    }
                }
            }
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        item {
            Text(crumbs.joinToString("  ›  "), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(24.dp, 16.dp, 24.dp, 4.dp))
            SortRow(state, Modifier.padding(horizontal = 24.dp).horizontalScroll(rememberScrollState()))
        }
        item {
            ListItem(
                headlineContent = { Text(if (path.isEmpty()) "All servers" else "Up") },
                leadingContent = { Icon(Icons.Filled.ArrowUpward, null) },
                modifier = Modifier.focusRequester(upRow).focusRing().clickable { state.up() },
            )
            HorizontalDivider()
        }
        when {
            error != null -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Couldn't open this", style = MaterialTheme.typography.titleMedium)
                    Text(error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton({ attempt++ }, Modifier.focusRing(PillShape)) { Text("Try again") }
                }
            }
            entries == null -> item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
            sorted.isEmpty() -> item {
                Text(if (path.isEmpty()) "This server shares nothing with this login." else "No folders or videos here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            }
            else -> itemsIndexed(sorted, key = { _, e -> e.path }) { _, e ->
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (e.isDir) null else ({ Text(listOf(sizeText(e.size), dateText(e.modified)).filter { it.isNotBlank() }.joinToString(" · ")) }),
                    leadingContent = {
                        Icon(when { e.isDir -> if (path.isEmpty()) Icons.Filled.Dns else Icons.Filled.Folder; isAudioFile(e.name) -> Icons.Filled.MusicNote; else -> Icons.Filled.Movie }, null)
                    },
                    modifier = Modifier.focusRing().clickable { open(e) },
                )
            }
        }
    }
}

/** A folder (or "Up") as a TV card: same size and focus behaviour as a video card. */
@Composable
private fun TvTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val myRequester = remember { FocusRequester() }
    val memory = LocalFocusMemory.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.06f else 1f, label = "tile")
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(myRequester)
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) memory.last = myRequester }
            .clip(shape)
            .background(if (focused) scheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) scheme.primary else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh), Alignment.Center) {
            Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(56.dp))
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}

private fun dateText(ms: Long): String =
    if (ms <= 0) "" else java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(ms))

private fun sizeText(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
