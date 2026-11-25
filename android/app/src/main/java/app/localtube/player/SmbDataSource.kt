package app.localtube.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import app.localtube.TubeApp
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Reads a file on an SMB share for the player: smb://<server id>@host/share/folder/file. It reads big chunks (one network
 * round trip each) and hands out the bytes in whatever sizes the player asks for, and it can start anywhere, so seeking is instant.
 */
@UnstableApi
class SmbDataSource(private val app: TubeApp) : BaseDataSource(/* isNetwork = */ true) {
    private var uri: Uri? = null
    private var file: SmbRandomAccessFile? = null
    private var remaining = 0L
    private val chunk = ByteArray(CHUNK)
    private var chunkPos = 0
    private var chunkEnd = 0
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        val u = dataSpec.uri
        uri = u
        transferInitializing(dataSpec)
        val server = u.userInfo?.let { app.smb.server(it) } ?: throw IOException("That server was removed.")
        val path = (u.path ?: "").trim('/')
        try {
            val ctx = app.smb.context(server)
            val url = app.smb.url(server, path, dir = false)
            val length = SmbFile(url, ctx).length()
            val raf = SmbRandomAccessFile(url, "r", 1 /* FILE_SHARE_READ */, ctx)
            raf.seek(dataSpec.position)
            file = raf
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else (length - dataSpec.position).coerceAtLeast(0)
        } catch (e: Exception) {
            android.util.Log.w("LT-smb", "open failed for ${server.host}/$path: $e")
            try { file?.close() } catch (_: Exception) {}
            file = null
            throw (e as? IOException) ?: IOException("Can't open the file on the share: ${e.message}", e)
        }
        chunkPos = 0; chunkEnd = 0
        started = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkPos >= chunkEnd) {
            val raf = file ?: throw IOException("closed")
            val n = try { raf.read(chunk, 0, minOf(CHUNK.toLong(), remaining).toInt()) } catch (e: SmbException) { throw IOException(e.message, e) }
            if (n < 0) return C.RESULT_END_OF_INPUT
            chunkPos = 0; chunkEnd = n
        }
        val n = minOf(length, chunkEnd - chunkPos)
        System.arraycopy(chunk, chunkPos, buffer, offset, n)
        chunkPos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { file?.close() } catch (_: Exception) {} finally {
            file = null; chunkPos = 0; chunkEnd = 0
            if (started) { started = false; transferEnded() } // only if open() got as far as transferStarted()
        }
    }

    private companion object { const val CHUNK = 512 * 1024 }
}
