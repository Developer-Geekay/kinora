package app.kinora.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.kinora.TubeApp
import app.kinora.data.ApiError
import app.kinora.data.Video
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the screens see of playback: the queue, the position, play/pause and so on, as Compose state.
 * The player itself lives in [PlaybackService]; this talks to it through a MediaController.
 */
@UnstableApi
/** One audio or subtitle track the player found in the file. */
data class TrackChoice(
    val label: String,
    val selected: Boolean,
    val supported: Boolean,
    internal val group: androidx.media3.common.TrackGroup,
    internal val index: Int,
)

@UnstableApi
class Playback(private val app: TubeApp) {
    private var controller: MediaController? = null
    private val waiting = mutableListOf<(MediaController) -> Unit>()
    private var controllerFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var ticker: kotlinx.coroutines.Job? = null
    private var released = false
    private var requestedAt = 0L // when the last "play this" was asked, for the timing log
    private var queueBatch = 0 // bumped each time a new queue is started, so a slow background lookup can tell it is stale
    private var lastPlay: Pair<List<Video>, Int>? = null
    private var seekTarget = -1L // where the user just sought to, until the player has actually got there
    private var seekAt = 0L

    var player: Player? by mutableStateOf(null); private set
    var queue by mutableStateOf<List<Video>>(emptyList()); private set
    var index by mutableIntStateOf(-1); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0); private set
    var durationMs by mutableLongStateOf(0); private set
    var bufferedMs by mutableLongStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null); private set
    var videoPortrait by mutableStateOf(false); private set // the picture is taller than wide (a Short, a phone recording)
    var starting by mutableStateOf(false); private set // between tapping a video and the first frame
    var live by mutableStateOf(false); private set // the current item is a live stream: no fixed length
    var audioTracks by mutableStateOf<List<TrackChoice>>(emptyList()); private set
    var textTracks by mutableStateOf<List<TrackChoice>>(emptyList()); private set
    var subtitlesOn by mutableStateOf(false); private set // a subtitle track is selected (and not switched off)

    val current: Video? get() = queue.getOrNull(index)
    /** The loaded item is audio only (audio stream mode, or a music file). Not Compose state: read it when something happens. */
    val audioPlaying: Boolean get() = controller?.currentMediaItem?.mediaMetadata?.extras?.getString(Streams.MODE) == "audio"
    val hasMedia: Boolean get() = current != null

    private var loggedId: String? = null
    private var lastSaved = 0L

    fun connect(context: Context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (released) return@addListener
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            player = c
            c.addListener(listener)
            syncAll(c)
            waiting.forEach { it(c) }
            waiting.clear()
        }, MoreExecutors.directExecutor())

        ticker = app.scope.launch { // position ticker
            while (true) {
                val c = controller
                if (c != null && c.mediaItemCount > 0) {
                    val raw = c.currentPosition.coerceAtLeast(0)
                    if (seekTarget >= 0) {
                        // While a seek swaps streams the player reports the old stream's time for a moment; keep showing the target.
                        val settled = c.playbackState == Player.STATE_READY && kotlin.math.abs(raw - seekTarget) <= 15_000
                        if (settled || System.currentTimeMillis() - seekAt > 10_000) seekTarget = -1
                    }
                    if (seekTarget < 0) positionMs = raw
                    durationMs = if (live) 0 else knownDuration(c)
                    bufferedMs = c.bufferedPosition.coerceAtLeast(0)
                    if (c.isPlaying) saveResume(c)
                }
                delay(500)
            }
        }
    }

    /** Call from the Activity's onDestroy: stops the ticker and unbinds this controller from the service. */
    fun release() {
        released = true
        ticker?.cancel()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        controllerFuture = null
        player = null
        waiting.clear()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else waiting.add(block)
    }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            videoPortrait = videoSize.width > 0 && videoSize.height > videoSize.width
        }

        override fun onEvents(player: Player, events: Player.Events) {
            syncAll(player)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying && requestedAt > 0) { // how long "tap a video" took to become sound and picture
                android.util.Log.i("LT-time", "tap to playing: ${android.os.SystemClock.elapsedRealtime() - requestedAt} ms (${if (app.api.onDevice) "phone" else "PC"})")
                requestedAt = 0
            }
            if (isPlaying) { starting = false; error = null; recordHistoryOnce() }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) current?.let { app.store.markDone(it.id); app.store.savePosition(it.id, 0, it.duration) }
        }

        override fun onPlayerError(e: PlaybackException) {
            starting = false
            error = "That video didn't play. ${e.errorCodeName.replace('_', ' ').lowercase()}"
        }
    }

    private fun syncAll(p: Player) {
        playing = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        speed = p.playbackParameters.speed
        val items = (0 until p.mediaItemCount).map { Streams.video(p.getMediaItemAt(it)) }
        if (items != queue) queue = items
        index = if (items.isEmpty()) -1 else p.currentMediaItemIndex
        if (buffering) starting = false
        live = p.currentMediaItem?.mediaMetadata?.extras?.getBoolean(Streams.LIVE, false) == true
        durationMs = if (live) 0 else knownDuration(p)
        refreshTracks(p)
    }

    /** The player's duration, or the video's own length while the player is between streams (else the seek bar's range collapses to 0). */
    private fun knownDuration(p: Player): Long =
        p.duration.takeIf { it > 0 } ?: ((current?.duration ?: 0L) * 1000)

    // ----- audio and subtitle tracks (what is inside the file: languages, 5.1, embedded or .srt next to it) -----

    private fun refreshTracks(p: Player) {
        val tracks = p.currentTracks
        val textDisabled = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes
        val audio = choices(tracks, C.TRACK_TYPE_AUDIO)
        val text = choices(tracks, C.TRACK_TYPE_TEXT).map { if (textDisabled) it.copy(selected = false) else it }
        if (audio != audioTracks) audioTracks = audio
        if (text != textTracks) textTracks = text
        val on = text.any { it.selected }
        if (on != subtitlesOn) subtitlesOn = on
    }

    private fun choices(tracks: Tracks, type: Int): List<TrackChoice> =
        tracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).map { i ->
                TrackChoice(trackLabel(g.getTrackFormat(i), type), g.isTrackSelected(i), g.isTrackSupported(i), g.mediaTrackGroup, i)
            }
        }.let { list ->
            // number identical labels ("English", "English") so they can be told apart
            val seen = HashMap<String, Int>()
            val counts = list.groupingBy { it.label }.eachCount()
            list.map { c -> if (counts.getValue(c.label) > 1) c.copy(label = "${c.label} ${seen.merge(c.label, 1, Int::plus)}") else c }
        }

    private fun trackLabel(f: Format, type: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).replaceFirstChar { ch -> ch.uppercase() } }
        // a language name reads best; a file's own track title is often a long release tag ("www.site.com - [DD+ 5.1 - 192Kbps]"), so trim it
        val tidy = f.label?.trim()?.replace(Regex("^www\\.[^ ]+\\s*-\\s*"), "")?.replace(Regex("[\\[\\]]"), "")?.take(26)?.takeIf { it.isNotBlank() }
        val base = lang ?: tidy ?: "Track"
        val extra = buildList {
            if (type == C.TRACK_TYPE_AUDIO) {
                when (f.channelCount) { 1 -> add("mono"); 2 -> add("stereo"); 6 -> add("5.1"); 8 -> add("7.1"); in 3..12 -> add("${f.channelCount} ch") }
                codecName(f.sampleMimeType)?.let(::add)
            } else {
                codecName(f.sampleMimeType)?.let(::add)
            }
            if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) add("forced")
            if (f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 && type == C.TRACK_TYPE_TEXT) add("default")
        }
        return (listOf(base) + extra).joinToString(" · ")
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC-3"; MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"; MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"; MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"; MimeTypes.AUDIO_MPEG -> "MP3"; MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"; MimeTypes.TEXT_VTT -> "VTT"; MimeTypes.TEXT_SSA -> "ASS"; MimeTypes.APPLICATION_PGS -> "PGS (picture)"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB (picture)"; MimeTypes.APPLICATION_TX3G -> "3GPP"; MimeTypes.APPLICATION_MP4VTT -> "VTT"
        else -> null
    }

    fun selectAudio(c: TrackChoice) = withController { ctrl ->
        if (!c.supported) return@withController
        ctrl.trackSelectionParameters = ctrl.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(c.group, listOf(c.index))).build()
    }

    /** [c] = a subtitle track, or null = subtitles off. */
    fun selectSubtitles(c: TrackChoice?) = withController { ctrl ->
        val b = ctrl.trackSelectionParameters.buildUpon()
        if (c == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else if (c.supported) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(c.group, listOf(c.index)))
        ctrl.trackSelectionParameters = b.build()
    }

    private fun recordHistoryOnce() {
        val v = current ?: return
        if (loggedId == v.id) return
        loggedId = v.id
        app.store.recordHistory(v)
    }

    private fun saveResume(c: MediaController, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSaved < 5000) return
        lastSaved = now
        val v = Streams.video(c.currentMediaItem ?: return)
        val len = if (v.duration > 0) v.duration else (c.duration / 1000).coerceAtLeast(0)
        if (v.duration == 0L && len > 0) app.store.setDuration(v.id, len)
        app.store.savePosition(v.id, (c.currentPosition / 1000).toInt(), len)
    }

    // ----- commands the screens use -----

    /** Play [videos] starting at [start], resuming where you left off if you'd watched part of it. */
    fun playQueue(videos: List<Video>, start: Int) {
        if (videos.isEmpty()) return
        starting = true; error = null
        lastPlay = videos to start
        requestedAt = android.os.SystemClock.elapsedRealtime()
        val batch = ++queueBatch
        app.scope.launch {
            val mode = app.stream
            val q = app.quality
            val first = videos[start]
            val resume = app.store.resumeSec(first.id)
            val resuming = resume > 15 && (first.duration == 0L || resume < first.duration - 20)
            // audio, and streams looked up on the phone, are seeked by the player itself; only the PC's video pipe needs a keyframe
            val native = mode == "audio" || app.api.onDevice || first.isLocal
            var startAt = 0.0
            if (!native && resuming) {
                // video can only begin on a keyframe: ask the server where that is
                startAt = runCatching { app.api.start(first.id, q, resume.toDouble()) }.getOrDefault(0.0)
            }
            val firstItem = try {
                app.api.item(first, mode, q, startAt)
            } catch (e: ApiError) {
                if (batch == queueBatch) { starting = false; error = e.message }
                return@launch
            }
            if (batch != queueBatch) return@launch // something else was started meanwhile
            loggedId = null
            withController { c ->
                c.setMediaItems(listOf(firstItem), 0, if (native && resuming) resume * 1000L else 0L)
                c.prepare()
                c.play()
            }
            // The rest of the queue is looked up one by one in the background, so the first video starts straight away.
            var before = 0
            for ((i, v) in videos.withIndex()) {
                if (i == start) continue
                val item = runCatching { app.api.item(v, mode, q) }.getOrNull() ?: continue // skip what can't be played
                if (batch != queueBatch) return@launch
                val c = controller ?: return@launch
                if (i < start) c.addMediaItem(before++.coerceAtMost(c.mediaItemCount), item) else c.addMediaItem(item)
            }
        }
    }

    fun playOne(v: Video) = playQueue(listOf(v), 0)

    fun addToQueue(v: Video) {
        if (!hasMedia) return playOne(v)
        app.scope.launch {
            val item = try { app.api.item(v, app.stream, app.quality) } catch (e: ApiError) { error = e.message; return@launch }
            withController { it.addMediaItem(item) }
        }
    }

    fun playNext(v: Video) {
        if (!hasMedia) return playOne(v)
        app.scope.launch {
            val item = try { app.api.item(v, app.stream, app.quality) } catch (e: ApiError) { error = e.message; return@launch }
            withController { it.addMediaItem((it.currentMediaItemIndex + 1).coerceAtMost(it.mediaItemCount), item) }
        }
    }

    fun removeAt(i: Int) = withController { if (i in 0 until it.mediaItemCount) it.removeMediaItem(i) }
    fun move(from: Int, to: Int) = withController { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) }
    fun skipTo(i: Int) = withController { it.seekTo(i, 0); it.play() }
    fun pause() = withController { it.pause() }
    /** Repeat the current item (the Shorts feed loops a clip until you swipe). */
    fun setLoop(on: Boolean) = withController { it.repeatMode = if (on) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }

    fun toggle() = withController { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    fun next() = withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previous() = withController { if (it.currentPosition > 5000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() }
    fun seek(ms: Long) = withController { positionMs = ms; seekTarget = ms; seekAt = System.currentTimeMillis(); it.seekTo(ms) }
    fun changeSpeed(s: Float) = withController { it.setPlaybackSpeed(s) }

    /** Leaving the app on a TV: remember where you were, then stop (a TV has no lock screen or notification to control it from). */
    fun stopForExit() = withController { c ->
        if (c.mediaItemCount > 0) saveResume(c, force = true)
        queueBatch++
        c.stop(); c.clearMediaItems()
        queue = emptyList(); index = -1; starting = false; error = null
    }

    fun stop() = withController {
        queueBatch++ // cancel any queue still being looked up
        it.stop(); it.clearMediaItems()
        queue = emptyList(); index = -1; starting = false; error = null
    }

    /** After changing stream type / quality / autoplay, tell the service to rebuild what it plays. */
    fun reload(streamsChanged: Boolean) = withController {
        it.sendCustomCommand(
            SessionCommand(PlaybackService.RELOAD, Bundle.EMPTY),
            Bundle().apply { putBoolean(PlaybackService.ARG_STREAMS, streamsChanged) },
        )
    }

    fun retry() {
        val c = controller
        val item: MediaItem? = c?.currentMediaItem
        if (c == null || item == null) { // the lookup itself failed, so there is nothing playing yet: start over
            lastPlay?.let { playQueue(it.first, it.second) }
            return
        }
        val here = c.currentPosition
        val idx = c.currentMediaItemIndex
        error = null; starting = true
        app.scope.launch {
            // look the stream up again: its URL may have expired
            val fresh = try { app.api.item(Streams.video(item), app.stream, app.quality, fresh = true) } catch (e: ApiError) { starting = false; error = e.message; return@launch }
            c.replaceMediaItem(idx, fresh)
            if (app.stream == "audio" || app.api.onDevice || Streams.video(item).isLocal) c.seekTo(here)
            c.prepare(); c.play()
        }
    }
}
