package app.kinora.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import app.kinora.TubeApp
import app.kinora.data.ApiError
import app.kinora.data.Store
import app.kinora.data.Video
import app.kinora.tubeApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Optional: use your PC's Kinora server instead of looking videos up on the phone. Enter its address and the PIN it shows. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(app.store.server) }
    var pin by remember { mutableStateOf(app.store.pin) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        busy = true; error = null
        scope.launch {
            try {
                app.api.ping(server, pin.trim())
                app.store.server = Store.normalize(server); app.store.pin = pin.trim(); app.store.source = "pc"; app.store.saveConnection()
                onDone()
            } catch (e: ApiError) {
                error = e.message
            }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("Kinora", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text("Use the Kinora server on your PC", style = MaterialTheme.typography.titleMedium)
        Text(
            "Optional. By default the phone looks videos up itself and needs nothing else. If you want 1440p or 4K, or the phone's lookup stops working, run  npm run lan  on the PC. It prints the PC's address (like http://192.168.1.20:3000) and a 6-digit PIN. The phone must be on the same Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(server, { server = it }, label = { Text("PC address") }, placeholder = { Text("192.168.1.20:3000") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (server.isNotBlank() && pin.isNotBlank() && !busy) connect() }), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::connect, enabled = server.isNotBlank() && pin.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Connect")
        }
        TextButton(onClick = { app.store.source = "device"; app.store.saveConnection(); onDone() }, modifier = Modifier.fillMaxWidth()) {
            Text("Use this phone instead")
        }
    }
}

/** Continue watching, your playlists and what you watched recently. */
@Composable
@UnstableApi
fun HomeScreen(onSave: (Video) -> Unit, openPlaylist: (String) -> Unit) {
    val app = LocalContext.current.tubeApp
    val play = LocalPlay.current
    val feed = app.feed
    val history = app.store.history
    val playlists = app.store.playlists
    val resumable = history.filter { !it.done && app.store.resumeSec(it.video.id) > 15 && it.video.duration > 0 }.take(10)
    androidx.compose.runtime.LaunchedEffect(history.firstOrNull()?.video?.id, app.store.source) { feed.refresh() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (resumable.isNotEmpty()) {
            item { SectionTitle("Continue watching") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resumable, key = { it.video.id }) { h ->
                        val v = h.video
                        Column(Modifier.width(220.dp).focusRing().clickable { play(listOf(v), 0) }) {
                            Thumb(v, Modifier.fillMaxWidth(), progress = app.store.resumeSec(v.id).toFloat() / v.duration)
                            Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            Text(v.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { SectionTitle(if (history.any { !it.video.isLocal && it.at > app.store.recsSince }) "Recommended for you" else "Popular right now") }
        when {
            feed.recommended.isEmpty() && feed.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            feed.recommended.isEmpty() -> item {
                EmptyState("No suggestions yet", feed.error ?: "Search for something and watch it: suggestions follow what you watch.",
                    action = { TextButton({ feed.refresh(force = true) }) { Text("Try again") } })
            }
            else -> items(feed.recommended, key = { "rec-" + it.id }) { v ->
                VideoCard(v, onClick = { play(feed.recommended, feed.recommended.indexOf(v).coerceAtLeast(0)) }, actions = videoActions(v, onSave), progress = progressOf(app, v))
            }
        }
        if (feed.fromChannels.isNotEmpty()) {
            item { SectionTitle("From channels you watch") }
            items(feed.fromChannels, key = { "ch-" + it.id }) { v ->
                VideoCard(v, onClick = { play(feed.fromChannels, feed.fromChannels.indexOf(v).coerceAtLeast(0)) }, actions = videoActions(v, onSave), progress = progressOf(app, v))
            }
        }
        if (playlists.isNotEmpty()) {
            item { SectionTitle("Your playlists") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists.toList(), key = { it.id }) { p ->
                        Column(Modifier.width(180.dp).focusRing().clickable { openPlaylist(p.id) }) {
                            val first = p.items.firstOrNull()
                            if (first != null) Thumb(first, Modifier.fillMaxWidth())
                            Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                            Text("${p.items.size} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 8.dp))
}

/** Search state lives outside the screen so results survive switching tabs. */
class SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Video>?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    private var n = 20
    private var gen = 0 // bumped by every new search and by reset(), so a slow answer to an old one is dropped

    /** Back to an empty search: no text, no results, nothing loading. */
    fun reset() { gen++; query = ""; results = null; loading = false; error = null; n = 20 }

    fun run(app: TubeApp, scope: CoroutineScope, more: Boolean = false) {
        val q = query.trim()
        if (q.isEmpty()) return
        n = if (more) (n + 20).coerceAtMost(60) else 20
        if (!more) app.store.addRecentSearch(q)
        loading = true; error = null
        if (!more) results = null
        val g = ++gen
        scope.launch {
            try { val r = app.api.search(q, n); if (g == gen) results = r } catch (e: ApiError) { if (g == gen) error = e.message }
            if (g == gen) loading = false
        }
    }

    val canLoadMore get() = (results?.size ?: 0) >= n && n < 60
}

private val VIDEO_ID = Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([\\w-]{11})")

@Composable
@UnstableApi
fun SearchScreen(state: SearchState, onSave: (Video) -> Unit) {
    val app = LocalContext.current.tubeApp
    val play = LocalPlay.current
    val scope = rememberCoroutineScope()

    fun submit() {
        val text = state.query.trim()
        val id = VIDEO_ID.find(text)?.groupValues?.get(1) ?: text.takeIf { Regex("[\\w-]{11}").matches(it) }
        if (id != null) play(listOf(Video(id, id)), 0) else state.run(app, scope)
    }

    val context = LocalContext.current
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val said = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == android.app.Activity.RESULT_OK && !said.isNullOrEmpty()) { state.query = said; submit() }
    }
    fun startVoice() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search YouTube")
        try { voice.launch(intent) }
        catch (e: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(context, "No speech recognition app on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            state.query, { state.query = it },
            placeholder = { Text("Search YouTube, or paste a link") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.query.isNotEmpty()) IconButton({ state.query = ""; state.results = null }) { Icon(Icons.Filled.Clear, "Clear") }
                    IconButton({ startVoice() }) { Icon(Icons.Filled.Mic, "Search by voice") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        )
        val results = state.results
        when {
            state.loading && results == null -> Row(Modifier.fillMaxWidth().padding(40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            state.error != null -> EmptyState("Search failed", state.error ?: "", action = { TextButton({ state.run(app, scope) }) { Text("Try again") } })
            results == null -> EmptyState("Find something to watch", "Type above and search.")
            results.isEmpty() -> EmptyState("No results", "Try different words.")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(results, key = { it.id }) { v ->
                    VideoCard(v, onClick = { play(listOf(v), 0) }, actions = videoActions(v, onSave), progress = progressOf(app, v))
                }
                if (state.canLoadMore) item {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                        if (state.loading) CircularProgressIndicator() else TextButton({ state.run(app, scope, more = true) }) { Text("Show more results") }
                    }
                }
            }
        }
    }
}

fun progressOf(app: TubeApp, v: Video): Float {
    if (app.store.history.any { it.video.id == v.id && it.done }) return 1f
    val sec = app.store.resumeSec(v.id)
    return if (sec > 0 && v.duration > 0) sec.toFloat() / v.duration else 0f
}
