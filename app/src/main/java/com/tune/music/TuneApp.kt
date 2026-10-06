package com.tune.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.request.ErrorResult
import java.io.File
import com.tune.music.data.LoudnessStore
import com.tune.music.data.Net
import com.tune.music.data.OfflineException
import com.tune.music.ui.components.ArtFetcher

class TuneApp : Application(), ImageLoaderFactory {
    /** Measured song loudness, shared by the player and the settings' "measure all songs". */
    val loudness by lazy { LoudnessStore(File(filesDir, "loudness.json")) }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ArtFetcher.Factory(this@TuneApp))
            add(ArtFetcher.Keyer)
            // Remote covers obey the network kill switch too.
            add(Interceptor { chain ->
                val data = chain.request.data
                val remote = data.toString().let { it.startsWith("http://") || it.startsWith("https://") }
                if (Net.blocked && remote) ErrorResult(null, chain.request, OfflineException()) else chain.proceed(chain.request)
            })
        }
        .crossfade(150)
        .build()
}
