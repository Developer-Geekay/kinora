package app.kinora.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.kinora.TubeApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The server sends video as one continuous stream (ffmpeg's output), which a phone can't seek in.
 * To seek, we ask the server for a new stream that starts at the target. This wrapper hides that:
 * the rest of the app, the notification and the lock screen see an ordinary seekable video with
 * the right position and length.
 *
 * Each stream knows where in the video it starts ([Streams.START_AT_MS], from the media item), so
 * position = that start + how far this stream has played.
 */
@UnstableApi
class TubePlayer(private val exo: ExoPlayer, private val app: TubeApp) : ForwardingPlayer(exo) {
    private var seekToken = 0

    init {
        applyAutoplay()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = applyAutoplay()
        })
    }

    /** Autoplay off = stop at the end of each video instead of rolling into the next. */
    fun applyAutoplay() { exo.pauseAtEndOfMediaItems = !app.autoplay }

    private fun extras() = currentMediaItem?.mediaMetadata?.extras
    private fun startAtMs() = extras()?.getLong(Streams.START_AT_MS, 0L) ?: 0L
    private fun totalMs() = extras()?.getLong(Streams.DURATION_MS, 0L) ?: 0L
    private fun isAudio() = extras()?.getString(Streams.MODE) == "audio"
    private fun isNative() = extras()?.getBoolean(Streams.NATIVE, false) == true

    override fun getCurrentPosition() = startAtMs() + super.getCurrentPosition()
    override fun getContentPosition() = startAtMs() + super.getContentPosition()
    override fun getBufferedPosition() = startAtMs() + super.getBufferedPosition()
    override fun getContentBufferedPosition() = startAtMs() + super.getContentBufferedPosition()
    override fun getDuration(): Long = totalMs().takeIf { it > 0 } ?: super.getDuration()
    override fun getContentDuration(): Long = duration

    // A video stream isn't seekable to ExoPlayer, but it is to us.
    override fun isCurrentMediaItemSeekable() = true
    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addAll(
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
        ).build()
    override fun isCommandAvailable(command: Int) = availableCommands.contains(command)

    // ForwardingPlayer hands listeners ExoPlayer's own command set, which says "can't seek" for the unseekable
    // pipe. The session caches that and then refuses seeks from controllers, so report our set instead.
    private val wrappedListeners = HashMap<Player.Listener, Player.Listener>()
    // A dynamic proxy, not Kotlin's `by`: every Player.Listener method is a Java default method, which `by`
    // does not forward, so the session would silently stop receiving playback events.
    override fun addListener(listener: Player.Listener) {
        val wrapper = java.lang.reflect.Proxy.newProxyInstance(
            Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java),
        ) { proxy, method, args ->
            try {
                when (method.name) {
                    "onAvailableCommandsChanged" -> { listener.onAvailableCommandsChanged(this@TubePlayer.availableCommands); null }
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TubePlayerListenerProxy"
                    else -> method.invoke(listener, *(args ?: emptyArray<Any?>()))
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Player.Listener
        wrappedListeners[listener] = wrapper
        super.addListener(wrapper)
    }
    override fun removeListener(listener: Player.Listener) {
        super.removeListener(wrappedListeners.remove(listener) ?: listener)
    }

    override fun seekTo(positionMs: Long) = seekInCurrent(positionMs)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (mediaItemIndex == currentMediaItemIndex && currentMediaItem != null) seekInCurrent(positionMs)
        else super.seekTo(mediaItemIndex, positionMs)
    }
    override fun seekBack() = seekInCurrent(currentPosition - seekBackIncrement)
    override fun seekForward() = seekInCurrent(currentPosition + seekForwardIncrement)

    private fun seekInCurrent(targetMs: Long) {
        val item = currentMediaItem ?: return
        val total = totalMs()
        val t = targetMs.coerceIn(0L, if (total > 1000) total - 1000 else Long.MAX_VALUE)
        // audio streams and streams looked up on the phone support real HTTP ranges: ExoPlayer seeks them natively
        if (isAudio() || isNative()) { super.seekTo(t); return }
        switchCurrent(item, t)
    }

    /** Replace the current video stream with one that starts at (the keyframe at or before) [targetMs]. */
    private fun switchCurrent(item: MediaItem, targetMs: Long, mode: String = "video") {
        val id = item.mediaId
        val token = ++seekToken
        val idx = currentMediaItemIndex
        app.scope.launch {
            val q = app.quality
            val start = if (targetMs <= 0) 0.0 else withContext(Dispatchers.IO) {
                runCatching { app.api.start(id, q, targetMs / 1000.0) }.getOrDefault(targetMs / 1000.0)
            }
            if (token != seekToken || currentMediaItem?.mediaId != id || currentMediaItemIndex != idx) return@launch // superseded
            replaceMediaItem(idx, Streams.rebuild(item, app.api.base(), mode, q, start))
        }
    }

    /** Settings changed (stream type or quality): rebuild every item, keeping the playing one at its position. */
    fun reloadAll(rebuildStreams: Boolean) {
        applyAutoplay()
        if (!rebuildStreams || mediaItemCount == 0) return
        if (app.api.onDevice) { reloadOnDevice(); return }
        val mode = app.stream
        val q = app.quality
        val base = app.api.base()
        val cur = currentMediaItemIndex
        val here = currentPosition
        for (i in 0 until mediaItemCount) {
            if (i != cur && !Streams.video(getMediaItemAt(i)).isLocal) replaceMediaItem(i, Streams.rebuild(getMediaItemAt(i), base, mode, q))
        }
        val item = currentMediaItem ?: return
        if (Streams.video(item).isLocal) return // a file on a share doesn't depend on stream type or quality
        if (mode == "audio") {
            replaceMediaItem(cur, Streams.rebuild(item, base, mode, q))
            super.seekTo(here)
        } else {
            switchCurrent(item, here, mode)
        }
    }

    /** Same, for streams looked up on the phone: look each one up again, the playing one first, and resume where it was. */
    private fun reloadOnDevice() {
        val mode = app.stream
        val q = app.quality
        val cur = currentMediaItemIndex
        val here = currentPosition
        val snapshot = (0 until mediaItemCount).map { getMediaItemAt(it) }
        app.scope.launch {
            for (i in listOf(cur) + snapshot.indices.filter { it != cur }) {
                val old = snapshot.getOrNull(i) ?: continue
                val new = runCatching { app.api.item(Streams.video(old), mode, q) }.getOrNull() ?: continue
                if (i >= mediaItemCount || getMediaItemAt(i).mediaId != old.mediaId) continue // the queue changed meanwhile
                replaceMediaItem(i, new)
                if (i == cur) super.seekTo(here)
            }
        }
    }
}
