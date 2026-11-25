package app.localtube.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers: the server address and PIN, your default settings, history,
 * playlists and resume positions. Properties are Compose state, so screens update when they change.
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("localtube", Context.MODE_PRIVATE)

    // where streams come from: "device" = looked up on this phone (default, nothing else needed), "pc" = your LocalTube server
    var source by mutableStateOf(sp.getString("source", "device") ?: "device")

    // the PC server (only used when source == "pc")
    var server by mutableStateOf(sp.getString("server", "") ?: "")
    var pin by mutableStateOf(sp.getString("pin", "") ?: "")

    // your defaults (the player screen can override stream/quality/autoplay for the current session)
    var stream by mutableStateOf(sp.getString("stream", "video") ?: "video")
    var quality by mutableIntStateOf(sp.getInt("quality", 720))
    var autoplay by mutableStateOf(sp.getBoolean("autoplay", true))
    var saveHistory by mutableStateOf(sp.getBoolean("saveHistory", true))
    var theme by mutableStateOf(sp.getString("theme", "system") ?: "system") // system | light | dark
    var dynamicColor by mutableStateOf(sp.getBoolean("dynamicColor", false))
    /** The last few searches, newest first: the TV offers them as one-press chips (typing with a remote is slow). */
    var recentSearches by mutableStateOf((sp.getString("recents", "") ?: "").split('\n').filter { it.isNotBlank() }); private set
    fun addRecentSearch(q: String) {
        val one = q.replace('\n', ' ').trim().take(80); if (one.isEmpty()) return
        recentSearches = (listOf(one) + recentSearches.filterNot { it.equals(one, true) }).take(8)
        sp.edit().putString("recents", recentSearches.joinToString("\n")).apply()
    }
    fun clearRecentSearches() { recentSearches = emptyList(); sp.edit().remove("recents").apply() }

    /** Suggestions ignore what you watched before this moment (set by "Reset recommendations"). Your history itself stays. */
    var recsSince by androidx.compose.runtime.mutableLongStateOf(sp.getLong("recsSince", 0L)); private set
    fun resetRecommendations() { recsSince = System.currentTimeMillis(); sp.edit().putLong("recsSince", recsSince).apply() }
    var layout by mutableStateOf(sp.getString("layout", "auto") ?: "auto") // auto | phone | tv

    val smbServers = mutableStateListOf<SmbServer>().also { it.addAll(readList("smb") { o -> SmbServer.fromJson(o) }) }
    val history = mutableStateListOf<HistoryEntry>().also { it.addAll(readList("history") { o -> HistoryEntry.fromJson(o) }) }
    val playlists = mutableStateListOf<Playlist>().also { it.addAll(readList("playlists") { o -> Playlist.fromJson(o) }) }
    private val positions: MutableMap<String, Int> = HashMap<String, Int>().also { m ->
        runCatching {
            val o = JSONObject(sp.getString("positions", "{}") ?: "{}")
            o.keys().forEach { k -> m[k] = o.getInt(k) }
        }
    }

    /** On the phone there is nothing to set up; the PC source needs an address first. */
    val configured get() = source == "device" || server.isNotBlank()

    fun saveConnection() = sp.edit().putString("server", server).putString("pin", pin).putString("source", source).apply()

    fun saveSettings() = sp.edit()
        .putString("source", source)
        .putString("stream", stream).putInt("quality", quality).putBoolean("autoplay", autoplay)
        .putString("layout", layout).putBoolean("saveHistory", saveHistory).putString("theme", theme).putBoolean("dynamicColor", dynamicColor).apply()

    // ----- network shares (SMB) -----
    fun saveSmbServer(s: SmbServer) {
        val i = smbServers.indexOfFirst { it.id == s.id }
        if (i >= 0) smbServers[i] = s else smbServers.add(s)
        persistSmb()
    }
    fun removeSmbServer(id: String) { smbServers.removeAll { it.id == id }; persistSmb() }
    private fun persistSmb() = sp.edit().putString("smb", JSONArray().also { a -> smbServers.forEach { a.put(it.toJson()) } }.toString()).apply()

    /** A video's length is only known once it plays (files on shares): remember it so lists can show progress. */
    fun setDuration(id: String, sec: Long) {
        val i = history.indexOfFirst { it.video.id == id }
        if (i >= 0 && history[i].video.duration == 0L && sec > 0) { history[i] = history[i].copy(video = history[i].video.copy(duration = sec)); persistHistory() }
    }

    // ----- history -----
    fun recordHistory(v: Video) {
        if (!saveHistory) return
        history.removeAll { it.video.id == v.id }
        history.add(0, HistoryEntry(v, System.currentTimeMillis(), false))
        while (history.size > 500) history.removeAt(history.size - 1)
        persistHistory()
    }

    fun markDone(id: String) {
        val i = history.indexOfFirst { it.video.id == id }
        if (i >= 0) { history[i] = history[i].copy(done = true); persistHistory() }
    }

    fun removeHistory(id: String) { history.removeAll { it.video.id == id }; persistHistory() }
    fun clearHistory() { history.clear(); persistHistory() }
    private fun persistHistory() = sp.edit().putString("history", JSONArray().also { a -> history.forEach { a.put(it.toJson()) } }.toString()).apply()

    // ----- playlists -----
    fun createPlaylist(name: String, items: List<Video> = emptyList()): Playlist {
        val p = Playlist("p" + System.currentTimeMillis().toString(36) + (1000..9999).random(), name, items)
        playlists.add(0, p); persistPlaylists(); return p
    }

    fun updatePlaylist(p: Playlist) {
        val i = playlists.indexOfFirst { it.id == p.id }
        if (i >= 0) { playlists[i] = p; persistPlaylists() }
    }

    fun deletePlaylist(id: String) { playlists.removeAll { it.id == id }; persistPlaylists() }
    fun restorePlaylist(p: Playlist, at: Int) { playlists.add(at.coerceIn(0, playlists.size), p); persistPlaylists() }
    private fun persistPlaylists() = sp.edit().putString("playlists", JSONArray().also { a -> playlists.forEach { a.put(it.toJson()) } }.toString()).apply()

    // ----- resume positions (seconds) -----
    fun resumeSec(id: String): Int = positions[id] ?: 0

    fun savePosition(id: String, sec: Int, durationSec: Long) {
        if (sec > 15 && (durationSec <= 0 || sec < durationSec - 20)) positions[id] = sec else positions.remove(id)
        sp.edit().putString("positions", JSONObject(positions as Map<*, *>).toString()).apply()
    }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(sp.getString(key, "[]") ?: "[]")
        (0 until a.length()).map { parse(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    companion object {
        /** "192.168.1.5:3000" -> "http://192.168.1.5:3000" */
        fun normalize(input: String): String {
            var s = input.trim().trimEnd('/')
            if (s.isEmpty()) return s
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
    }
}
