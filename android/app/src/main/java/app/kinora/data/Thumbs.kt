package app.kinora.data

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import app.kinora.TubeApp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Thumbnails for files on network shares (YouTube videos have their own). A frame is grabbed from the file itself,
 * once, and kept on the device. It happens one file at a time so browsing a big folder does not flood the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Thumbs(private val app: TubeApp) {
    /** Bumped each time a thumbnail appears, so screens showing a placeholder redraw. */
    var version by mutableIntStateOf(0); private set

    private val one: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val dir by lazy { File(app.cacheDir, "thumbs").also { it.mkdirs() } }
    private val inFlight = HashSet<String>()
    private val failed = HashSet<String>()

    private fun fileFor(v: Video): File {
        val h = MessageDigest.getInstance("SHA-1").digest(v.id.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
        return File(dir, "$h.jpg")
    }

    fun existing(v: Video): File? = fileFor(v).takeIf { it.isFile && it.length() > 0 }

    /** Makes the thumbnail in the background if there is not one yet. */
    fun ensure(v: Video) {
        if (!v.isLocal || isAudioFile(v.title) || existing(v) != null) return
        synchronized(this) { if (v.id in failed || !inFlight.add(v.id)) return }
        app.scope.launch(one) {
            try {
                if (generate(v)) version++ else synchronized(this@Thumbs) { failed.add(v.id) }
            } finally { synchronized(this@Thumbs) { inFlight.remove(v.id) } }
        }
    }

    private fun generate(v: Video): Boolean = runCatching {
        val (serverId, path) = parseSmbId(v.id) ?: return false
        val server = app.smb.server(serverId) ?: return false
        val file = app.smb.file(server, path, dir = false)
        val length = file.length()
        val raf = file.openRandomAccess("r", 1)
        val source = object : MediaDataSource() {
            private var start = -1L
            private val block = ByteArray(1 shl 20)
            private var have = 0
            @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= length) return -1
                if (position < start || position >= start + have) {
                    raf.seek(position)
                    have = raf.read(block, 0, minOf(block.size.toLong(), length - position).toInt()).coerceAtLeast(0)
                    start = position
                    if (have == 0) return -1
                }
                val n = minOf(size, (start + have - position).toInt())
                System.arraycopy(block, (position - start).toInt(), buffer, offset, n)
                return n
            }
            override fun getSize() = length
            override fun close() { runCatching { raf.close() } }
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(source)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            // a frame a little way in: the very start is usually black or a logo
            val atMs = if (durMs > 0) (durMs * 0.1).toLong().coerceIn(3_000, 150_000).coerceAtMost(durMs / 2) else 5_000
            val frame = r.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: r.getFrameAtTime(-1) ?: return false
            val w = 480
            val bmp = if (frame.width > w) Bitmap.createScaledBitmap(frame, w, (frame.height * w / frame.width).coerceAtLeast(1), true) else frame
            val out = fileFor(v)
            val tmp = File(out.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            tmp.renameTo(out)
            true
        } finally {
            runCatching { r.release() }
            source.close()
        }
    }.getOrDefault(false)
}
