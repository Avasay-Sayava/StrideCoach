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
    private var ready = false

    fun configureAmbientCache(ctx: Context) {
        if (ready) return
        ready = true
        runCatching {
            val mgr = OfflineManager.getInstance(ctx.applicationContext)
            mgr.setMaximumAmbientCacheSize(200L * 1024L * 1024L, null)
            purgeOffline(mgr)
        }
    }

    fun preloadAround(ctx: Context, lat: Double, lon: Double, style: String) {
        if (lat !in -85.0..85.0 || lon !in -179.9..179.9) return
        configureAmbientCache(ctx)
    }

    private fun purgeOffline(mgr: OfflineManager) {
        mgr.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(regions: Array<OfflineRegion>?) {
                regions?.forEach(::dropRegion)
            }

            override fun onError(err: String) = Unit
        })
    }

    private fun dropRegion(reg: OfflineRegion) {
        runCatching {
            reg.setDownloadState(OfflineRegion.STATE_INACTIVE)
            reg.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = Unit
                override fun onError(err: String) = Unit
            })
        }
    }
}
