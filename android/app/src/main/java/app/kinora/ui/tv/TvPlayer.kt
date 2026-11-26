package app.kinora.ui.tv

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.focus.focusProperties
import app.kinora.ui.progressOf
import app.kinora.ui.LocalPlay
import app.kinora.data.SMB_PREFIX
import app.kinora.data.VideoInfo
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.produceState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.kinora.data.Video
import app.kinora.data.formatTime
import app.kinora.data.qualityLabel
import app.kinora.tubeApp
import app.kinora.ui.LocalPlayback
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/*
 * The TV player. Everything works from the remote:
 *   Left / Right        seek 10 s (hold to go faster); works while the controls are hidden, and on the seek bar
 *   OK                  play / pause (when the controls are hidden or the seek bar is focused)
 *   Up / Down           show the controls; Down from the seek bar reaches the buttons
 *   Play/Pause, FF, RW  the remote's media keys; Next / Previous too
 *   Back                hide the controls first, then leave the player
 * Controls hide by themselves a few seconds after the last key while playing, and stay up when paused.
 */

private const val STEP_MS = 10_000L
private val OPTION_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
@UnstableApi
fun TvPlayerScreen(onClose: () -> Unit, onSave: (Video) -> Unit) {
    val pb = LocalPlayback.current
    val app = LocalContext.current.tubeApp
    val view = LocalView.current
    val audio = app.stream == "audio"
    val video = pb.current

    var controls by remember { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }       // bumped on every key: restarts the auto-hide timer
    var scrub by remember { mutableStateOf<Long?>(null) }   // where the user is scrubbing to, until it is applied
    var seekFocused by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) } // name of the focused control, shown above the buttons
    var related by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var buttonsFocused by remember { mutableStateOf(false) }
    val play = LocalPlay.current
    val info by produceState<VideoInfo?>(null, video?.id) {
        value = null
        val id = video?.id
        if (id != null && !id.startsWith(SMB_PREFIX)) value = runCatching { app.api.info(id, app.stream, app.quality) }.getOrNull()
    }
    val relatedList = info?.related.orEmpty()
    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val relatedBtn = remember { FocusRequester() }
    var returnToRelated by remember { mutableStateOf(false) }
    val errorFocus = remember { FocusRequester() }

    // the TV must not go to sleep while a video plays
    DisposableEffect(pb.playing) { view.keepScreenOn = pb.playing; onDispose { view.keepScreenOn = false } }

    val pinned = audio || !pb.playing || pb.error != null || options || related || details // controls stay up
    val visible = controls || pinned
    LaunchedEffect(controls, keyTick, pinned) { if (controls && !pinned) { delay(4500); controls = false } }

    // keep the focus somewhere that makes sense, so the remote always does something
    LaunchedEffect(visible, options, related, pb.error) {
        when {
            options -> Unit
            related -> returnToRelated = true
            pb.error != null -> runCatching { errorFocus.requestFocus() }
            visible -> {
                val target = if (returnToRelated) relatedBtn else seekFocus
                if (returnToRelated) delay(150) // the strip that just closed is still on screen for a moment and would hold the focus
                returnToRelated = false
                runCatching { target.requestFocus() }.onFailure { runCatching { seekFocus.requestFocus() } }
            }
            else -> runCatching { rootFocus.requestFocus() }
        }
    }

    // apply a scrub a moment after the last press, so holding the key doesn't restart the video stream each time
    LaunchedEffect(scrub) {
        val target = scrub ?: return@LaunchedEffect
        delay(650)
        pb.seek(target)
        scrub = null
    }

    fun scrubBy(delta: Long) {
        val max = (pb.durationMs - 1000).coerceAtLeast(0)
        scrub = ((scrub ?: pb.positionMs) + delta).coerceIn(0L, max)
        controls = true
    }

    BackHandler(true) {
        when {
            details -> details = false
            related -> related = false
            options -> options = false
            controls && pb.playing && !audio -> controls = false
            else -> onClose()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .focusProperties { exit = { FocusRequester.Cancel } } // nothing under the player may take focus
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || options || related || details) return@onPreviewKeyEvent false
                keyTick++
                val step = when {
                    e.nativeKeyEvent.repeatCount > 15 -> 6 * STEP_MS
                    e.nativeKeyEvent.repeatCount > 5 -> 3 * STEP_MS
                    else -> STEP_MS
                }
                when (e.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { pb.toggle(); controls = true; true }
                    Key.MediaFastForward, Key.MediaStepForward -> { scrubBy(3 * STEP_MS); true }
                    Key.MediaRewind, Key.MediaStepBackward -> { scrubBy(-3 * STEP_MS); true }
                    Key.MediaNext -> { pb.next(); true }
                    Key.MediaPrevious -> { pb.previous(); true }
                    Key.DirectionLeft -> if (!visible || seekFocused) { scrubBy(-step); true } else false
                    Key.DirectionRight -> if (!visible || seekFocused) { scrubBy(step); true } else false
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        if (!visible || seekFocused) { pb.toggle(); controls = true; true } else false
                    Key.DirectionDown -> when {
                        !visible -> { controls = true; true }
                        !seekFocused && relatedList.isNotEmpty() -> { related = true; true } // Down from a button (not the seek bar) opens the related strip
                        else -> false
                    }
                    Key.DirectionUp -> if (!visible) { controls = true; true } else false
                    else -> { if (!visible) controls = true; false }
                }
            },
    ) {
        // ----- the picture (or cover art for audio) -----
        if (audio) {
            if (video != null) {
                AsyncImage(video.thumb, null, contentScale = ContentScale.Crop, alpha = 0.28f, modifier = Modifier.fillMaxSize())
                AsyncImage(video.thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.align(Alignment.Center).width(520.dp).clip(RoundedCornerShape(20.dp)))
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

        if ((pb.starting || pb.buffering) && pb.error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(64.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 5.dp)
        }

        // ----- controls (YouTube style: title at the top, a thin red bar, plain white icons) -----
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(170.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color(0xA6000000), 1f to Color(0xF2000000))))

                Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 36.dp, end = 56.dp)) {
                    Text(video?.title.orEmpty(), color = Color.White, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(video?.channel?.takeIf { it.isNotBlank() }, if (audio) "Audio only" else qualityLabel(app.quality)).joinToString("  ·  ")
                    Text(sub, color = Color(0xCCFFFFFF), fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                }

                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 56.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val shown = scrub ?: pb.positionMs
                    Text(hint.orEmpty(), color = Color(0xE6FFFFFF), fontSize = 15.sp, modifier = Modifier.height(20.dp))
                    TvSeekBar(if (pb.live) 1L else shown, if (pb.live) 1L else pb.bufferedMs, if (pb.live) 1L else pb.durationMs, scrubbing = scrub != null,
                        modifier = Modifier.focusRequester(seekFocus), onFocus = { seekFocused = it })
                    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (pb.live) Text("LIVE", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFFF0000)).padding(horizontal = 8.dp, vertical = 1.dp))
                        else Text(formatTime(shown / 1000) + "  /  " + formatTime(pb.durationMs / 1000), color = Color.White, fontSize = 16.sp)
                        Box(Modifier.weight(1f))
                        if (pb.durationMs > 0 && !pb.live) {
                            val leftMs = ((pb.durationMs - shown) / pb.speed.coerceAtLeast(0.25f)).toLong().coerceAtLeast(0)
                            val endsAt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(System.currentTimeMillis() + leftMs))
                            Text("ends $endsAt" + if (pb.speed != 1f) "  ·  ${pb.speed}×" else "", color = Color(0xB3FFFFFF), fontSize = 14.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth().onFocusChanged { buttonsFocused = it.hasFocus }, verticalAlignment = Alignment.CenterVertically) {
                        TvControl(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (pb.playing) "Pause" else "Play", size = 56.dp, onFocus = { hint = it }) { pb.toggle() }
                        TvControl(Icons.Filled.SkipPrevious, "Previous", onFocus = { hint = it }) { pb.previous() }
                        TvControl(Icons.Filled.SkipNext, "Next video", enabled = pb.index < pb.queue.size - 1, onFocus = { hint = it }) { pb.next() }
                        TvControl(Icons.Filled.Replay10, "Back 10 seconds", onFocus = { hint = it }) { scrubBy(-STEP_MS) }
                        TvControl(Icons.Filled.Forward10, "Forward 10 seconds", onFocus = { hint = it }) { scrubBy(STEP_MS) }
                        Box(Modifier.weight(1f))
                        if (pb.textTracks.isNotEmpty()) TvControl(
                            if (pb.subtitlesOn) Icons.Filled.Subtitles else Icons.Filled.SubtitlesOff,
                            if (pb.subtitlesOn) "Subtitles on (press to turn off)" else "Subtitles off (press to turn on)",
                            lit = pb.subtitlesOn, onFocus = { hint = it },
                        ) { if (pb.subtitlesOn) pb.selectSubtitles(null) else pb.selectSubtitles(pb.textTracks.firstOrNull { it.supported }) }
                        if (pb.audioTracks.size > 1) TvControl(Icons.Filled.Audiotrack, "Audio track", onFocus = { hint = it }) { options = true }
                        if (info != null) TvControl(Icons.Filled.Info, "About this video: channel and description", onFocus = { hint = it }) { details = true }
                        if (relatedList.isNotEmpty()) TvControl(Icons.Filled.VideoLibrary, "Related videos (or press Down)", modifier = Modifier.focusRequester(relatedBtn), onFocus = { hint = it }) { related = true }
                        TvControl(Icons.Filled.PlaylistAdd, "Save to playlist", onFocus = { hint = it }) { video?.let(onSave) }
                        TvControl(Icons.Filled.Tune, "Settings: quality, speed, subtitles", onFocus = { hint = it }) { options = true }
                    }
                }
            }
        }

        // ----- something went wrong -----
        pb.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color(0xE6000000)).padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(msg, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(pb::retry, Modifier.focusRequester(errorFocus)) { Text("Try again", fontSize = 18.sp) }
                    OutlinedButton(onClose) { Text("Back", fontSize = 18.sp) }
                }
            }
        }

        if (options) TvOptionsPanel(onDismiss = { options = false })
        if (details) info?.let { TvDetailsPanel(it, video?.channel.orEmpty(), onDismiss = { details = false }) }
        if (related) TvRelatedStrip(relatedList, onPick = { i -> related = false; play(relatedList, i) }, onDismiss = { related = false })
    }
}

// ---------------------------------------------------------------------------------------------------------------------

/** A plain white icon; the focused one gets a white disc with a dark icon, like YouTube on a TV. */
@Composable
private fun TvControl(icon: ImageVector, label: String, size: Dp = 48.dp, filled: Boolean = false, enabled: Boolean = true, lit: Boolean = false, modifier: Modifier = Modifier, onFocus: (String?) -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, label = "ctl")
    Box(
        modifier.size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.35f }
            .onFocusChanged { focused = it.hasFocus; onFocus(if (it.hasFocus) label else null) }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (focused) Color(0xFF0F0F0F) else Color.White, modifier = Modifier.size(size * 0.58f))
        if (lit) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp, height = 3.dp).clip(CircleShape).background(Color(0xFFFF0000)))
    }
}

/** The progress bar: thin and red, with a red dot when the remote is on it. Left/Right scrub (handled by the player). */
@Composable
private fun TvSeekBar(positionMs: Long, bufferedMs: Long, durationMs: Long, scrubbing: Boolean, modifier: Modifier = Modifier, onFocus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val total = durationMs.coerceAtLeast(1).toFloat()
    val pos = (positionMs / total).coerceIn(0f, 1f)
    val buf = (bufferedMs / total).coerceIn(0f, 1f)
    val thick by animateFloatAsState(if (focused || scrubbing) 8f else 4f, label = "seek")
    Box(
        modifier.fillMaxWidth().height(28.dp)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(thick.dp).clip(RoundedCornerShape(50)).background(Color(0x4DFFFFFF))) {
            Box(Modifier.fillMaxWidth(buf).fillMaxHeight().background(Color(0x80FFFFFF)))
            Box(Modifier.fillMaxWidth(pos).fillMaxHeight().background(Color(0xFFFF0000)))
        }
        if (focused || scrubbing) {
            Box(Modifier.fillMaxWidth(pos), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.offset(x = 9.dp).size(18.dp).clip(CircleShape).background(Color(0xFFFF0000)))
            }
        }
    }
}

/** Channel, numbers and the description, as a panel on the right. Each paragraph can take focus, so Up/Down scroll through it. */
@Composable
private fun TvDetailsPanel(info: VideoInfo, channelFallback: String, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    fun short(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1e6); n >= 1_000 -> "%.1fK".format(n / 1e3); else -> n.toString() }.replace(".0", "")
    val channel = info.channel.ifBlank { channelFallback }
    val stats = listOfNotNull(
        info.views?.let { "${short(it)} views" }, info.uploaded.takeIf { it.isNotBlank() }, info.likes?.let { "${short(it)} likes" },
    ).joinToString(" · ")
    val paragraphs = remember(info.description) { info.description.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() } }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss)) {
        Surface(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(700.dp).clickable(enabled = false) {},
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        ) {
            LazyColumn(contentPadding = PaddingValues(36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Column(Modifier.focusRequester(first).focusable().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(channel, style = MaterialTheme.typography.headlineSmall)
                        info.subscribers?.let { Text("${short(it)} subscribers", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (stats.isNotBlank()) Text(stats, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                itemsIndexed(paragraphs) { _, p ->
                    var focused by remember { mutableStateOf(false) }
                    Text(
                        p, style = MaterialTheme.typography.bodyLarge, fontSize = 20.sp, lineHeight = 28.sp,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.focusable()
                            .border(if (focused) 3.dp else 0.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(10.dp),
                    )
                }
                item { Text("Back closes this panel.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** What YouTube suggests next, as a strip of cards along the bottom. Back closes it. */
@Composable
@UnstableApi
private fun TvRelatedStrip(videos: List<Video>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000), Color(0xF5000000))))
            .onPreviewKeyEvent { e -> if (e.key == Key.DirectionUp && e.type == KeyEventType.KeyDown) { onDismiss(); true } else false }, // Up closes it, like Back
    ) {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Related videos", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(start = 56.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(videos, key = { i, v -> "$i-${v.id}" }) { i, v ->
                    TvCard(v, progressOf(app, v), onClick = { onPick(i) }, onMenu = {}, width = 250.dp, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
                }
            }
            Text("Up or Back to close", style = MaterialTheme.typography.bodyMedium, color = Color(0xCCFFFFFF), modifier = Modifier.padding(start = 56.dp))
        }
    }
}

/** Stream, quality, speed and autoplay for what is playing now, as a panel on the right. */
@Composable
@UnstableApi
private fun TvOptionsPanel(onDismiss: () -> Unit) {
    val app = LocalContext.current.tubeApp
    val pb = LocalPlayback.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss)) {
        Surface(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(620.dp).clickable(enabled = false) {},
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(36.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Playback options", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton({ app.resetOverrides(); pb.reload(true) }, enabled = app.hasOverrides) { Text("Reset") }
                }
                if (pb.textTracks.isNotEmpty()) OptionGroup("Subtitles") {
                    OptionChip("Off", !pb.subtitlesOn, Modifier.focusRequester(first)) { pb.selectSubtitles(null) }
                    pb.textTracks.forEach { t -> OptionChip(t.label + if (t.supported) "" else " · not supported", t.selected, enabled = t.supported) { pb.selectSubtitles(t) } }
                }
                if (pb.audioTracks.size > 1) OptionGroup("Audio") {
                    pb.audioTracks.forEach { t -> OptionChip(t.label + if (t.supported) "" else " · not supported", t.selected, enabled = t.supported) { pb.selectAudio(t) } }
                }
                OptionGroup("Stream") {
                    OptionChip("Video", app.stream == "video", if (pb.textTracks.isEmpty()) Modifier.focusRequester(first) else Modifier) { app.ovStream = if ("video" == app.store.stream) null else "video"; pb.reload(true) }
                    OptionChip("Audio only", app.stream == "audio") { app.ovStream = if ("audio" == app.store.stream) null else "audio"; pb.reload(true) }
                }
                OptionGroup("Quality") {
                    listOf(360, 480, 720, 1080).forEach { q ->
                        OptionChip(qualityLabel(q), app.quality == q, enabled = app.stream == "video") { app.ovQuality = if (q == app.store.quality) null else q; pb.reload(true) }
                    }
                }
                OptionGroup("Speed") {
                    OPTION_SPEEDS.forEach { s -> OptionChip("${s}×", pb.speed == s) { pb.changeSpeed(s) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Autoplay next", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Switch(app.autoplay, { app.ovAutoplay = if (it == app.store.autoplay) null else it; pb.reload(false) })
                }
                Text("Back closes this panel.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OptionGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun OptionChip(text: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    app.kinora.ui.FocusChip(
        selected = selected, onClick = onClick, enabled = enabled, modifier = modifier,
        label = { Text(text, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)) },
    )
}
