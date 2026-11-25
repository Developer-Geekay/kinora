package app.localtube.ui

import app.localtube.data.SMB_PREFIX
import app.localtube.data.VideoInfo
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.localtube.data.Video
import app.localtube.data.formatTime
import app.localtube.data.qualityLabel
import app.localtube.tubeApp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Compact "now playing" bar shown above the bottom navigation while something is loaded. */
@Composable
@UnstableApi
fun MiniPlayer(onOpen: () -> Unit) {
    val pb = LocalPlayback.current
    val v = pb.current ?: return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().focusRing().clickable(onClick = onOpen)
        .pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = { if (total < -40f) onOpen() },
                onDragCancel = { total = 0f },
            ) { _, dy -> total += dy }
        }) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(if (pb.error != null) "Couldn't play" else v.channel, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pb.starting || pb.buffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else IconButton(pb::toggle) { Icon(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pb.playing) "Pause" else "Play") }
                IconButton(pb::next, enabled = pb.index < pb.queue.size - 1) { Icon(Icons.Filled.SkipNext, "Next") }
                IconButton(pb::stop) { Icon(Icons.Filled.Close, "Stop") }
            }
            LinearProgressIndicator(
                progress = { if (pb.durationMs > 0) (pb.positionMs.toFloat() / pb.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(), trackColor = Color.Transparent,
            )
        }
    }
}

/** The full player: video (or cover art in audio mode), controls, options, and the queue. */
@OptIn(ExperimentalMaterial3Api::class)
/** Keeps the screen from turning off on its own while [on] is true (a video is playing). */
@Composable
fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) { view.keepScreenOn = on; onDispose { view.keepScreenOn = false } }
}

@Composable
@UnstableApi
fun PlayerScreen(onClose: () -> Unit, onSave: (Video) -> Unit) {
    val pb = LocalPlayback.current
    val app = LocalContext.current.tubeApp
    val activity = LocalContext.current as Activity
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val audio = app.stream == "audio"
    KeepScreenOn(!audio && (pb.playing || pb.buffering || pb.starting))
    var controls by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    // Full screen is the phone turned sideways, or (for a tall video such as a Short) full screen while staying upright:
    // only a wide video is worth turning the phone for.
    var fullscreen by remember { mutableStateOf(false) }
    val isFull = landscape || fullscreen
    androidx.activity.compose.BackHandler(fullscreen && !landscape) { fullscreen = false }

    // Full screen: hide the system bars. Restore everything when this screen closes.
    DisposableEffect(isFull) {
        val ctl = WindowCompat.getInsetsController(activity.window, view)
        if (isFull) {
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }
    DisposableEffect(Unit) { onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }

    // controls fade away while playing
    LaunchedEffect(controls, pb.playing) { if (controls && pb.playing) { delay(3500); controls = false } }

    val openRelated = LocalPlay.current
    val v = pb.current
    // channel, tags and description (YouTube videos; files on a share have none)
    val info by androidx.compose.runtime.produceState<VideoInfo?>(null, v?.id) {
        value = null
        val id = v?.id
        if (id != null && !id.startsWith(SMB_PREFIX)) value = runCatching { app.api.info(id, app.stream, app.quality) }.getOrNull()
    }

    // Remote control (TV, or a keyboard): with the controls hidden, Left/Right seek 10 s, OK plays/pauses, Up/Down bring the
    // controls back. With them showing, the D-pad moves focus between the buttons as usual.
    val remoteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { remoteFocus.requestFocus() } }
    val remote = Modifier
        .focusRequester(remoteFocus)
        .focusable()
        .onPreviewKeyEvent { e ->
            if (controls || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { pb.seek((pb.positionMs - 10_000).coerceAtLeast(0)); controls = true; true }
                Key.DirectionRight -> { pb.seek((pb.positionMs + 10_000).coerceAtMost((pb.durationMs - 1000).coerceAtLeast(0))); controls = true; true }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { pb.toggle(); controls = true; true }
                Key.DirectionUp, Key.DirectionDown -> { controls = true; true }
                else -> false
            }
        }

    Surface(Modifier.fillMaxSize().then(remote), color = MaterialTheme.colorScheme.surface) {
        if (isFull) {
            VideoArea(audio, controls, { controls = !controls }, onClose, { options = true }, landscape = true, onToggleOrientation = {
                fullscreen = false
                if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }, modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                VideoArea(audio, controls, { controls = !controls }, onClose, { options = true }, landscape = false, onToggleOrientation = {
                    if (pb.videoPortrait) fullscreen = true else activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))

                LazyColumn(Modifier.weight(1f).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        if (v != null) Column(Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(v.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(v.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(
                                    onClick = { options = true },
                                    label = { Text(if (audio) "Audio only" else "Video · ${qualityLabel(app.quality)}" + if (app.hasOverrides) " •" else "") },
                                    leadingIcon = { Icon(if (audio) Icons.Filled.Headphones else Icons.Filled.Videocam, null) },
                                )
                                FilledTonalButton({ onSave(v) }) { Icon(Icons.Filled.PlaylistAdd, null); Text("  Save") }
                            }
                            info?.let { VideoDetails(it, v.channel, modifier = Modifier.padding(top = 8.dp)) }
                        }
                    }
                    if (pb.queue.size > 1) {
                        item {
                            Text("Up next · ${pb.index + 1} / ${pb.queue.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
                        }
                        itemsIndexed(pb.queue, key = { i, q -> "$i-${q.id}" }) { i, q ->
                            val actions = buildList {
                                if (i > 0) add(MenuAction("Move up", Icons.Filled.ArrowUpward) { pb.move(i, i - 1) })
                                if (i < pb.queue.size - 1) add(MenuAction("Move down", Icons.Filled.ArrowDownward) { pb.move(i, i + 1) })
                                add(MenuAction("Save to playlist…", Icons.Filled.PlaylistAdd) { onSave(q) })
                                add(MenuAction("Remove from queue", Icons.Filled.Delete, danger = true) { pb.removeAt(i) })
                            }
                            VideoRow(q, onClick = { pb.skipTo(i) }, actions = actions, highlighted = i == pb.index)
                        }
                    }
                    // what YouTube suggests next, like the list under a video on youtube.com
                    val related = info?.related.orEmpty()
                    if (related.isNotEmpty()) {
                        item { Text("Related videos", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)) }
                        itemsIndexed(related, key = { i, r -> "rel-$i-${r.id}" }) { i, r ->
                            VideoCard(r, onClick = { openRelated(related, i) }, actions = videoActions(r, onSave), progress = progressOf(app, r))
                        }
                    }
                }
            }
        }
    }

    if (options) OptionsSheet(onDismiss = { options = false })
}

@Composable
@UnstableApi
private fun VideoArea(
    audio: Boolean,
    controls: Boolean,
    toggleControls: () -> Unit,
    onClose: () -> Unit,
    openOptions: () -> Unit,
    landscape: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier,
) {
    val pb = LocalPlayback.current
    val v = pb.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fast by remember { mutableStateOf(false) } // holding a finger on the picture = 2x speed, like YouTube
    // Touch gestures on the picture: tap = show/hide the controls; double-tap the left third = back 10 s, the right third =
    // forward 10 s (tap again to keep going: +20, +30 ...), the middle = play/pause.
    var hint by remember { mutableStateOf<Pair<Int, Int>?>(null) } // (side: -1 back, 0 pause, 1 forward, seconds so far)
    var areaWidth by remember { mutableStateOf(1) }
    LaunchedEffect(hint) { if (hint != null) { delay(700); hint = null } }
    Box(
        modifier
            .onSizeChanged { areaWidth = it.width.coerceAtLeast(1) }
            // swipe down: leave full screen, or shrink to the mini player; swipe up (portrait): full screen
            .pointerInput(landscape) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > 60f) { if (landscape) onToggleOrientation() else onClose() }
                        else if (total < -60f && !landscape) onToggleOrientation()
                    },
                    onDragCancel = { total = 0f },
                ) { _, dy -> total += dy }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val hold = scope.launch {
                            delay(450)
                            if (pb.playing) { fast = true; pb.changeSpeed(2f) }
                        }
                        val normal = pb.speed
                        tryAwaitRelease()
                        hold.cancel()
                        if (fast) { fast = false; pb.changeSpeed(normal) }
                    },
                    onTap = { toggleControls() },
                    onDoubleTap = { p ->
                        val side = when { p.x < areaWidth / 3f -> -1; p.x > areaWidth * 2f / 3f -> 1; else -> 0 }
                        if (side == 0) { pb.toggle(); hint = 0 to 0 }
                        else {
                            val same = hint?.first == side
                            val total = if (same) hint!!.second + 10 else 10
                            val max = (pb.durationMs - 1000).coerceAtLeast(0)
                            pb.seek((pb.positionMs + side * 10_000L).coerceIn(0L, max))
                            hint = side to total
                        }
                    },
                )
            },
    ) {
        if (audio) {
            if (v != null) {
                AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().background(Color.Black), alpha = 0.35f)
                AsyncImage(v.thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.Center).fillMaxWidth(if (landscape) 0.4f else 0.6f).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)))
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { it.player = pb.player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        hint?.let { (side, secs) ->
            val label = when (side) { -1 -> "− $secs s"; 1 -> "+ $secs s"; else -> if (pb.playing) "Playing" else "Paused" }
            val at = when (side) { -1 -> Alignment.CenterStart; 1 -> Alignment.CenterEnd; else -> Alignment.Center }
            Text(label, color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(at).padding(horizontal = 36.dp).clip(RoundedCornerShape(24.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 10.dp))
        }
        if (fast) Text("2×  ▶▶", color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp))
        if (pb.starting || pb.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = MaterialTheme.colorScheme.primary)
        pb.error?.let { msg ->
            Column(Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center)
                TextButton(pb::retry) { Text("Try again") }
            }
        }

        AnimatedVisibility(controls, Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent, Color(0xCC000000))))) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Minimize", tint = Color.White) }
                    Text(v?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(openOptions) { Icon(Icons.Filled.Tune, "Playback options", tint = Color.White) }
                }
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(pb::previous, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
                    IconButton(pb::toggle, Modifier.size(64.dp).background(Color(0x66000000), CircleShape), colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                        Icon(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pb.playing) "Pause" else "Play", Modifier.size(40.dp))
                    }
                    IconButton(pb::next, enabled = pb.index < pb.queue.size - 1, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color(0x66FFFFFF))) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp)) {
                    SeekBar()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pb.live) Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFD32F2F)).padding(horizontal = 6.dp, vertical = 1.dp))
                        else Text("${formatTime(pb.positionMs / 1000)} / ${formatTime(pb.durationMs / 1000)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.weight(1f))
                        IconButton(onToggleOrientation) { Icon(if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (landscape) "Exit full screen" else "Full screen", tint = Color.White) }
                    }
                }
            }
        }
    }
}

@Composable
@UnstableApi
private fun SeekBar() {
    val pb = LocalPlayback.current
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val max = pb.durationMs.coerceAtLeast(1).toFloat()
    Slider(
        value = if (dragging) drag else pb.positionMs.toFloat().coerceIn(0f, max),
        onValueChange = { dragging = true; drag = it },
        onValueChangeFinished = { pb.seek(drag.toLong()); dragging = false },
        valueRange = 0f..max,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Player-level overrides: stream type, quality, speed, autoplay for what's playing now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@UnstableApi
private fun OptionsSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val pb = LocalPlayback.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playback options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton({ app.resetOverrides(); pb.reload(true) }, enabled = app.hasOverrides) { Text("Reset") }
            }
            OptionRow("Stream", note(app.ovStream != null, if (app.store.stream == "audio") "Audio only" else "Video")) {
                Chips(listOf("video" to "Video", "audio" to "Audio only"), app.stream) {
                    app.ovStream = if (it == app.store.stream) null else it; pb.reload(true)
                }
            }
            OptionRow("Quality", note(app.ovQuality != null, qualityLabel(app.store.quality))) {
                Chips(listOf(360, 480, 720, 1080, 1440, 2160).map { it.toString() to qualityLabel(it) }, app.quality.toString(), enabled = app.stream == "video") {
                    app.ovQuality = if (it.toInt() == app.store.quality) null else it.toInt(); pb.reload(true)
                }
            }
            OptionRow("Speed", null) { Chips(SPEEDS.map { it.toString() to "${it}×" }, pb.speed.toString()) { pb.changeSpeed(it.toFloat()) } }
            // what is inside the file (files on a network share, and YouTube videos with several languages)
            if (pb.audioTracks.size > 1) OptionRow("Audio", "${pb.audioTracks.size} tracks") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pb.audioTracks.forEach { t -> FilterChip(selected = t.selected, onClick = { pb.selectAudio(t) }, enabled = t.supported, label = { Text(t.label + if (t.supported) "" else " · not supported") }) }
                }
            }
            if (pb.textTracks.isNotEmpty()) OptionRow("Subtitles", "${pb.textTracks.size} found") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !pb.subtitlesOn, onClick = { pb.selectSubtitles(null) }, label = { Text("Off") })
                    pb.textTracks.forEach { t -> FilterChip(selected = t.selected, onClick = { pb.selectSubtitles(t) }, enabled = t.supported, label = { Text(t.label + if (t.supported) "" else " · not supported") }) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay next", style = MaterialTheme.typography.bodyLarge)
                    val n = note(app.ovAutoplay != null, if (app.store.autoplay) "On" else "Off")
                    if (n != null) Text(n, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Switch(app.autoplay, { app.ovAutoplay = if (it == app.store.autoplay) null else it; pb.reload(false) })
            }
        }
    }
}

private fun note(overridden: Boolean, default: String) = if (overridden) "Overridden here · default is $default" else null

@Composable
private fun OptionRow(title: String, note: String?, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun Chips(options: List<Pair<String, String>>, selected: String, enabled: Boolean = true, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, text) -> FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(text) }, enabled = enabled) }
    }
}
