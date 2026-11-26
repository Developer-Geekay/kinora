package app.kinora.player

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.kinora.tubeApp
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.launch
import app.kinora.data.Video

/**
 * Keeps playback alive with the screen off, and gives the notification / lock screen / headset buttons.
 * It is a media *library* service so cars (Android Auto / Automotive) can browse history and playlists too: see [LibraryTree].
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var tube: TubePlayer? = null

    override fun onCreate() {
        super.onCreate()
        val app = tubeApp

        // The PIN is read on every request, so changing the server in Settings takes effect at once.
        val pcHttp = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(60_000) // a video's first lookup on the PC can take a while
                .setDefaultRequestProperties(mapOf("X-LT-Key" to app.store.pin))
                .createDataSource()
        }
        // Straight to YouTube's servers: never send the PC's PIN there. Read in windows, because a plain download is throttled.
        val ytHttp = DefaultHttpDataSource.Factory()
            .setUserAgent(DEVICE_USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)
        val deviceHttp = DataSource.Factory { YtChunkedDataSource(ytHttp, DEVICE_USER_AGENT) }

        val smbData = DataSource.Factory { SmbDataSource(app) }

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(TubeMediaSourceFactory(pcHttp, deviceHttp, smbData))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).setUsage(C.USAGE_MEDIA).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val player = TubePlayer(exo, app)
        tube = player
        session = MediaLibrarySession.Builder(this, player, Callback()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // Phone: closing the app or locking the screen keeps the sound going (stop it from the notification or the lock screen).
    // Only when nothing is playing does closing the app end the service. A TV has no notification to stop it from, so it stops.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (tubeApp.useTv || p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            p?.run { pause(); stop(); clearMediaItems() }
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(RELOAD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == RELOAD) {
                tube?.reloadAll(rebuildStreams = args.getBoolean(ARG_STREAMS, false))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        // ----- browsing (cars) -----

        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(LibraryTree.root(), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = LibraryTree.children(tubeApp.store, parentId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            val from = (page * pageSize).coerceAtMost(all.size)
            val slice = all.subList(from, (from + pageSize).coerceAtMost(all.size))
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(slice), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = LibraryTree.item(tubeApp.store, mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /**
         * Items the app itself sends already carry their URLs and pass straight through. Items a car sends (just an id from
         * the browse tree) are looked up now and played as audio only.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) return super.onAddMediaItems(mediaSession, controller, mediaItems)
            val app = tubeApp
            val result = SettableFuture.create<MutableList<MediaItem>>()
            app.scope.launch {
                try {
                    result.set(mediaItems.map { item ->
                        if (item.localConfiguration != null) item
                        else {
                            val known: Video = LibraryTree.findVideo(app.store, item.mediaId) ?: Streams.video(item)
                            app.api.item(known, "audio", app.quality)
                        }
                    }.toMutableList())
                } catch (e: Exception) {
                    result.setException(e)
                }
            }
            return result
        }
    }

    companion object {
        const val DEVICE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
        const val RELOAD = "kinora.reload"
        const val ARG_STREAMS = "streams"
    }
}
