package app.kinora.ui

import app.kinora.data.SMB_PREFIX
import app.kinora.data.VideoInfo
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
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
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import app.kinora.data.Video
import app.kinora.data.formatTime
import app.kinora.data.qualityLabel
import app.kinora.tubeApp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
                modifier = Modifier.fillMaxWidth(), color = PlayerRed, trackColor = Color.Transparent,
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

    // controls fade away while playing (but not while a finger is on the seek bar)
    var scrub by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(controls, pb.playing, scrub != null) { if (controls && pb.playing && scrub == null) { delay(3500); controls = false } }

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
            VideoArea(audio, controls, { controls = !controls }, onClose, { options = true }, scrub, { scrub = it }, landscape = true, onToggleOrientation = {
                fullscreen = false
                if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }, modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                VideoArea(audio, controls, { controls = !controls }, onClose, { options = true }, scrub, { scrub = it }, landscape = false, onToggleOrientation = {
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

    if (options) SettingsSheet(onDismiss = { options = false })
}

@Composable
@UnstableApi
private fun VideoArea(
    audio: Boolean,
    controls: Boolean,
    toggleControls: () -> Unit,
    onClose: () -> Unit,
    openOptions: () -> Unit,
    scrub: Long?,
    onScrub: (Long?) -> Unit,
    landscape: Boolean,
    onToggleOrientation: () -> Unit,
    modifier: Modifier,
) {
    val pb = LocalPlayback.current
    val v = pb.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var fast by remember { mutableStateOf(false) } // holding a finger on the picture = 2x speed, like YouTube
    // Touch gestures on the picture: tap = show/hide the controls; double-tap the left third = back 10 s, the right third =
    // forward 10 s (double-tap again to keep going: 20, 30 ...), the middle = play/pause.
    val skip = rememberSkipState()
    val flash = remember { FlashState() }
    var areaWidth by remember { mutableStateOf(1) }
    fun toggle() { flash.show(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow); pb.toggle() }
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
                        if (side == 0) toggle()
                        else if (!pb.live && pb.durationMs > 0) {
                            pb.seek((pb.positionMs + side * 10_000L).coerceIn(0L, (pb.durationMs - 1000).coerceAtLeast(0)))
                            skip.add(side * 10)
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

        SkipFeedback(skip)
        PlayerFlash(flash)
        if (fast) Text("2×  ▶▶", color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).clip(RoundedCornerShape(20.dp)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp))
        val loading = (pb.starting || pb.buffering) && pb.error == null
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White, strokeWidth = 3.dp)

        val shown = scrub ?: pb.positionMs
        val length = if (pb.live) 1L else pb.durationMs
        // controls hidden, upright: only a thin line along the bottom of the picture
        if (!controls && !landscape && !pb.live) SeekTrack(pb.positionMs, pb.bufferedMs, length, thickness = 2.dp, knob = 0.dp, modifier = Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Minimize", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    // upright, the title is under the picture already
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        if (landscape) {
                            Text(v?.title.orEmpty(), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(v?.channel.orEmpty(), color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(
                        { if (pb.subtitlesOn) pb.selectSubtitles(null) else pb.selectSubtitles(pb.textTracks.firstOrNull { it.supported }) },
                        enabled = pb.textTracks.isNotEmpty(),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.ClosedCaption, if (pb.subtitlesOn) "Captions on" else "Captions off", tint = if (pb.textTracks.isEmpty()) Color(0x66FFFFFF) else Color.White)
                            if (pb.subtitlesOn) Box(Modifier.align(Alignment.BottomCenter).size(width = 18.dp, height = 3.dp).clip(CircleShape).background(PlayerRed))
                        }
                    }
                    IconButton(openOptions) { Icon(Icons.Filled.Settings, "Settings", tint = Color.White) }
                }
                if (pb.error == null) Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    CenterButton(Icons.Filled.SkipPrevious, "Previous", 48.dp, onClick = pb::previous)
                    // the spinner takes the middle button's place while loading
                    if (loading) Box(Modifier.size(64.dp)) else CenterButton(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pb.playing) "Pause" else "Play", 64.dp, onClick = { toggle() })
                    CenterButton(Icons.Filled.SkipNext, "Next", 48.dp, enabled = pb.index < pb.queue.size - 1, onClick = pb::next)
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (landscape) 24.dp else 0.dp).padding(bottom = if (landscape) 12.dp else 0.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pb.live) {
                            Box(Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(PlayerRed))
                            Text("LIVE", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        } else Text("${formatTime(shown / 1000)} / ${formatTime(pb.durationMs / 1000)}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.weight(1f))
                        IconButton(onToggleOrientation) { Icon(if (landscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, if (landscape) "Exit full screen" else "Full screen", tint = Color.White) }
                    }
                    SeekBar(shown, length, scrubbing = scrub != null, onScrub = onScrub)
                }
            }
        }

        pb.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(16.dp).background(Color(0xE6000000), RoundedCornerShape(16.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center)
                Button(pb::retry, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text("Try again") }
            }
        }
    }
}

/** The round buttons in the middle of the picture. */
@Composable
private fun CenterButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(size).background(Color(0x73000000), CircleShape), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White, disabledContentColor = Color(0x66FFFFFF))) {
        Icon(icon, label, Modifier.size(size * 0.6f))
    }
}

/** The red progress bar. Tap or drag anywhere on it; the video seeks when the finger lifts. */
@Composable
@UnstableApi
private fun SeekBar(shownMs: Long, lengthMs: Long, scrubbing: Boolean, onScrub: (Long?) -> Unit) {
    val pb = LocalPlayback.current
    val report by rememberUpdatedState(onScrub)
    Box(
        Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (pb.live || pb.durationMs <= 0) return@awaitEachGesture
                fun at(x: Float) = ((x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f) * pb.durationMs).toLong()
                down.consume()
                var target = at(down.position.x)
                report(target)
                var released = false
                try {
                    while (!released) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        target = at(change.position.x)
                        released = !change.pressed
                        change.consume()
                        if (!released) report(target)
                    }
                    if (released) pb.seek(target)
                } finally {
                    report(null)
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        SeekTrack(if (pb.live) 1L else shownMs, if (pb.live) 1L else pb.bufferedMs, lengthMs, thickness = if (scrubbing) 6.dp else 3.dp, knob = if (pb.live) 0.dp else if (scrubbing) 18.dp else 12.dp)
    }
}

/** The settings menu (the same list as on the TV and the web), as a sheet from the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@UnstableApi
private fun SettingsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PlayerPanel, contentColor = Color.White) {
        PlayerSettingsList(playerSettings(), Modifier.navigationBarsPadding().padding(bottom = 12.dp))
    }
}
