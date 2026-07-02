package dev.phucngu.simpletype.gesture

import kotlin.math.hypot

/** A point on the touch surface, in pixels. Pure Kotlin so the decoder is JVM-testable. */
data class GesturePoint(val x: Float, val y: Float) {
    fun distanceTo(other: GesturePoint): Float = hypot(x - other.x, y - other.y)
}

/**
 * Letter-key geometry of the current keyboard: the center of every letter key plus the
 * average key size, used to normalize decoder distances.
 */
class KeyGeometry(
    val centers: Map<Char, GesturePoint>,
    val keyWidth: Float,
    val keyHeight: Float,
) {
    fun centerOf(c: Char): GesturePoint? = centers[c]

    val isEmpty: Boolean get() = centers.isEmpty() || keyWidth <= 0f
}

internal fun pathLength(points: List<GesturePoint>): Float {
    var total = 0f
    for (i in 1 until points.size) total += points[i].distanceTo(points[i - 1])
    return total
}

/** Resamples [points] into [count] equidistant points along the polyline. */
internal fun resample(points: List<GesturePoint>, count: Int): List<GesturePoint> {
    require(count >= 2)
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return List(count) { points[0] }

    val total = pathLength(points)
    if (total <= 0f) return List(count) { points[0] }

    val interval = total / (count - 1)
    val result = ArrayList<GesturePoint>(count)
    result.add(points.first())
    var accumulated = 0f
    var i = 1
    var prev = points[0]
    while (i < points.size && result.size < count - 1) {
        val current = points[i]
        val segment = prev.distanceTo(current)
        if (segment <= 0f) {
            i++
            prev = current
            continue
        }
        if (accumulated + segment >= interval) {
            val t = (interval - accumulated) / segment
            val nx = prev.x + t * (current.x - prev.x)
            val ny = prev.y + t * (current.y - prev.y)
            val inserted = GesturePoint(nx, ny)
            result.add(inserted)
            prev = inserted
            accumulated = 0f
        } else {
            accumulated += segment
            prev = current
            i++
        }
    }
    while (result.size < count) result.add(points.last())
    return result
}

/** Translates to the centroid and scales the longest bounding-box side to 1. */
internal fun normalizeShape(points: List<GesturePoint>): List<GesturePoint> {
    if (points.isEmpty()) return points
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
    var cx = 0f; var cy = 0f
    for (p in points) {
        minX = minOf(minX, p.x); maxX = maxOf(maxX, p.x)
        minY = minOf(minY, p.y); maxY = maxOf(maxY, p.y)
        cx += p.x; cy += p.y
    }
    cx /= points.size; cy /= points.size
    val scale = maxOf(maxX - minX, maxY - minY)
    if (scale <= 0f) return points.map { GesturePoint(0f, 0f) }
    return points.map { GesturePoint((it.x - cx) / scale, (it.y - cy) / scale) }
}
