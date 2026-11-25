package app.localtube.data

import org.json.JSONArray
import org.json.JSONObject

/** A video as the app handles it. [duration] is in seconds (0 = unknown). */
data class Video(
    val id: String,
    val title: String,
    val channel: String = "",
    val duration: Long = 0,
    val views: Long? = null,
    val verified: Boolean = false,
    val live: Boolean = false,
) {
    /** A file on a network share rather than a YouTube video. */
    val isLocal get() = id.startsWith(SMB_PREFIX)
    val thumb get() = if (isLocal) "" else "https://i.ytimg.com/vi/$id/mqdefault.jpg"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("channel", channel).put("duration", duration)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"),
            title = o.optString("title", o.getString("id")),
            channel = o.optString("channel"),
            duration = o.optLong("duration"),
        )

        /** One row of /api/search. */
        fun fromSearch(o: JSONObject) = Video(
            id = o.getString("id"),
            title = o.optString("title"),
            channel = o.optString("channel"),
            duration = o.optLong("duration"),
            views = if (o.isNull("views")) null else o.optLong("views"),
            verified = o.optBoolean("verified"),
            live = o.optBoolean("live"),
        )
    }
}

data class HistoryEntry(val video: Video, val at: Long, val done: Boolean) {
    fun toJson(): JSONObject = video.toJson().put("at", at).put("done", done)

    companion object {
        fun fromJson(o: JSONObject) = HistoryEntry(Video.fromJson(o), o.optLong("at"), o.optBoolean("done"))
    }
}

data class Playlist(val id: String, val name: String, val items: List<Video>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("items", JSONArray().also { a -> items.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Playlist {
            val a = o.optJSONArray("items") ?: JSONArray()
            return Playlist(o.getString("id"), o.optString("name", "Playlist"), (0 until a.length()).map { Video.fromJson(a.getJSONObject(it)) })
        }
    }
}

/** What /api/info returns for one video. */
data class VideoInfo(
    val title: String,
    val channel: String,
    val duration: Long,
    val live: Boolean = false,
    val views: Long?,
    val description: String,
    val subs: List<SubTrack>,
    val tags: List<String> = emptyList(),
    val uploaded: String = "",
    val likes: Long? = null,
    val subscribers: Long? = null,
    val category: String = "",
    val verified: Boolean = false,
    val related: List<Video> = emptyList(), // what YouTube suggests next
)

data class SubTrack(val lang: String, val name: String, val auto: Boolean)

data class Cue(val start: Double, val end: Double, val text: String)

fun formatTime(totalSeconds: Long): String {
    val s = if (totalSeconds < 0) 0 else totalSeconds
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatViews(n: Long?): String {
    if (n == null) return ""
    val text = when {
        n >= 1_000_000_000 -> "%.1fB".format(n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.1fK".format(n / 1e3)
        else -> n.toString()
    }.replace(".0", "")
    return "$text view" + if (n == 1L) "" else "s"
}

fun qualityLabel(h: Int) = if (h == 2160) "4K" else "${h}p"
