package app.localtube.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Reads a YouTube stream the way the PC server does, because a plain download is slow (about playback speed, so a
 * video takes ~30 s to start and then stalls):
 *  - it asks for windows with the `range=a-b` URL *parameter* (not a Range header), which isn't throttled;
 *  - several windows are in flight at once, so a slow one doesn't hold up playback;
 *  - a window that is slow is dropped after a few seconds and asked for again: a fresh connection is often fast
 *    (from one network, windows came back at either ~2 MB/s or ~0.1 MB/s, apparently at random).
 * It also makes seeking cheap: a seek just starts fetching at the new position.
 *
 * Needs the stream's length, which YouTube puts in the URL as `clen=`. Without it, falls back to a plain read.
 */
@UnstableApi
class YtChunkedDataSource(private val fallbackFactory: DataSource.Factory, private val userAgent: String) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var fallback: DataSource? = null
    private var baseUri: Uri? = null
    private var chunked = false

    private var pos = 0L              // next byte to hand to the player
    private var end = 0L              // exclusive
    private var nextFetch = 0L        // where the next window to start fetching begins
    private var started = false       // has the first (small) window been scheduled
    private val pending = ArrayDeque<Fetch>()
    private var current: ByteArray? = null
    private var currentOffset = 0

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        fallback?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseUri = dataSpec.uri
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        chunked = clen != null && dataSpec.uri.getQueryParameter("range") == null
        if (!chunked) {
            val ds = fallbackFactory.createDataSource().also { d -> listeners.forEach(d::addTransferListener) }
            fallback = ds
            return ds.open(dataSpec)
        }
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(clen!!, pos + dataSpec.length) else clen!!
        nextFetch = pos
        started = false
        current = null; currentOffset = 0
        schedule()
        return end - pos
    }

    private fun schedule() {
        while (pending.size < PARALLEL && nextFetch < end) {
            val size = if (!started) FIRST_WINDOW else WINDOW
            started = true
            val to = minOf(end, nextFetch + size)
            val f = Fetch(baseUri!!, nextFetch, to)
            pending.add(f)
            EXECUTOR.execute(f.task)
            nextFetch = to
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return fallback!!.read(buffer, offset, length)
        var cur = current
        if (cur == null || currentOffset >= cur.size) {
            if (pos >= end) return C.RESULT_END_OF_INPUT
            val f = pending.pollFirst() ?: return C.RESULT_END_OF_INPUT
            schedule() // keep the pipeline full while we wait for this one
            cur = try {
                f.task.get()
            } catch (e: ExecutionException) {
                throw (e.cause as? IOException) ?: IOException(e.cause)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException()
            }
            current = cur; currentOffset = 0
        }
        val data = cur ?: return C.RESULT_END_OF_INPUT
        val n = minOf(length, data.size - currentOffset)
        System.arraycopy(data, currentOffset, buffer, offset, n)
        currentOffset += n
        pos += n
        return n
    }

    override fun getUri(): Uri? = baseUri

    override fun getResponseHeaders(): Map<String, List<String>> = fallback?.responseHeaders ?: emptyMap()

    override fun close() {
        pending.forEach(Fetch::cancel)
        pending.clear()
        current = null
        try { fallback?.close() } finally { fallback = null; baseUri = null }
    }

    /** One window: bytes [start, to) of the stream, downloaded in full. Retries with a fresh connection when slow. */
    private inner class Fetch(private val base: Uri, private val start: Long, private val to: Long) : Callable<ByteArray> {
        @Volatile private var cancelled = false
        @Volatile private var call: okhttp3.Call? = null
        val task = FutureTask(this)

        fun cancel() {
            cancelled = true
            call?.cancel()
            task.cancel(true)
        }

        override fun call(): ByteArray {
            val want = (to - start).toInt()
            val timeouts = if (want <= FIRST_WINDOW) FIRST_TIMEOUTS else TIMEOUTS
            val buf = ByteArray(want)
            var got = 0 // kept across attempts: a slow attempt is cut off, but what it downloaded is not thrown away
            var last: IOException? = null
            for ((attempt, limit) in timeouts.withIndex()) {
                if (cancelled) throw InterruptedIOException("cancelled")
                val t0 = SystemClock.elapsedRealtime()
                val before = got
                try {
                    // ask only for what is still missing, on a fresh connection (a new one is often much faster)
                    val url = base.buildUpon().appendQueryParameter("range", "${start + got}-${to - 1}").build().toString()
                    val c = CLIENT.newBuilder().callTimeout(limit, TimeUnit.MILLISECONDS).build()
                        .newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
                    call = c
                    c.execute().use { r ->
                        if (!r.isSuccessful) throw IOException("YouTube answered ${r.code} for bytes ${start + got}-${to - 1}")
                        val body = r.body ?: throw IOException("empty body")
                        body.byteStream().use { input ->
                            while (got < want) {
                                val n = input.read(buf, got, want - got)
                                if (n < 0) break
                                got += n
                            }
                        }
                    }
                    if (got >= want) return buf
                    last = IOException("short window: $got of $want bytes")
                } catch (e: IOException) {
                    if (cancelled) throw InterruptedIOException("cancelled")
                    last = e
                }
                Log.d("LT-win", "window at ${start / 1024} KB: attempt ${attempt + 1} got ${(got - before) / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms (${last?.javaClass?.simpleName}), $got/$want so far")
            }
            throw last ?: IOException("window at $start failed")
        }
    }

    companion object {
        private const val FIRST_WINDOW = 384 * 1024L   // small, so playback starts quickly
        private const val WINDOW = 1024 * 1024L
        private const val PARALLEL = 4                 // windows in flight (the PC server uses 4 as well)
        private val FIRST_TIMEOUTS = longArrayOf(3_000, 4_000, 8_000, 15_000, 30_000, 30_000)
        private val TIMEOUTS = longArrayOf(4_000, 5_000, 10_000, 20_000, 30_000, 30_000)
        private val CLIENT = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build()
        private val EXECUTOR = Executors.newCachedThreadPool { r -> Thread(r, "yt-window").apply { isDaemon = true } }
    }
}
