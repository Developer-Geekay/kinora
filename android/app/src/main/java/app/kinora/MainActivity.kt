package app.kinora

import app.kinora.ui.LocalOpenSearch
import android.Manifest
import android.os.Build
import android.os.Bundle
import kotlinx.coroutines.launch
import android.provider.MediaStore
import android.content.Intent
import android.app.SearchManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import app.kinora.ui.IconButton
import app.kinora.ui.PullRefresh
import app.kinora.ui.ShortsFeed
import app.kinora.ui.ShortsScreen
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import app.kinora.ui.ShortsState
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi
import app.kinora.data.Video
import app.kinora.player.Playback
import app.kinora.ui.HomeScreen
import app.kinora.ui.FilterBar
import app.kinora.ui.HistoryScreen
import app.kinora.ui.PlaylistsScreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.LocalContentColor
import app.kinora.ui.LocalPlay
import app.kinora.ui.LocalPlayback
import app.kinora.ui.MiniPlayer
import app.kinora.ui.NetworkScreen
import app.kinora.ui.NetworkState
import app.kinora.ui.PlayerScreen
import app.kinora.ui.SaveToPlaylistDialog
import app.kinora.ui.SearchScreen
import app.kinora.ui.SearchState
import app.kinora.ui.SettingsScreen
import app.kinora.ui.SetupScreen
import app.kinora.ui.TubeTheme
import app.kinora.ui.tv.TvRoot

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var playback: Playback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = tubeApp
        playback = Playback(app).also { it.connect(this) }
        handleVoiceSearch(intent)
        setContent {
            val tv = app.useTv
            TubeTheme(if (tv) "dark" else app.store.theme, if (tv) false else app.store.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalPlayback provides playback) { Root() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceSearch(intent)
    }

    /** "Play <query>": search, then play the first real result (live streams aren't supported). */
    private fun handleVoiceSearch(intent: Intent?) {
        if (intent?.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
        if (query.isEmpty()) return
        val app = tubeApp
        app.scope.launch {
            val first = runCatching { app.api.search(query, 10) }.getOrNull()?.firstOrNull { !it.live } ?: return@launch
            playback.playOne(first)
        }
    }

    /**
     * On Fire TV (Android 7) the Back key reaches the activity but its up-event never turns into onBackPressed(), so the
     * remote's Back did nothing. On a TV, run the back handlers directly, on the key's release.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK && tubeApp.useTv) {
            if (event.action == android.view.KeyEvent.ACTION_UP && !event.isCanceled) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (tubeApp.useTv) playback.restore()
    }

    override fun onStop() {
        // TV: leaving the app (Home, another app, the screensaver) stops playback but remembers it. Phone: it keeps playing.
        if (tubeApp.useTv && !isChangingConfigurations) playback.suspendForExit()
        super.onStop()
    }

    override fun onDestroy() {
        playback.release()
        super.onDestroy()
    }
}

@Composable
@UnstableApi
private fun Root() {
    val app = androidx.compose.ui.platform.LocalContext.current.tubeApp
    val pb = LocalPlayback.current
    var configured by remember { mutableStateOf(app.store.configured) }

    // Android 13+ needs permission to show the playback notification (lock-screen controls).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }

    if (!configured) { SetupScreen { configured = true; pb.reload(true) }; return }
    if (app.useTv) { TvRoot(onChangeServer = { configured = false }); return }

    var tab by rememberSaveable { mutableStateOf(0) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    // what is typed in the top bar of the screens that filter their own list
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var playlistQuery by rememberSaveable { mutableStateOf("") }
    var confirmClearHistory by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<Video?>(null) }
    val search = remember { SearchState() }
    val net = remember { NetworkState() }
    val shorts = remember { ShortsState() }
    var shortsAt by rememberSaveable { mutableStateOf<Int?>(null) } // index of the clip the Shorts feed opened on
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val uiScope = androidx.compose.runtime.rememberCoroutineScope()

    val play: (List<Video>, Int) -> Unit = { videos, i -> pb.playQueue(videos, i); playerOpen = true }
    BackHandler(shortsAt != null && !playerOpen) { shortsAt = null }
    BackHandler(drawer.isOpen) { uiScope.launch { drawer.close() } }
    BackHandler(playerOpen) { playerOpen = false }
    BackHandler(!playerOpen && tab == 6 && openPlaylist != null) { openPlaylist = null }
    BackHandler(!playerOpen && tab != 0 && !(tab == 6 && openPlaylist != null)) { tab = 0 }
    BackHandler(!playerOpen && tab == 3 && net.canGoUp) { net.up() } // after the one above, so it wins: up a folder before leaving the tab
    // and these win over both: Back first empties a filter
    BackHandler(!playerOpen && tab == 2 && historyQuery.isNotEmpty()) { historyQuery = "" }
    BackHandler(!playerOpen && tab == 6 && openPlaylist == null && playlistQuery.isNotEmpty()) { playlistQuery = "" }
    BackHandler(!playerOpen && tab == 3 && net.query.isNotEmpty()) { net.query = "" }
    // a filter is forgotten when you leave its screen
    LaunchedEffect(tab) { if (tab != 2) historyQuery = ""; if (tab != 6) playlistQuery = ""; if (tab != 3) net.query = "" }

    val openSearch: (String) -> Unit = { q -> playerOpen = false; tab = 1; search.query = q; search.run(app, app.scope) }
    CompositionLocalProvider(LocalPlay provides play, LocalOpenSearch provides openSearch) {
        Box(Modifier.fillMaxSize()) {
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = drawer.isOpen, // opened from "More"; the edge swipe would fight the carousels
                drawerContent = {
                    ModalDrawerSheet {
                        Row(Modifier.padding(24.dp, 28.dp, 16.dp, 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(40.dp))
                            Text("Kinora", Modifier.padding(start = 14.dp), style = MaterialTheme.typography.titleLarge)
                        }
                        listOf(Triple(0, Icons.Filled.Home, "Home"), Triple(5, Icons.Filled.SlowMotionVideo, "Shorts"), Triple(3, Icons.Filled.Dns, "Network"),
                            Triple(2, Icons.Filled.History, "History"), Triple(6, Icons.Filled.VideoLibrary, "Playlists"), Triple(4, Icons.Filled.Settings, "Settings")).forEach { (id, icon, label) ->
                            NavigationDrawerItem(
                                label = { Text(label) }, icon = { Icon(icon, null) }, selected = tab == id,
                                onClick = { tab = id; openPlaylist = null; uiScope.launch { drawer.close() } },
                                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                            )
                        }
                    }
                },
            ) {
            Scaffold(
                topBar = {
                    // The top bar is a search box: on History, Playlists and inside a share it filters what is on the screen;
                    // elsewhere tapping it opens the YouTube search. (The menu is under "More".)
                    when {
                        tab == 1 -> Unit // the search screen has its own box
                        tab == 2 -> FilterBar(historyQuery, { historyQuery = it }, "Search watch history") {
                            IconButton({ confirmClearHistory = true }, enabled = app.store.history.isNotEmpty()) { Icon(Icons.Filled.Delete, "Clear history") }
                        }
                        tab == 6 && openPlaylist == null -> FilterBar(playlistQuery, { playlistQuery = it }, "Search playlists")
                        tab == 3 && net.server != null -> FilterBar(net.query, { net.query = it }, "Search this folder") {
                            IconButton({ net.options = true }) {
                                Icon(Icons.Filled.Tune, "Filter, sort and view", tint = if (net.type != "all") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                            }
                        }
                        else -> Row(
                            Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(12.dp, 8.dp, 8.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(24.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { tab = 1 }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Search YouTube", Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (tab == 0) IconButton({ app.feed.refresh(force = true) }, enabled = !app.feed.loading) { Icon(Icons.Filled.Refresh, "Refresh suggestions") }
                        }
                    }
                },
                bottomBar = {
                    Column {
                        if (pb.hasMedia) MiniPlayer(onOpen = { playerOpen = true })
                        NavigationBar {
                            listOf(Triple(0, Icons.Filled.Home, "Home"), Triple(5, Icons.Filled.SlowMotionVideo, "Shorts"), Triple(3, Icons.Filled.Dns, "Network"))
                                .forEach { (id, icon, label) ->
                                    NavigationBarItem(selected = tab == id, onClick = { tab = id; openPlaylist = null }, icon = { Icon(icon, label) }, label = { Text(label) })
                                }
                            // the side menu has every destination, including History, Playlists and Settings
                            NavigationBarItem(
                                selected = tab == 2 || tab == 4 || tab == 6, onClick = { uiScope.launch { drawer.open() } },
                                icon = { Icon(Icons.Filled.MoreHoriz, "More") },
                                label = { Text("More") },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when (tab) {
                        0 -> PullRefresh(app.feed.loading, { app.feed.refresh(force = true) }) { HomeScreen(onSave = { saving = it }, openPlaylist = { openPlaylist = it; tab = 6 }) }
                        1 -> SearchScreen(search, onSave = { saving = it })
                        2 -> HistoryScreen(historyQuery, confirmClearHistory, onClearDone = { confirmClearHistory = false }, onSave = { saving = it })
                        6 -> PlaylistsScreen(playlistQuery, openPlaylist, { openPlaylist = it }, onSave = { saving = it })
                        3 -> NetworkScreen(net)
                        5 -> PullRefresh(shorts.loading, { shorts.load(app, app.scope, force = true) }) { ShortsScreen(shorts, onOpen = { shortsAt = it }) }
                        else -> SettingsScreen(onChangeServer = { configured = false })
                    }
                }
            }
            }

            AnimatedVisibility(shortsAt != null && !playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                shortsAt?.let { at -> Surface(Modifier.fillMaxSize()) { ShortsFeed(shorts.items, at, onClose = { shortsAt = null }, onSave = { saving = it }) } }
            }
            AnimatedVisibility(playerOpen, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Surface(Modifier.fillMaxSize()) { PlayerScreen(onClose = { playerOpen = false }, onSave = { saving = it }) }
            }
            saving?.let { SaveToPlaylistDialog(it) { saving = null } }
        }
    }
}
