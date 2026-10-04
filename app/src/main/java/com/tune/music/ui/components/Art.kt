package com.tune.music.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import com.tune.music.data.albumArtUri
import com.tune.music.ui.theme.Metro
import java.io.IOException

/** Bumped by the app whenever the library reloads, to refresh cached art. */
val LocalArtVersion = compositionLocalOf { 0L }

data class ArtRequest(val albumId: Long, val songUri: Uri?, val version: Long)

/** Album art from MediaStore, falling back to the picture embedded in a song. */
class ArtFetcher(private val data: ArtRequest, private val options: Options, private val context: Context) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val px = (options.size.width as? Dimension.Pixels)?.px?.coerceIn(64, 1024) ?: 512
        val bmp = fromMediaStore(px) ?: fromEmbedded(px) ?: throw IOException("no art")
        return DrawableResult(bmp.toDrawable(context.resources), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun fromMediaStore(px: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, data.albumId)
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            context.contentResolver.openInputStream(albumArtUri(data.albumId))?.use { BitmapFactory.decodeStream(it) }
        }
    }.getOrNull()

    private fun fromEmbedded(px: Int): Bitmap? {
        val uri = data.songUri ?: return null
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val bytes = r.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= px) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<ArtRequest> {
        override fun create(data: ArtRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtFetcher(data, options, context)
    }

    object Keyer : coil.key.Keyer<ArtRequest> {
        override fun key(data: ArtRequest, options: Options) = "art:${data.albumId}:${data.version}"
    }
}

/** Square album art, with the Metro placeholder (accent square + note) when missing. */
@Composable
fun AlbumArt(albumId: Long, songUri: Uri?, modifier: Modifier = Modifier) {
    val version = LocalArtVersion.current
    SubcomposeAsyncImage(
        model = ArtRequest(albumId, songUri, version),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { ArtPlaceholder() },
        error = { ArtPlaceholder() },
    )
}

/** Remote image (cover art search results) with the same placeholder. */
@Composable
fun RemoteArt(url: String, modifier: Modifier = Modifier) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { ArtPlaceholder(Metro.colors.disabled) },
        error = { ArtPlaceholder(Metro.colors.disabled) },
    )
}

@Composable
fun ArtPlaceholder(color: Color = Metro.colors.accent) {
    Box(Modifier.fillMaxSize().background(color), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.fillMaxWidth(0.4f).size(48.dp))
    }
}
