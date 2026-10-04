package com.tune.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tune.music.ui.components.ArtFetcher

class TuneApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(ArtFetcher.Factory(this@TuneApp))
            add(ArtFetcher.Keyer)
        }
        .crossfade(150)
        .build()
}
