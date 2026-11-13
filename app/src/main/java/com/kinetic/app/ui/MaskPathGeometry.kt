package com.kinetic.app.ui

import android.graphics.Path
import com.kinetic.app.model.VectorPoint

/** Catmull-Rom to cubic Bezier conversion for smooth, editable mask contours. */
internal fun buildMaskContour(points: List<VectorPoint>, width: Float, height: Float, closed: Boolean): Path {
    val path = Path()
    if (points.isEmpty()) return path
    fun px(i: Int) = points[i].x * width
    fun py(i: Int) = points[i].y * height
    path.moveTo(px(0), py(0))
    if (points.size == 1) return path
    val segmentCount = if (closed && points.size > 2) points.size else points.size - 1
    for (i in 0 until segmentCount) {
        val next = (i + 1) % points.size
        val previous = if (i == 0) {
            if (closed) points.lastIndex else 0
        } else i - 1
        val after = if (next + 1 >= points.size) {
            if (closed) 0 else points.lastIndex
        } else next + 1
        val x1 = px(i); val y1 = py(i)
        val x2 = px(next); val y2 = py(next)
        val c1x = x1 + (px(next) - px(previous)) / 6f
        val c1y = y1 + (py(next) - py(previous)) / 6f
        val c2x = x2 - (px(after) - px(i)) / 6f
        val c2y = y2 - (py(after) - py(i)) / 6f
        path.cubicTo(c1x, c1y, c2x, c2y, x2, y2)
    }
    if (closed && points.size > 2) path.close()
    return path
}
