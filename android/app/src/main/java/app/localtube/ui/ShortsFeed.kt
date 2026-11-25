package app.localtube.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.localtube.data.Video
import app.localtube.tubeApp
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Full-screen vertical feed of short clips: swipe up for the next, down for the previous, tap to pause.
 * One shared player plays the clip on the current page; the other pages only show their thumbnail.
 * A clip loops until you swipe to the next one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
@UnstableApi
fun ShortsFeed(items: List<Video>, start: Int, onClose: () -> Unit, onSave: (Video) -> Unit) {
    val pb = LocalPlayback.current
    val app = LocalContext.current.tubeApp
    val audio = app.stream == "audio"
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var flash by remember { mutableStateOf(false) } // brief play/pause icon after a tap
    KeepScreenOn(!audio)

    // Shorts loop until you swipe, and the feed stops when you leave it
    DisposableEffect(Unit) {
        pb.setLoop(true)
        onDispose { pb.setLoop(false); pb.stop() }
    }
    // Play the clip on the page you settled on, and only that one: the player's queue is filled one clip at a time in the
    // background, so queueing the whole feed would leave swipes pointing at clips that aren't loaded yet. Pause first so
    // the previous clip's sound stops at once instead of carrying on until the new one has loaded.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            pb.pause()
            pb.playQueue(listOf(items[page]), 0)
            // Looking a clip up takes seconds, so do the next two now: by the time you swipe, they are cached and start at once.
            for (k in page + 1..page + 2) items.getOrNull(k)?.let { n -> app.scope.launch { runCatching { app.api.item(n, app.stream, app.quality) } } }
        }
    }
    LaunchedEffect(flash) { if (flash) { kotlinx.coroutines.delay(600); flash = false } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(pager, Modifier.fillMaxSize()) { page ->
            val v = items[page]
            val current = pb.current?.id == v.id // the clip the player has loaded: only this page shows video
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { pb.toggle(); flash = true }) },
            ) {
                AsyncImage(v.thumb, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (current && !audio) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT) // keep the thumbnail until the first frame
                            }
                        },
                        update = { it.player = pb.player },
                        onRelease = { it.player = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (page == pager.currentPage && (pb.starting || pb.buffering)) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = MaterialTheme.colorScheme.primary)
                if (page == pager.currentPage && pb.error != null) Text(pb.error ?: "", color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))

                // title and channel over a soft shade at the bottom
                Column(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                        .navigationBarsPadding().padding(16.dp, 48.dp, 80.dp, 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(v.channel, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(v.title, color = Color.White, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                // actions down the right edge
                Column(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 8.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton({ onSave(v) }) { Icon(Icons.Filled.PlaylistAdd, "Save to playlist", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    Text("Save", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        AnimatedVisibility(flash, Modifier.align(Alignment.Center)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0x88000000)), Alignment.Center) {
                Icon(if (pb.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp))
            }
        }

        Row(Modifier.statusBarsPadding().padding(4.dp).align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text("Shorts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        LinearProgressIndicator(
            progress = { if (pb.durationMs > 0) (pb.positionMs.toFloat() / pb.durationMs).coerceIn(0f, 1f) else 0f },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(), trackColor = Color(0x33FFFFFF),
        )
    }
}
