package com.stridecoach.running.core.domain.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val a = sinLat * sinLat + cos(rLat1) * cos(rLat2) * sinLon * sinLon
        val c = 2.0 * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
        return EARTH_RADIUS_METERS * c
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val deg = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return deg.toFloat()
    }
}
