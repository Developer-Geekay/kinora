package app.kinora.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import app.kinora.data.Store
import app.kinora.data.Video

/**
 * What a car (Android Auto, Android Automotive) or any other media browser sees when it opens Kinora:
 *   Kinora
 *     Recently watched        your history, newest first
 *     Playlists               one folder per playlist
 * Items here have no URL on purpose: when the car asks to play one, [PlaybackService] looks the video up and builds an
 * audio-only item (video isn't shown in a car).
 */
@UnstableApi
object LibraryTree {
    const val ROOT = "root"
    const val HISTORY = "history"
    const val PLAYLISTS = "playlists"
    private const val PLAYLIST_PREFIX = "pl:"
    private const val MAX_ITEMS = 50

    fun root(): MediaItem = folder(ROOT, "Kinora")

    /** The children of [parentId], or null if there is no such folder. */
    fun children(store: Store, parentId: String): List<MediaItem>? = when {
        parentId == ROOT -> listOf(folder(HISTORY, "Recently watched"), folder(PLAYLISTS, "Playlists"))
        parentId == HISTORY -> store.history.take(MAX_ITEMS).map { video(it.video) }
        parentId == PLAYLISTS -> store.playlists.map { folder(PLAYLIST_PREFIX + it.id, it.name) }
        parentId.startsWith(PLAYLIST_PREFIX) ->
            store.playlists.firstOrNull { it.id == parentId.removePrefix(PLAYLIST_PREFIX) }?.items?.take(MAX_ITEMS)?.map(::video)
        else -> null
    }

    /** One entry by id (a folder or a video), for browsers that ask for it directly. */
    fun item(store: Store, id: String): MediaItem? = when (id) {
        ROOT -> root()
        HISTORY -> folder(HISTORY, "Recently watched")
        PLAYLISTS -> folder(PLAYLISTS, "Playlists")
        else -> if (id.startsWith(PLAYLIST_PREFIX)) {
            store.playlists.firstOrNull { it.id == id.removePrefix(PLAYLIST_PREFIX) }?.let { folder(id, it.name) }
        } else findVideo(store, id)?.let(::video)
    }

    /** The video we know under this id (history first, then playlists). */
    fun findVideo(store: Store, id: String): Video? =
        store.history.firstOrNull { it.video.id == id }?.video
            ?: store.playlists.firstNotNullOfOrNull { p -> p.items.firstOrNull { it.id == id } }

    private fun folder(id: String, title: String) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build(),
        )
        .build()

    private fun video(v: Video) = MediaItem.Builder()
        .setMediaId(v.id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(v.title)
                .setArtist(v.channel)
                .setArtworkUri(Uri.parse(v.thumb))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .setExtras(Bundle().apply { putLong(Streams.DURATION_MS, v.duration * 1000) })
                .build(),
        )
        .build()
}
