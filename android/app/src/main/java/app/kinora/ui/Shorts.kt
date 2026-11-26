package app.kinora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import app.kinora.data.ApiError
import app.kinora.data.Video
import app.kinora.tubeApp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/** Real YouTube Shorts (vertical clips), from [app.kinora.data.Api.shorts]. Kept across tab switches. */
class ShortsState {
    var items by mutableStateOf<List<Video>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun load(app: app.kinora.TubeApp, scope: kotlinx.coroutines.CoroutineScope, force: Boolean = false) {
        if (loading || (items.isNotEmpty() && !force)) return
        loading = true; error = null
        scope.launch {
            try {
                items = app.api.shorts(60).filter { !it.live }.shuffled()
                if (items.isEmpty()) error = "No short clips came back. Try again."
            } catch (e: ApiError) { error = e.message }
            loading = false
        }
    }
}

@Composable
@UnstableApi
fun ShortsScreen(state: ShortsState, onOpen: (Int) -> Unit) {
    val app = LocalContext.current.tubeApp
    val scope = rememberCoroutineScope()
    LaunchedEffect(app.store.source) { state.load(app, scope) }

    when {
        state.items.isEmpty() && state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        state.items.isEmpty() -> EmptyState("Shorts", state.error ?: "Nothing here yet.",
            action = { TextButton({ state.load(app, scope, force = true) }) { Text("Try again") } })
        else -> LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.items, key = { it.id }) { v ->
                Box(
                    Modifier.fillMaxWidth().aspectRatio(9f / 16f).clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onOpen(state.items.indexOf(v).coerceAtLeast(0)) },
                ) {
                    AsyncImage(v.thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(10.dp, 28.dp, 10.dp, 10.dp),
                    ) {
                        Text(v.title, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(v.channel, color = Color(0xCCFFFFFF), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                    TextButton({ state.load(app, scope, force = true) }, enabled = !state.loading) { Text("Refresh") }
                }
            }
        }
    }
}
