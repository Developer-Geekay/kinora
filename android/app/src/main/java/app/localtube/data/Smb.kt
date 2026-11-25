package app.localtube.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.localtube.player.Streams
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Properties

/** A computer or NAS on the home network that shares files over SMB (a Windows share, Samba, most NAS boxes). */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val user: String = "",      // blank = guest
    val password: String = "",
    val domain: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("host", host)
        .put("user", user).put("password", password).put("domain", domain)

    companion object {
        fun fromJson(o: JSONObject) = SmbServer(
            o.getString("id"), o.optString("name").ifBlank { o.getString("host") }, o.getString("host"),
            o.optString("user"), o.optString("password"), o.optString("domain"),
        )
    }
}

/** One thing in a folder. [path] is relative to the server: "share/folder/file.mkv" ("" = the server's list of shares). */
data class SmbEntry(val name: String, val isDir: Boolean, val size: Long, val path: String, val modified: Long = 0L)

private val VIDEO_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "wmv", "flv", "ogv", "avi")
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "mka")
private val SUB_EXT = mapOf("srt" to MimeTypes.APPLICATION_SUBRIP, "vtt" to MimeTypes.TEXT_VTT, "ass" to MimeTypes.TEXT_SSA, "ssa" to MimeTypes.TEXT_SSA)

private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
fun isVideoFile(name: String) = ext(name) in VIDEO_EXT
fun isAudioFile(name: String) = ext(name) in AUDIO_EXT
fun isMediaFile(name: String) = isVideoFile(name) || isAudioFile(name)

/** Videos from a share have ids like "smb:<server id>|share/folder/file.mkv". */
const val SMB_PREFIX = "smb:"
fun smbVideoId(server: SmbServer, path: String) = "$SMB_PREFIX${server.id}|$path"
fun parseSmbId(id: String): Pair<String, String>? =
    if (id.startsWith(SMB_PREFIX) && '|' in id) id.removePrefix(SMB_PREFIX).substringBefore('|') to id.substringAfter('|') else null

/**
 * Browsing and reading files on SMB shares (jcifs-ng: SMB 2/3, NTLM). Everything stays on the home network and the
 * passwords stay on this device.
 */
class SmbSource(private val store: Store) {
    private val contexts = HashMap<String, Pair<String, CIFSContext>>()
    private val dirCache = object : LinkedHashMap<String, Pair<Long, List<SmbEntry>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<SmbEntry>>>?) = size > 24
    }

    fun server(id: String): SmbServer? = store.smbServers.firstOrNull { it.id == id }

    /** One connection context per server; rebuilt when its login changes. */
    fun context(s: SmbServer): CIFSContext = synchronized(contexts) {
        val key = "${s.host}|${s.domain}|${s.user}|${s.password}"
        contexts[s.id]?.takeIf { it.first == key }?.second ?: newContext(s).also { contexts[s.id] = key to it }
    }

    private fun newContext(s: SmbServer): CIFSContext {
        val p = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")   // SMB1 is off on current Windows and insecure
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "30000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.smb.client.ipcSigningEnforced", "false") // guest / anonymous logins
            setProperty("jcifs.traceResources", "false")
        }
        val base = BaseContext(PropertyConfiguration(p))
        return if (s.user.isBlank()) base.withGuestCrendentials()
        else base.withCredentials(NtlmPasswordAuthenticator(s.domain.ifBlank { null }, s.user, s.password))
    }

    /**
     * smb://host/share/folder/ for jcifs; a folder ends with a slash. jcifs wants the names as they are (spaces, +, &, brackets
     * and so on): percent-escaping them made it look for a file literally called "a%20b". Only the three characters a URL itself
     * would trip on are escaped.
     */
    fun url(s: SmbServer, path: String, dir: Boolean): String {
        val parts = path.split('/').filter { it.isNotEmpty() }
        val enc = parts.joinToString("/") { it.replace("%", "%25").replace("#", "%23").replace("?", "%3F") }
        return "smb://${s.host}/" + enc + if (parts.isNotEmpty() && dir) "/" else ""
    }

    /**
     * The folder's contents: folders first, then files, each by name. [all] keeps every file; otherwise only folders and
     * video / audio files are returned. At the top, a server lists its shares (the hidden admin ones, C$ and so on, are left out).
     */
    suspend fun list(s: SmbServer, path: String, all: Boolean = false): List<SmbEntry> = withContext(Dispatchers.IO) {
        val key = "${s.id}|$path"
        val cached = synchronized(dirCache) { dirCache[key]?.takeIf { System.currentTimeMillis() - it.first < 30_000 }?.second }
        val raw = cached ?: wrap {
            val dir = SmbFile(url(s, path, true), context(s))
            val entries = dir.listFiles().mapNotNull { f ->
                val name = f.name.trimEnd('/')
                if (name.isEmpty() || name.endsWith("$") || name.startsWith("$")) return@mapNotNull null
                val isDir = runCatching { f.isDirectory }.getOrDefault(false) || f.name.endsWith("/")
                val size = if (isDir) 0L else runCatching { f.length() }.getOrDefault(0L)
                val modified = if (isDir && path.isEmpty()) 0L else runCatching { f.lastModified() }.getOrDefault(0L)
                SmbEntry(name, isDir, size, if (path.isEmpty()) name else "$path/$name", modified)
            }
            synchronized(dirCache) { dirCache[key] = System.currentTimeMillis() to entries }
            entries
        }
        raw.filter { all || it.isDir || isMediaFile(it.name) }
            .sortedWith(compareBy<SmbEntry>({ !it.isDir }, { it.name.lowercase() }))
    }

    /** Checks the address and login by listing the server's shares. */
    suspend fun test(s: SmbServer) { list(s, "") }

    fun forget(serverId: String) = synchronized(contexts) { contexts.remove(serverId) }

    /** The player item for a file on a share, with a same-named .srt / .vtt / .ass next to it as subtitles. */
    suspend fun item(v: Video): MediaItem {
        val (serverId, path) = parseSmbId(v.id) ?: throw ApiError("That isn't a file on a share.")
        val s = server(serverId) ?: throw ApiError("That server was removed. Add it again under Network.")
        val name = path.substringAfterLast('/')
        val base = name.substringBeforeLast('.', name)
        val folder = path.substringBeforeLast('/', "")

        val subs = runCatching {
            list(s, folder, all = true)
                .filter { !it.isDir && ext(it.name) in SUB_EXT && it.name.startsWith(base, ignoreCase = true) }
                .take(3)
        }.getOrDefault(emptyList())

        val subConfigs = subs.mapIndexed { i, e ->
            // "Movie.en.srt" -> language "en"
            val lang = e.name.removeSuffix("." + ext(e.name)).removePrefix(base).trim('.', ' ', '-', '_').takeIf { it.length in 2..3 }
            MediaItem.SubtitleConfiguration.Builder(smbUri(s, e.path))
                .setMimeType(SUB_EXT.getValue(ext(e.name)))
                .setLanguage(lang)
                .setLabel(e.name)
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val audio = isAudioFile(name)
        val extras = android.os.Bundle().apply {
            putLong(Streams.DURATION_MS, v.duration * 1000)
            putLong(Streams.START_AT_MS, 0L)
            putString(Streams.MODE, if (audio) "audio" else "video")
            putString("channel", s.name)
            putBoolean(Streams.NATIVE, true) // a share is seekable: the player seeks it itself
        }
        return MediaItem.Builder()
            .setMediaId(v.id)
            .setUri(smbUri(s, path))
            .setSubtitleConfigurations(subConfigs)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(base)
                    .setArtist(s.name + folder.takeIf { it.isNotEmpty() }?.let { " · " + it.substringAfterLast('/') }.orEmpty())
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** The URI the player reads: smb://<server id>@host/path. The login is looked up by id, never put in the address. */
    fun smbUri(s: SmbServer, path: String): Uri = Uri.Builder().scheme("smb")
        .encodedAuthority("${s.id}@${s.host}")
        .encodedPath("/" + path.split('/').filter { it.isNotEmpty() }.joinToString("/") { Uri.encode(it) })
        .build()

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: SmbAuthException) {
        throw ApiError("Wrong user name or password, or this share needs a login. (Leave the user blank to try as a guest.)")
    } catch (e: UnknownHostException) {
        throw ApiError("Can't find that computer. Check the address.")
    } catch (e: ConnectException) {
        throw ApiError("Can't connect. Is the computer on, and is file sharing (SMB) enabled?")
    } catch (e: NoRouteToHostException) {
        throw ApiError("Can't reach that computer on this network.")
    } catch (e: SocketTimeoutException) {
        throw ApiError("The computer didn't answer in time.")
    } catch (e: SmbException) {
        val m = e.message.orEmpty()
        throw ApiError(
            when {
                m.contains("Failed to connect", true) || m.contains("Connection", true) && m.contains("refused", true) ->
                    "Can't connect. Is the computer on, and is file sharing (SMB) enabled?"
                m.contains("BAD_NETWORK_NAME", true) || m.contains("not found", true) -> "That share or folder wasn't found."
                m.contains("ACCESS_DENIED", true) -> "Access denied: that login can't open this."
                m.contains("LOGON_FAILURE", true) -> "Wrong user name or password."
                else -> "Couldn't read from the share: " + m.ifBlank { e.javaClass.simpleName }
            },
        )
    } catch (e: IOException) {
        throw ApiError("Couldn't read from the share: " + e.message.orEmpty())
    }
}
