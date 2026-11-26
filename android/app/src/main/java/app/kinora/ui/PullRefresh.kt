package app.kinora.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt

/**
 * Pull the list down (when it's already at the top) to reload it. [refreshing] is the owner's own loading flag;
 * [onRefresh] starts a reload. Material3 1.2 has no pull-to-refresh, so this is a small nested-scroll one.
 */
@Composable
fun PullRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier.fillMaxSize(), content: @Composable () -> Unit) {
    val trigger = with(LocalDensity.current) { 80.dp.toPx() }
    var pull by remember { mutableFloatStateOf(0f) }
    var pulled by remember { mutableStateOf(false) } // a reload you asked for; the list shows its own spinner for automatic ones
    val refreshNow by rememberUpdatedState(onRefresh)
    androidx.compose.runtime.LaunchedEffect(refreshing) { if (!refreshing) pulled = false }
    val busy by rememberUpdatedState(refreshing)

    val connection = remember(trigger) {
        object : NestedScrollConnection {
            // content scrolled back up while we're pulling: take that first
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || pull <= 0f || available.y >= 0f) return Offset.Zero
                val used = maxOf(available.y, -pull)
                pull += used
                return Offset(0f, used)
            }
            // the list is at the top and the finger still moves down: that's the pull
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag || available.y <= 0f) return Offset.Zero
                pull = (pull + available.y * 0.5f).coerceAtMost(trigger * 1.6f)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pull >= trigger && !busy) { pulled = true; refreshNow() }
                val had = pull > 0f
                pull = 0f
                return if (had) available else Velocity.Zero
            }
        }
    }

    Box(modifier.nestedScroll(connection)) {
        content()
        val shown = if (refreshing && pulled) trigger * 0.7f else pull
        if (shown > 0f) {
            Surface(
                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopCenter).offset { androidx.compose.ui.unit.IntOffset(0, (shown - 40.dp.toPx()).roundToInt()) }.size(40.dp),
            ) {
                Box(Modifier.size(40.dp), Alignment.Center) {
                    if (refreshing && pulled) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    else CircularProgressIndicator(progress = { (pull / trigger).coerceIn(0f, 1f) }, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                }
            }
        }
    }
}
