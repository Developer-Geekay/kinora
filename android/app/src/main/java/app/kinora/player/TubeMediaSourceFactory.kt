package app.kinora.player

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Builds what the player loads for each item.
 *  - Items looked up on the phone ([Streams.NATIVE]) carry YouTube's own URLs: a video-only URL plus a separate
 *    audio URL, merged here. They use [deviceHttp], which must NOT carry the PC's PIN header.
 *  - Items from the PC server stream a single muxed pipe and use [pcHttp] (which adds the PIN).
 */
@UnstableApi
class TubeMediaSourceFactory(
    private val pcHttp: DataSource.Factory, private val deviceHttp: DataSource.Factory, smbData: DataSource.Factory,
) : MediaSource.Factory {
    private val pc = DefaultMediaSourceFactory(pcHttp)
    /** Files on network shares (smb://): the usual factory, which also loads their subtitle files, reading through [smbData]. */
    private val smb = DefaultMediaSourceFactory(smbData)
    private val device = ProgressiveMediaSource.Factory(deviceHttp)
    private val live = DefaultMediaSourceFactory(deviceHttp) // HLS (live streams) from YouTube's servers, no PIN header

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        pc.setDrmSessionManagerProvider(provider); device.setDrmSessionManagerProvider(provider); smb.setDrmSessionManagerProvider(provider); live.setDrmSessionManagerProvider(provider); return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        pc.setLoadErrorHandlingPolicy(policy); device.setLoadErrorHandlingPolicy(policy); smb.setLoadErrorHandlingPolicy(policy); live.setLoadErrorHandlingPolicy(policy); return this
    }

    override fun getSupportedTypes(): IntArray = pc.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        if (mediaItem.localConfiguration?.uri?.scheme == "smb") return smb.createMediaSource(mediaItem)
        val extras = mediaItem.mediaMetadata.extras
        if (extras?.getBoolean(Streams.LIVE, false) == true) return live.createMediaSource(mediaItem)
        if (extras?.getBoolean(Streams.NATIVE, false) != true) return pc.createMediaSource(mediaItem)
        val main = device.createMediaSource(mediaItem)
        val audioUrl = extras.getString(Streams.AUDIO_URL)
        if (audioUrl.isNullOrEmpty()) return main
        val audio = device.createMediaSource(MediaItem.fromUri(audioUrl))
        return MergingMediaSource(main, audio)
    }
}
