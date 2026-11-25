package app.localtube.ui.tv

import app.localtube.R
import app.localtube.ui.FocusChip
import app.localtube.ui.focusRing
import app.localtube.data.formatTime
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay
import app.localtube.ui.LocalFocusMemory
import app.localtube.ui.FocusMemory
import app.localtube.ui.LocalOpenSearch
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import app.localtube.data.Video
import app.localtube.data.formatViews
import app.localtube.tubeApp
import app.localtube.ui.LocalPlay
import app.localtube.ui.LocalPlayback
import app.localtube.ui.NetworkScreen
import app.localtube.ui.NetworkState
import app.localtube.ui.SaveToPlaylistDialog
import app.localtube.ui.SearchState
import app.localtube.ui.SettingsScreen
import app.localtube.ui.Thumb
import app.localtube.ui.progressOf

/*
 * The TV interface: made for a remote control and a screen across the room.
 *   - a rail on the left (Home, Search, Library, Settings, and "Playing" while something plays)
 *   - rows and grids of big cards; the focused card grows and gets a ring, so it is always clear what OK will do
 *   - the Menu button on the remote saves the focused video to a playlist
 * The phone interface is untouched; MainActivity picks this one on a TV (or when Settings says so).
 */

/** What is playing, when you are not in the player (on a TV only audio is left playing). OK opens the player; the button pauses. */
@Composable
@UnstableApi
private fun TvMiniPlayer(modifier: Modifier, onOpen: () -> Unit) {
    val pb = LocalPlayback.current
    val v = pb.current ?: return
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.widthIn(max = 760.dp).fillMaxWidth(0.8f), shape = RoundedCornerShape(22.dp), color = scheme.surfaceContainerHigh, tonalElevation = 8.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.weight(1f).focusRing(RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen).padding(6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Thumb(v, Modifier.width(100.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(v.channel, if (pb.live) "LIVE" else if (pb.durationMs > 0) "${formatTime(pb.positionMs / 1000)} / ${formatTime(pb.durationMs / 1000)}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            app.localtube.ui.IconButton(pb::toggle, Modifier.size(52.dp)) {
                Icon(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pb.playing) "Pause" else "Play", Modifier.size(30.dp))
            }
        }
    }
}

/** The TV type scale. System font (Roboto) used on purpose: weight and size carry the hierarchy, not decoration. */
private object TvType {
    val wordmark = TextStyle(fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = (-0.5).sp)
    val screen = TextStyle(fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = (-0.3).sp)
    val section = TextStyle(fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val cardTitle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    val meta = TextStyle(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal, letterSpacing = 0.1.sp)
    val rail = TextStyle(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, letterSpacing = 0.3.sp)
}

private val RAIL_OUTLINED: List<ImageVector> = listOf(
    Icons.Outlined.Home, Icons.Outlined.Search, Icons.Outlined.VideoLibrary, Icons.Outlined.Lan, Icons.Outlined.Settings,
)

private val RAIL_ITEMS: List<Pair<ImageVector, String>> = listOf(
    Icons.Filled.Home to "Home",
    Icons.Filled.Search to "Search",
    Icons.Filled.VideoLibrary to "Library",
    Icons.Filled.Lan to "Network",
    Icons.Filled.Settings to "Settings",
)
private const val TAB_SETTINGS = 4
private const val TV_WIDTH_DP = 1280f

@Composable
@UnstableApi
fun TvRoot(onChangeServer: () -> Unit) {
    val pb = LocalPlayback.current
    val app = LocalContext.current.tubeApp
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Video?>(null) }
    val search = remember { SearchState() }
    val net = remember { NetworkState() }

    val entry = remember { FocusRequester() }                 // where Right from the rail lands in the current screen
    val railFocus = remember { List(RAIL_ITEMS.size + 1) { FocusRequester() } }
    var railHasFocus by remember { mutableStateOf(true) }
    var confirmExit by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity

    val play: (List<Video>, Int) -> Unit = { videos, i -> pb.playQueue(videos, i); playerOpen = true }

    // Leaving the player on a TV: a video stops (you're done with the screen); audio keeps playing, with the mini player.
    val closePlayer = { if (!pb.audioPlaying) pb.stopForExit(); playerOpen = false }
    BackHandler(playerOpen) { closePlayer() }
    // Back from inside a screen goes to the rail first (like most TV apps); Back on the rail leaves the app.
    BackHandler(!playerOpen && !railHasFocus) { runCatching { railFocus[tab].requestFocus() } }
    // Back on the rail would leave the app: ask first
    BackHandler(!playerOpen && railHasFocus && !confirmExit && saving == null) { confirmExit = true }
    // a search is forgotten when you leave the Search screen, so it opens empty next time
    LaunchedEffect(tab) { if (tab != 1) search.reset() }
    BackHandler(!playerOpen && !railHasFocus && tab == 3 && net.canGoUp) { net.up() } // last, so it wins: up a folder first
    val memory = remember { FocusMemory() }
    var everOpened by remember { mutableStateOf(false) }
    // When the player closes, go back to the card that started it (not the side rail); the rail is the fallback.
    LaunchedEffect(playerOpen) {
        if (playerOpen) { everOpened = true; return@LaunchedEffect }
        if (everOpened) delay(380) // let the player's fade-out finish first
        val back = memory.last
        val attempt = if (everOpened && back != null) runCatching { back.requestFocus() } else null
        val ok = attempt?.isSuccess == true
        if (!ok) runCatching { railFocus[tab].requestFocus() }
    }

    // TVs report wildly different densities (a Fire TV Stick makes everything look 1.5x too big). Lay the whole TV
    // interface out as if the screen were 1280 dp wide, whatever the TV says.
    val density = LocalDensity.current
    val widthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val openSearch: (String) -> Unit = { q -> playerOpen = false; tab = 1; search.query = q; search.run(app, app.scope) }
    CompositionLocalProvider(LocalPlay provides play, LocalOpenSearch provides openSearch, LocalFocusMemory provides memory, LocalDensity provides Density(widthPx / TV_WIDTH_DP, density.fontScale)) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(Modifier.fillMaxSize()) {
                TvRail(
                    tab = tab,
                    playing = pb.hasMedia,
                    requesters = railFocus,
                    entry = entry,
                    onTab = { tab = it },
                    onOpenPlayer = { playerOpen = true },
                    onRailFocus = { railHasFocus = it },
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        0 -> TvHome(entry, onSave = { saving = it }, goSearch = { tab = 1 })
                        1 -> TvSearch(search, entry, onSave = { saving = it })
                        2 -> TvLibrary(entry, onSave = { saving = it }, goSearch = { tab = 1 })
                        3 -> NetworkScreen(net, entry)
                        else -> SettingsScreen(onChangeServer = onChangeServer)
                    }
                    if (pb.hasMedia && !playerOpen) TvMiniPlayer(Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp), onOpen = { playerOpen = true })
                }
            }
            AnimatedVisibility(playerOpen, enter = fadeIn(), exit = fadeOut()) {
                // the player's own cards (related videos) must not overwrite the screen's remembered card
                CompositionLocalProvider(LocalFocusMemory provides remember { FocusMemory() }) {
                    TvPlayerScreen(onClose = closePlayer, onSave = { saving = it })
                }
            }
            saving?.let { SaveToPlaylistDialog(it) { saving = null } }
            if (confirmExit) ExitConfirm(onStay = { confirmExit = false }, onExit = { confirmExit = false; activity?.finish() })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// rail
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvRail(
    tab: Int,
    playing: Boolean,
    requesters: List<FocusRequester>,
    entry: FocusRequester,
    onTab: (Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onRailFocus: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxHeight().width(112.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 20.dp)
            // Coming back to the rail from a screen (Left, or Back) always lands on the current section, not on
            // whichever item is nearest: focusing a rail item switches screens, so the wrong one would change the screen.
            .focusProperties { enter = { requesters[tab] } }
            .focusGroup()
            .onFocusChanged { onRailFocus(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // what is playing now sits at the top, where it is easy to get back to
        if (playing) {
            TvRailItem(
                icon = Icons.Filled.GraphicEq, label = "Playing", selected = false,
                requester = requesters[RAIL_ITEMS.size], right = FocusRequester.Default,
                selectOnFocus = false, onSelect = onOpenPlayer,
            )
        }
        RAIL_ITEMS.forEachIndexed { i, (filled, label) ->
            TvRailItem(
                icon = if (tab == i) filled else RAIL_OUTLINED[i], label = label, selected = tab == i,
                requester = requesters[i],
                // Right goes into the screen. Settings is an ordinary scrolling list: let Compose pick.
                right = if (i == TAB_SETTINGS) FocusRequester.Default else entry,
                selectOnFocus = true, onSelect = { onTab(i) },
            )
        }
    }
}

@Composable
private fun TvRailItem(
    icon: ImageVector, label: String, selected: Boolean,
    requester: FocusRequester, right: FocusRequester,
    selectOnFocus: Boolean, onSelect: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.width(92.dp)
            .focusRequester(requester)
            .focusProperties { this.right = right }
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus && selectOnFocus) onSelect() }
            .clip(shape)
            .background(when { focused -> scheme.primary; selected -> scheme.secondaryContainer; else -> Color.Transparent })
            .clickable(onClick = onSelect)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val tint = if (focused) scheme.onPrimary else if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
        Icon(icon, label, tint = tint, modifier = Modifier.size(28.dp))
        Text(label, color = tint, style = TvType.rail, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// cards and rows
// ---------------------------------------------------------------------------------------------------------------------

/** A video: thumbnail, title, channel. Grows and gets a ring when focused. The remote's Menu key calls [onMenu]. */
@Composable
internal fun TvCard(
    video: Video, progress: Float, onClick: () -> Unit, onMenu: () -> Unit,
    modifier: Modifier = Modifier, width: Dp? = 250.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val myRequester = remember { FocusRequester() }
    val memory = LocalFocusMemory.current
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "cardScale")
    val shape = RoundedCornerShape(16.dp)
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.let { if (width != null) it.width(width) else it.fillMaxWidth() }
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(myRequester)
            .onFocusChanged { focused = it.hasFocus; if (it.hasFocus) memory.last = myRequester }
            .onKeyEvent { e ->
                if (e.key == Key.Menu && e.type == KeyEventType.KeyUp) { onMenu(); true } else false
            }
            .clip(shape)
            .background(if (focused) scheme.surfaceContainerHighest else Color.Transparent)
            .border(if (focused) 3.dp else 0.dp, if (focused) scheme.primary else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumb(video, Modifier.fillMaxWidth(), progress)
        Text(video.title, style = TvType.cardTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOf(video.channel, formatViews(video.views)).filter { it.isNotBlank() }.joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = TvType.meta, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TvRow(title: String, videos: List<Video>, onSave: (Video) -> Unit, entry: FocusRequester? = null) {
    val app = LocalContext.current.tubeApp
    val play = LocalPlay.current
    Column {
        Text(title, style = TvType.section, modifier = Modifier.padding(start = 44.dp, top = 6.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 36.dp, vertical = 16.dp), // room for the focused card to grow into
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(videos, key = { i, v -> "$i-${v.id}" }) { i, v ->
                TvCard(
                    v, progressOf(app, v), onClick = { play(videos, i) }, onMenu = { onSave(v) },
                    modifier = if (i == 0 && entry != null) Modifier.focusRequester(entry) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun TvEmpty(title: String, text: String, button: String, entry: FocusRequester, onClick: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick, Modifier.focusRequester(entry).padding(top = 8.dp)) { Text(button, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// screens
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun TvHome(entry: FocusRequester, onSave: (Video) -> Unit, goSearch: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val feed = app.feed
    val history = app.store.history
    val resumable = history.filter { !it.done && app.store.resumeSec(it.video.id) > 15 && it.video.duration > 0 }.map { it.video }.take(12)
    LaunchedEffect(history.firstOrNull()?.video?.id, app.store.source) { feed.refresh() }

    val rec = feed.recommended
    val firstRow = when { resumable.isNotEmpty() -> 0; rec.isNotEmpty() -> 1; else -> 2 }
    if (rec.isEmpty() && resumable.isEmpty() && !feed.loading) {
        TvEmpty("Welcome to Kinora", feed.error ?: "Search for a video and suggestions will appear here, based on what you watch.", "Search", entry, goSearch)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
        item {
            Row(Modifier.padding(start = 44.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                Text("Kinora", style = TvType.wordmark, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 14.dp))
            }
        }
        if (resumable.isNotEmpty()) item { TvRow("Continue watching", resumable, onSave, entry = entry.takeIf { firstRow == 0 }) }
        if (rec.isNotEmpty()) {
            val title = if (history.any { !it.video.isLocal }) "Recommended for you" else "Popular right now"
            item { TvRow(title, rec.take(14), onSave, entry = entry.takeIf { firstRow == 1 }) }
            if (rec.size > 14) item { TvRow("More to watch", rec.drop(14), onSave) }
        } else if (feed.loading) {
            item { Box(Modifier.fillMaxWidth().padding(48.dp), Alignment.Center) { CircularProgressIndicator() } }
        }
        if (feed.fromChannels.isNotEmpty()) item { TvRow("From channels you watch", feed.fromChannels, onSave) }
    }
}

@Composable
private fun TvLibrary(entry: FocusRequester, onSave: (Video) -> Unit, goSearch: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val history = app.store.history.map { it.video }.take(60)
    val playlists = app.store.playlists.filter { it.items.isNotEmpty() }
    if (history.isEmpty() && playlists.isEmpty()) {
        TvEmpty("Your library is empty", "Press the Menu button on a video to save it to a playlist.", "Search", entry, goSearch)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 28.dp, bottom = 40.dp)) {
        item { Text("Library", style = TvType.screen, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 44.dp, bottom = 8.dp)) }
        playlists.forEachIndexed { i, p ->
            item(key = "p-${p.id}") { TvRow("${p.name} · ${p.items.size}", p.items, onSave, entry = if (i == 0) entry else null) }
        }
        if (history.isNotEmpty()) item(key = "history") { TvRow("History", history, onSave, entry = if (playlists.isEmpty()) entry else null) }
    }
}

@Composable
private fun ExitConfirm(onStay: () -> Unit, onExit: () -> Unit) {
    val stay = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onStay, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(28.dp)).background(scheme.surfaceContainerHigh).padding(36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Exit Kinora?", style = TvType.screen, color = scheme.onSurface)
            Text("Playback stops when you leave.", style = TvType.cardTitle.copy(fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = scheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DialogButton("Stay", Modifier.weight(1f).focusRequester(stay), onStay)
                DialogButton("Exit", Modifier.weight(1f), onExit)
            }
        }
    }
}

/** Same focus language as the rail: the focused control fills with the accent, the others sit quietly on the surface. */
@Composable
private fun DialogButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "dlgbtn")
    Box(
        modifier.height(60.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.hasFocus }
            .clip(RoundedCornerShape(16.dp))
            .background(if (focused) scheme.primary else scheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = TvType.section.copy(fontSize = 20.sp), color = if (focused) scheme.onPrimary else scheme.onSurface) }
}

private val VIDEO_LINK = Regex("(?:v=|youtu\\.be/|shorts/|embed/|live/)([\\w-]{11})")

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TvSearch(state: SearchState, entry: FocusRequester, onSave: (Video) -> Unit) {
    val app = LocalContext.current.tubeApp
    val play = LocalPlay.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val firstResult = remember { FocusRequester() }
    var jumpToResults by remember { mutableStateOf(false) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val newFirst = remember { FocusRequester() }      // the first result a "Show more" press added
    val moreBtn = remember { FocusRequester() }
    var focusFrom by remember { mutableStateOf<Int?>(null) }
    // after "Show more": once the new results are in, scroll to and focus the first new one (or back on the button if nothing came)
    LaunchedEffect(state.results?.size, state.loading) {
        val from = focusFrom ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        // focusFrom stays set until the request is done: it is what attaches newFirst to that tile
        if ((state.results?.size ?: 0) > from) { runCatching { gridState.animateScrollToItem(from) }; delay(150); runCatching { newFirst.requestFocus() } }
        else runCatching { moreBtn.requestFocus() }
        focusFrom = null
    }

    fun submit() {
        val text = state.query.trim()
        val id = VIDEO_LINK.find(text)?.groupValues?.get(1) ?: text.takeIf { Regex("[\\w-]{11}").matches(it) }
        if (id != null) play(listOf(Video(id, id)), 0) else { state.run(app, scope); jumpToResults = true }
    }
    // after a search, put the focus on the first result so OK plays it
    LaunchedEffect(state.results, jumpToResults) {
        if (jumpToResults && !state.results.isNullOrEmpty()) {
            jumpToResults = false
            keyboard?.hide()
            runCatching { firstResult.requestFocus() }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 44.dp, end = 44.dp, top = 28.dp)) {
        val results = state.results
        // Nothing searched yet: Right from the rail lands on the first chip (no keyboard popping up); Up reaches the box.
        val chipsShown = results == null && !state.loading && state.error == null
        OutlinedTextField(
            state.query, { state.query = it },
            // Right from the rail lands on the first result when there are some (the field would pop the keyboard up)
            modifier = Modifier.fillMaxWidth().let { if (results.isNullOrEmpty() && !chipsShown) it.focusRequester(entry) else it }
                .focusProperties { down = if (!results.isNullOrEmpty()) firstResult else FocusRequester.Default },
            placeholder = { Text("Search YouTube, or paste a link", fontSize = 20.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            textStyle = TextStyle(fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
            shape = RoundedCornerShape(32.dp),
        )
        when {
            state.loading && results == null -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Search failed", style = MaterialTheme.typography.headlineSmall)
                Text(state.error ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ state.run(app, scope) }) { Text("Try again") }
            }
            results == null -> Column(Modifier.fillMaxWidth().padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Typing with a remote is slow: recent searches and common topics are one press away.
                fun pick(text: String) { state.query = text; state.run(app, scope); jumpToResults = true }
                val recents = app.store.recentSearches
                if (recents.isNotEmpty()) {
                    Text("Recent searches", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        recents.forEachIndexed { i, r -> FocusChip(selected = false, onClick = { pick(r) }, modifier = if (i == 0) Modifier.focusRequester(entry) else Modifier, label = { Text(r, fontSize = 17.sp) }) }
                        FocusChip(selected = false, onClick = { app.store.clearRecentSearches() }, label = { Text("Clear", fontSize = 17.sp) })
                    }
                }
                Text("Try", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Trending now", "Music", "Live now", "Gaming", "News", "Movies", "Cooking", "Science", "Sports", "Comedy", "Learning").forEachIndexed { i, t ->
                        FocusChip(selected = false, onClick = { pick(t) }, modifier = if (i == 0 && recents.isEmpty()) Modifier.focusRequester(entry) else Modifier, label = { Text(t, fontSize = 17.sp) })
                    }
                }
                Text(
                    "Or type with the on-screen keyboard (hold the voice button to speak). Press Menu on a video to save it to a playlist.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp),
                )
            }
            results.isEmpty() -> Text("No results. Try different words.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 36.dp))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(240.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 18.dp, bottom = 40.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(results, key = { i, v -> "$i-${v.id}" }) { i, v ->
                    TvCard(
                        v, progressOf(app, v), onClick = { play(listOf(v), 0) }, onMenu = { onSave(v) }, width = null,
                        modifier = (if (i == 0) Modifier.focusRequester(firstResult).focusRequester(entry) else Modifier).let { if (i == focusFrom) it.focusRequester(newFirst) else it },
                    )
                }
                if (state.canLoadMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(12.dp), Alignment.Center) {
                        // the button stays while loading (so the remote keeps its place) and just says so
                        DialogButton(if (state.loading) "Loading more…" else "Show more results", Modifier.width(280.dp).focusRequester(moreBtn)) {
                            if (!state.loading) { focusFrom = results.size; state.run(app, scope, more = true) }
                        }
                    }
                }
            }
        }
    }
}
