package app.kinora.data

import androidx.media3.common.MediaItem
import app.kinora.player.Streams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiError(message: String) : Exception(message)

/** Talks to the Kinora server on your PC. Every request carries the PIN. */
class Api(private val store: Store) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS) // the first lookup of a video can take several seconds
        .build()

    private val device = DeviceSource()
    lateinit var smb: SmbSource

    /** True when streams come from YouTube directly, looked up on this phone (no PC, no PIN). */
    val onDevice: Boolean get() = store.source == "device"

    fun base(): String = Store.normalize(store.server)

    /**
     * The media item to play [v] as [mode] ("video" | "audio") at up to [quality] p.
     * On the phone this resolves YouTube's real URLs; with the PC source it just points at the PC's stream.
     * [startAtSec] only matters for the PC source (its video stream can't be seeked, so a seek starts a new one).
     * [fresh] skips cached lookups: use it when retrying after an error.
     */
    suspend fun item(v: Video, mode: String, quality: Int, startAtSec: Double = 0.0, fresh: Boolean = false): MediaItem {
        if (v.isLocal) return smb.item(v) // a file on a network share: no YouTube, no PC
        if (!onDevice) return Streams.item(base(), v, mode, quality, startAtSec)
        val t0 = android.os.SystemClock.elapsedRealtime()
        val item = if (mode == "audio") {
            device.audioOnly(v.id, fresh).let { u -> if (u.contains(".m3u8") || u.contains("/manifest/")) Streams.liveItem(v, u) else Streams.deviceItem(v, "audio", u, null) }
        } else {
            val s = device.streams(v.id, quality, fresh)
            if (s.live) Streams.liveItem(v, s.video!!) else Streams.deviceItem(v, "video", s.video!!, s.audio)
        }
        android.util.Log.i("LT-time", "lookup of ${v.id}: ${android.os.SystemClock.elapsedRealtime() - t0} ms")
        return item
    }

    private suspend fun getText(path: String, server: String = base(), pin: String = store.pin): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(server + path).header("X-LT-Key", pin).build()
        try {
            http.newCall(req).execute().use { r ->
                val body = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw ApiError(errorText(r.code, body))
                body
            }
        } catch (e: ApiError) {
            throw e
        } catch (e: IOException) {
            throw ApiError("Can't reach the server. Is Kinora running on your PC (npm run lan) and are you on the same Wi-Fi?")
        } catch (e: IllegalArgumentException) {
            throw ApiError("That server address doesn't look right.")
        }
    }

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return when {
            code == 401 -> "Wrong PIN."
            code == 429 -> "Too many wrong PINs. Wait a few minutes and try again."
            code == 403 -> "The server refused this device (it only answers your home network)."
            msg.isNotBlank() -> msg
            else -> "Server error ($code)."
        }
    }

    /** Checks the address and PIN. Throws [ApiError] with a readable message. */
    suspend fun ping(server: String, pin: String) {
        val o = JSONObject(getText("/api/ping", Store.normalize(server), pin))
        if (!o.optBoolean("ok")) throw ApiError("That isn't a Kinora server.")
    }

    suspend fun search(q: String, n: Int = 20): List<Video> {
        if (onDevice) return device.search(q, n)
        val a = JSONArray(getText("/api/search?q=${enc(q)}&n=$n"))
        return (0 until a.length()).map { Video.fromSearch(a.getJSONObject(it)) }
    }

    /** Real Shorts: from the phone's own lookup, or from the PC server's /api/shorts. */
    suspend fun shorts(n: Int = 60): List<Video> {
        if (onDevice) return device.shorts(n)
        val a = JSONArray(getText("/api/shorts"))
        return (0 until a.length()).map { Video.fromSearch(a.getJSONObject(it)) }.take(n)
    }

    /** Videos YouTube suggests after [id] (looked up on the phone; the PC server has no such call, so it gives none). */
    suspend fun related(id: String): List<Video> = if (onDevice) device.related(id) else emptyList()

    suspend fun info(id: String, mode: String, quality: Int): VideoInfo {
        if (onDevice) return device.info(id)
        val o = JSONObject(getText("/api/info/$id?mode=$mode&q=$quality"))
        val subs = o.optJSONArray("subs") ?: JSONArray()
        return VideoInfo(
            title = o.optString("title"),
            channel = o.optString("channel"),
            duration = o.optLong("duration"),
            views = if (o.isNull("views")) null else o.optLong("views"),
            description = o.optString("description"),
            subs = (0 until subs.length()).map {
                val s = subs.getJSONObject(it)
                SubTrack(s.getString("lang"), s.optString("name"), s.optBoolean("auto"))
            },
        )
    }

    /** The keyframe time at or just before [t] seconds: where a seek really starts. */
    suspend fun start(id: String, quality: Int, t: Double): Double =
        if (onDevice) t else JSONObject(getText("/api/start/$id?q=$quality&t=$t")).optDouble("start", t)

    suspend fun cues(id: String, track: SubTrack): List<Cue> {
        if (onDevice) return emptyList()
        val a = JSONArray(getText("/api/subs/$id?lang=${enc(track.lang)}&auto=${if (track.auto) 1 else 0}"))
        return (0 until a.length()).map { val c = a.getJSONObject(it); Cue(c.getDouble("s"), c.getDouble("e"), c.getString("t")) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
