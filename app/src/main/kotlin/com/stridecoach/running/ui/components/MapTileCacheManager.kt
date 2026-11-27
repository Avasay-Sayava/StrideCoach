package com.stridecoach.running.ui.components

import android.content.Context
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion

/**
 * Configures MapLibre's 200 MB ambient vector tile cache and purges any stale
 * background OfflineRegion downloads so mbgl-offline.db stays lock-free.
 */
object MapTileCacheManager {
    @Volatile
    private var ambientConfigured = false

    fun configureAmbientCache(ctx: Context) {
        if (ambientConfigured) return
        ambientConfigured = true
        val appCtx = ctx.applicationContext
        runCatching {
            val mgr = OfflineManager.getInstance(appCtx)
            mgr.setMaximumAmbientCacheSize(200L * 1024L * 1024L, null)
            mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    offlineRegions?.forEach { region ->
                        runCatching {
                            region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                                override fun onDelete() = Unit
                                override fun onError(error: String) = Unit
                            })
                        }
                    }
                }

                override fun onError(error: String) = Unit
            })
        }
    }

    fun preloadAround(ctx: Context, lat: Double, lon: Double, styleUri: String) {
        if (lat !in -85.0..85.0 || lon !in -179.9..179.9) return
        configureAmbientCache(ctx)
    }
}
