package app.localtube.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.localtube.data.Video

/** Builds the player's media items. Everything the player needs later lives in the item's extras. */
object Streams {
    const val DURATION_MS = "durationMs" // total length of the video
    const val START_AT_MS = "startAtMs"  // where in the video this stream begins (after a seek)
    const val MODE = "mode"              // "video" | "audio"

    /** [startAtSec] > 0 asks the server to begin the video stream there (use the keyframe time it gave back). */
    fun item(base: String, v: Video, mode: String, quality: Int, startAtSec: Double = 0.0): MediaItem {
        val url = "$base/stream/${v.id}?mode=$mode&q=$quality" + if (startAtSec > 0) "&t=$startAtSec" else ""
        val extras = Bundle().apply {
            putLong(DURATION_MS, v.duration * 1000)
            putLong(START_AT_MS, (startAtSec * 1000).toLong())
            putString(MODE, mode)
            putString("channel", v.channel)
        }
        return MediaItem.Builder()
            .setMediaId(v.id)
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(v.title)
                    .setArtist(v.channel)
                    .setArtworkUri(Uri.parse(v.thumb))
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    const val AUDIO_URL = "audioUrl"     // device source: the separate audio stream to merge with the video URL
    const val NATIVE = "native"          // device source: real HTTP ranges, so the player seeks the stream itself

    /** An item whose URLs are YouTube's own (looked up on the phone). Video + audio are merged by [TubeMediaSourceFactory]. */
    fun deviceItem(v: Video, mode: String, mainUrl: String, audioUrl: String?): MediaItem {
        val extras = Bundle().apply {
            putLong(DURATION_MS, v.duration * 1000)
            putLong(START_AT_MS, 0L)
            putString(MODE, mode)
            putString("channel", v.channel)
            putBoolean(NATIVE, true)
            if (mode == "video" && audioUrl != null) putString(AUDIO_URL, audioUrl)
        }
        return MediaItem.Builder()
            .setMediaId(v.id)
            .setUri(mainUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(v.title)
                    .setArtist(v.channel)
                    .setArtworkUri(Uri.parse(v.thumb))
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    const val LIVE = "live" // a live stream (HLS): no length, no fixed end

    /** A live stream: HLS playlist, played by ExoPlayer's HLS source. No duration; the player shows LIVE. */
    fun liveItem(v: Video, hlsUrl: String): MediaItem {
        val extras = Bundle().apply {
            putLong(DURATION_MS, 0L)
            putLong(START_AT_MS, 0L)
            putString(MODE, "video")
            putString("channel", v.channel)
            putBoolean(NATIVE, true)
            putBoolean(LIVE, true)
        }
        return MediaItem.Builder()
            .setMediaId(v.id)
            .setUri(hlsUrl)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(v.title).setArtist(v.channel).setArtworkUri(Uri.parse(v.thumb)).setExtras(extras).build(),
            )
            .build()
    }

    fun video(item: MediaItem): Video = Video(
        id = item.mediaId,
        title = item.mediaMetadata.title?.toString() ?: item.mediaId,
        channel = item.mediaMetadata.artist?.toString().orEmpty(),
        duration = (item.mediaMetadata.extras?.getLong(DURATION_MS) ?: 0L) / 1000,
    )

    fun rebuild(old: MediaItem, base: String, mode: String, quality: Int, startAtSec: Double = 0.0) =
        item(base, video(old), mode, quality, startAtSec)
}
