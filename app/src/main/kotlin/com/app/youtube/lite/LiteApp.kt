package com.app.youtube.lite

import android.app.Application
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.net.Http
import com.app.youtube.lite.core.perf.MemoryTrimmer
import com.app.youtube.lite.core.util.L
import java.io.File

/**
 * The `Application`: the app's first and last object.
 *
 * It does exactly three things, because everything it does here delays the first frame the user
 * sees:
 *
 * 1. **Installs the object graph and warms up the session** on a background dispatcher. The
 *    encrypted-preferences read is the only thing that has to happen early, and it happens off the
 *    main thread, so the home feed starts loading immediately and the account state simply
 *    appears a moment later.
 * 2. **Owns the single Coil `ImageLoader`** (through [ImageLoaderFactory]). One loader means one
 *    memory cache, one disk cache and one HTTP stack for every thumbnail and artwork in the app —
 *    and letting Coil use this app's shared `OkHttpClient` means thumbnails reuse the same HTTP/2
 *    connection the feed JSON arrived on instead of opening a second socket to YouTube's CDN.
 * 3. **Reacts to memory pressure**, by handing `onTrimMemory` to [MemoryTrimmer], which drops
 *    decoded bitmaps first and sockets second. The platform tells an app exactly how urgently it
 *    needs memory back; a video app that ignores that gets killed mid-video.
 */
class LiteApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppGraph.install(this).warmUp()
        L.i("LiteApp") { "started; lowRam=${MemoryTrimmer.isLowRamDevice(this)}" }
    }

    /**
     * Hardware bitmaps by default: a decoded thumbnail never has to be readable by app code (we
     * never touch its pixels), so keeping it in the GPU's memory instead of the Java heap saves
     * roughly one full ARGB buffer per cached image — on a 480 px-wide feed thumbnail that is
     * about 0.7 MB each, and the Java heap is the budget the LMK actually kills over.
     *
     * Both caches are sized, not left at defaults: 15% of the app's heap for bitmaps and 64 MB on
     * disk for thumbnails, which is enough for a long scrolling session and small enough that the
     * app is never the reason a user runs out of storage.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .callFactory { Http.client }
        .crossfade(false)
        .allowHardware(true)
        .bitmapConfig(Bitmap.Config.HARDWARE)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(IMAGE_CACHE_HEAP_PERCENT)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(File(cacheDir, THUMBNAIL_CACHE_DIR))
                .maxSizeBytes(THUMBNAIL_CACHE_BYTES)
                .build()
        }
        .build()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        MemoryTrimmer.onTrimMemory(level, Coil.imageLoader(this))
    }

    override fun onLowMemory() {
        super.onLowMemory()
        MemoryTrimmer.clearImages(Coil.imageLoader(this))
    }

    private companion object {
        const val IMAGE_CACHE_HEAP_PERCENT = 0.15
        const val THUMBNAIL_CACHE_DIR = "thumbnails"
        const val THUMBNAIL_CACHE_BYTES = 64L * 1024L * 1024L
    }
}
