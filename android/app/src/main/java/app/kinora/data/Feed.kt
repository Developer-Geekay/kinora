package app.kinora.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kinora.TubeApp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The Home page's suggestions. YouTube builds its home page from what you watched; without a login we do the same locally:
 *  - "Recommended": the videos YouTube suggests after each of your last few watched videos, mixed together, minus what you've seen.
 *  - "From channels you watch": recent uploads / popular videos of those channels.
 * With no watch history yet it falls back to a few general searches so the page isn't empty.
 */
class Feed(private val app: TubeApp) {
    var recommended by mutableStateOf<List<Video>>(emptyList()); private set
    var fromChannels by mutableStateOf<List<Video>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    private var builtFor = ""
    private var builtAt = 0L

    /** Start over: forget the current suggestions and build them again from what you watch from now on. */
    fun reset() { builtFor = ""; builtAt = 0L; recommended = emptyList(); fromChannels = emptyList(); refresh(force = true) }

    /** Rebuilds the feed if your history changed or it is older than [maxAgeMs]. */
    fun refresh(force: Boolean = false, maxAgeMs: Long = 30 * 60_000L) {
        val seeds = app.store.history.filter { it.at > app.store.recsSince }.map { it.video }.filter { !it.isLocal }.distinctBy { it.id }.take(5)
        val key = seeds.joinToString(",") { it.id } + "|" + app.api.onDevice + "|" + app.store.recsSince
        val fresh = System.currentTimeMillis() - builtAt < maxAgeMs
        if (!force && fresh && key == builtFor && recommended.isNotEmpty()) return
        if (loading) return
        loading = true; error = null
        app.scope.launch {
            try {
                val seen = app.store.history.map { it.video.id }.toSet()
                if (seeds.isEmpty()) {
                    val general = coroutineScope {
                        listOf("trending now", "popular music", "news today").map { q -> async { runCatching { app.api.search(q, 12) }.getOrDefault(emptyList()) } }.awaitAll()
                    }
                    recommended = interleave(general).distinctBy { it.id }.take(40)
                    fromChannels = emptyList()
                } else {
                    val (related, byChannel) = coroutineScope {
                        val rel = seeds.map { s -> async { runCatching { app.api.related(s.id) }.getOrDefault(emptyList()) } }
                        val chans = seeds.map { it.channel }.filter { it.isNotBlank() }.distinct().take(3)
                            .map { c -> async { runCatching { app.api.search(c, 10) }.getOrDefault(emptyList()) } }
                        rel.awaitAll() to chans.awaitAll()
                    }
                    val rec = interleave(related).filter { it.id !in seen }.distinctBy { it.id }.take(40)
                    val recIds = rec.map { it.id }.toSet()
                    recommended = rec
                    fromChannels = interleave(byChannel).filter { it.id !in seen && it.id !in recIds }.distinctBy { it.id }.take(24)
                }
                builtFor = key; builtAt = System.currentTimeMillis()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't load suggestions."
            }
            loading = false
        }
    }

    /** [[a1,a2],[b1,b2]] -> a1,b1,a2,b2: one from each source in turn, so no single video dominates. */
    private fun interleave(lists: List<List<Video>>): List<Video> {
        val out = ArrayList<Video>()
        var i = 0
        while (lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let(out::add) }; i++ }
        return out
    }
}
