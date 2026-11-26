package app.kinora.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.TimeUnit

private val SHORT_CHANNELS = listOf(
    "https://www.youtube.com/@MrBeast", "https://www.youtube.com/@Zhong", "https://www.youtube.com/@SSSniperWolf",
    "https://www.youtube.com/@KhabyLame", "https://www.youtube.com/@MarkRober", "https://www.youtube.com/@CarryMinati",
    "https://www.youtube.com/@BeastReacts", "https://www.youtube.com/@MrBeastGaming",
)

/** The URLs the player needs for one video. [video] is null for audio-only; [audio] is null when only a muxed stream exists. */
data class DeviceStreams(val video: String?, val audio: String?, val height: Int, val live: Boolean = false)

/**
 * Looks things up on YouTube from the phone itself, with NewPipeExtractor. No PC and no PIN.
 * YouTube hands out separate video-only and audio-only URLs; the player merges them and seeks them natively.
 */
class DeviceSource {
    private val cache = object : LinkedHashMap<String, Pair<Long, StreamInfo>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, StreamInfo>>?) = size > 12
    }

    suspend fun search(q: String, n: Int): List<Video> = withContext(Dispatchers.IO) {
        wrap {
            init()
            val yt = ServiceList.YouTube
            val handler = yt.searchQHFactory.fromQuery(q, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
            val out = ArrayList<Video>()
            fun collect(items: List<org.schabi.newpipe.extractor.InfoItem>) {
                items.forEach { (it as? StreamInfoItem)?.let { s -> toVideo(s)?.let(out::add) } }
            }
            val first = SearchInfo.getInfo(yt, handler)
            collect(first.relatedItems)
            var page = first.nextPage
            while (out.size < n && page != null) {
                val more = SearchInfo.getMoreItems(yt, handler, page)
                collect(more.items)
                page = more.nextPage
            }
            out.take(n)
        }
    }

    /**
     * Real Shorts. A search can't tell a Short from a video (searching "shorts" returns ordinary, often long, videos), so
     * read the Shorts tab of a few busy channels instead, and mix them.
     */
    suspend fun shorts(n: Int): List<Video> = withContext(Dispatchers.IO) {
        wrap {
            init()
            val yt = ServiceList.YouTube
            val lists = coroutineScope {
                SHORT_CHANNELS.shuffled().take(6).map { url ->
                    async {
                        runCatching {
                            val tab = ChannelInfo.getInfo(yt, url).tabs.first { it.contentFilters.contains(ChannelTabs.SHORTS) }
                            ChannelTabInfo.getInfo(yt, tab).relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull(::toVideo)
                        }.getOrDefault(emptyList())
                    }
                }.map { it.await() }
            }
            val out = LinkedHashMap<String, Video>()
            var i = 0
            while (out.size < n && lists.any { i < it.size }) { lists.forEach { l -> l.getOrNull(i)?.let { out.putIfAbsent(it.id, it) } }; i++ }
            if (out.isEmpty()) throw ApiError("Couldn't load Shorts from YouTube.")
            out.values.toList().take(n)
        }
    }

    /** What YouTube suggests after this video. */
    suspend fun related(id: String): List<Video> = withContext(Dispatchers.IO) {
        streamInfo(id).relatedItems.orEmpty().filterIsInstance<StreamInfoItem>().mapNotNull(::toVideo).filter { it.id != id }
    }

    suspend fun info(id: String): VideoInfo = withContext(Dispatchers.IO) {
        val i = streamInfo(id)
        VideoInfo(
            title = i.name.orEmpty(),
            channel = i.uploaderName.orEmpty(),
            duration = i.duration.coerceAtLeast(0),
            live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM,
            views = i.viewCount.takeIf { it >= 0 },
            description = plainText(i.description),
            subs = emptyList(), // captions on the phone: not built yet
            tags = i.tags.orEmpty().filter { it.isNotBlank() }.distinct(),
            uploaded = runCatching { i.uploadDate?.offsetDateTime()?.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)) }.getOrNull()
                ?: i.textualUploadDate.orEmpty(),
            likes = i.likeCount.takeIf { it >= 0 },
            subscribers = i.uploaderSubscriberCount.takeIf { it >= 0 },
            category = i.category.orEmpty(),
            verified = i.isUploaderVerified,
            related = i.relatedItems.orEmpty().filterIsInstance<StreamInfoItem>()
                .mapNotNull(::toVideo).filter { it.id != id }.distinctBy { it.id }.take(30),
        )
    }

    /** Fresh stream URLs for [id] at up to [quality] pixels tall. [force] skips the short-lived cache (use after an error). */
    suspend fun streams(id: String, quality: Int, force: Boolean = false): DeviceStreams = withContext(Dispatchers.IO) {
        val info = streamInfo(id, force)
        liveUrl(info)?.let { return@withContext DeviceStreams(it, null, 0, live = true) }
        val audio = pickAudio(info.audioStreams)
        val v = pickVideo(info.videoOnlyStreams, quality)
        if (v != null) return@withContext DeviceStreams(v.content, audio?.content, v.height)
        // some videos only have a muxed stream
        val muxed = pickVideo(info.videoStreams, quality) ?: throw ApiError("YouTube didn't give a playable video for this one.")
        DeviceStreams(muxed.content, null, muxed.height)
    }

    suspend fun audioOnly(id: String, force: Boolean = false): String = withContext(Dispatchers.IO) {
        liveUrl(streamInfo(id, force))?.let { return@withContext it }
        pickAudio(streamInfo(id, force).audioStreams)?.content ?: throw ApiError("YouTube didn't give an audio stream for this one.")
    }

    /** YouTube descriptions can come as HTML (line breaks, links, &nbsp;): turn them into plain text. */
    private fun plainText(d: org.schabi.newpipe.extractor.stream.Description?): String {
        val raw = d?.content.orEmpty()
        if (raw.isEmpty()) return ""
        val looksHtml = "<br" in raw || "&nbsp;" in raw || "<a " in raw || "&amp;" in raw || "&#" in raw
        val text = if (looksHtml) android.text.Html.fromHtml(raw, android.text.Html.FROM_HTML_MODE_LEGACY).toString() else raw
        return text.replace('\u00a0', ' ').replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** A live stream is an HLS playlist (a list of short chunks that keeps growing). Null for an ordinary video. */
    private fun liveUrl(i: StreamInfo): String? {
        val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
        if (!live) return null
        return i.hlsUrl?.takeIf { it.isNotBlank() }
            ?: throw ApiError("YouTube didn't give a playable address for this live stream.")
    }

    // ----- internals -----

    private fun streamInfo(id: String, force: Boolean = false): StreamInfo = wrap {
        init()
        synchronized(cache) { cache[id]?.takeIf { !force && System.currentTimeMillis() - it.first < 20 * 60_000 }?.second }
            ?: StreamInfo.getInfo("https://www.youtube.com/watch?v=$id").also {
                synchronized(cache) { cache[id] = System.currentTimeMillis() to it }
            }
    }

    private fun pickAudio(all: List<AudioStream>): AudioStream? {
        val usable = all.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // videos with several language tracks: the original one
        val original = usable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { usable }
        return original.maxWithOrNull(compareBy<AudioStream>({ it.format == MediaFormat.M4A }, { it.averageBitrate }))
    }

    private fun pickVideo(all: List<VideoStream>, quality: Int): VideoStream? {
        val usable = all.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        if (usable.isEmpty()) return null
        val within = usable.filter { it.height <= quality }.ifEmpty { listOf(usable.minBy { it.height }) }
        val h = within.maxOf { it.height }
        val atH = within.filter { it.height == h }
        // H.264 plays everywhere; VP9 (1440p/4K) only where the phone decodes it; AV1 last
        fun rank(s: VideoStream) = when {
            s.codec?.startsWith("avc1") == true -> 0
            s.codec?.startsWith("vp9") == true || s.codec?.startsWith("vp09") == true -> 1
            else -> 2
        }
        return atH.sortedWith(compareBy({ rank(it) }, { -it.bitrate })).first()
    }

    private fun toVideo(s: StreamInfoItem): Video? {
        val id = Regex("(?:[?&]v=|/shorts/)([\\w-]{11})").find(s.url ?: return null)?.groupValues?.get(1) ?: return null
        val live = s.streamType == StreamType.LIVE_STREAM || s.streamType == StreamType.AUDIO_LIVE_STREAM
        return Video(
            id = id,
            title = s.name.orEmpty(),
            channel = s.uploaderName.orEmpty(),
            duration = s.duration.coerceAtLeast(0),
            views = s.viewCount.takeIf { it >= 0 },
            verified = s.isUploaderVerified,
            live = live,
        )
    }

    private fun init() {
        if (initDone) return
        synchronized(DeviceSource::class.java) {
            if (!initDone) {
                NewPipe.init(OkDownloader(), Localization("en", "US"), ContentCountry("US"))
                initDone = true
            }
        }
    }

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: ApiError) {
        throw e
    } catch (e: ContentNotAvailableException) {
        throw ApiError(e.message ?: "This video isn't available.")
    } catch (e: ReCaptchaException) {
        throw ApiError("YouTube is asking for a captcha from this network. Try again later or switch networks.")
    } catch (e: IOException) {
        throw ApiError("Can't reach YouTube. Check your internet connection.")
    } catch (e: ExtractionException) {
        throw ApiError("Couldn't read that from YouTube (${e.message ?: e.javaClass.simpleName}). The app may need an update.")
    }

    companion object {
        @Volatile private var initDone = false
    }
}

/** NewPipeExtractor does its HTTP through this. */
private class OkDownloader : Downloader() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
        request.headers().forEach { (k, values) -> b.removeHeader(k); values.forEach { v -> b.addHeader(k, v) } }
        if (request.headers().keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", USER_AGENT)
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube rate limit", r.request.url.toString())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
