package io.github.inkpad.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Geometry: hit testing, lasso picking, scribble to erase, partial erasing and shape recognition.
// Translated line by line from B:geom.py (A:Model/Geom.swift and W:src/core/geom.js are the same
// rules). All coordinates are page points; only indices 0 and 1 of a point are read, except where
// a pressure is carried along.

/** x0, y0, x1, y1. */
typealias Box = DoubleArray

/** What geometry needs to know of an item on a page. */
interface Shaped {
    /** "s" stroke, "h" shape, "t" text, "i" image. */
    val kind: String
    val bbox: Box

    /** The item's centre line or outline; null for items without one. */
    fun polyline(): List<Pt>?

    /** Points spread over the item, for items without a line (text, pictures). */
    fun sample(): List<Pt>
}

object Geom {
    fun union(a: Box?, b: Box?): Box? {
        if (a == null) return b
        if (b == null) return a
        return doubleArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }

    fun intersects(a: Box, b: Box): Boolean = a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3]

    fun bboxOf(pts: List<Pt>, pad: Double = 0.0): Box {
        var x0 = Double.POSITIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (p in pts) {
            if (p[0] < x0) x0 = p[0]
            if (p[0] > x1) x1 = p[0]
            if (p[1] < y0) y0 = p[1]
            if (p[1] > y1) y1 = p[1]
        }
        return doubleArrayOf(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
    }

    fun <T> decimate(pts: List<T>, limit: Int): List<T> {
        val n = pts.size
        if (n <= limit) return pts
        val step = n.toDouble() / limit
        val out = ArrayList<T>(limit + 1)
        for (i in 0 until limit) out.add(pts[(i * step).toInt()])
        out.add(pts[n - 1])
        return out
    }

    fun dist2Seg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val d = dx * dx + dy * dy
        if (d <= 1e-12) return (px - ax) * (px - ax) + (py - ay) * (py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / d).coerceIn(0.0, 1.0)
        val ex = px - ax - t * dx
        val ey = py - ay - t * dy
        return ex * ex + ey * ey
    }

    fun polylineHit(pts: List<Pt>, x: Double, y: Double, r: Double): Boolean {
        val r2 = r * r
        if (pts.size == 1) return (pts[0][0] - x) * (pts[0][0] - x) + (pts[0][1] - y) * (pts[0][1] - y) <= r2
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            if (dist2Seg(x, y, a[0], a[1], b[0], b[1]) <= r2) return true
        }
        return false
    }

    fun inPolygon(poly: List<Pt>, x: Double, y: Double): Boolean {
        var inside = false
        var ax = poly[poly.size - 1][0]
        var ay = poly[poly.size - 1][1]
        for (p in poly) {
            val bx = p[0]
            val by = p[1]
            if ((ay > y) != (by > y) && x < (bx - ax) * (y - ay) / (by - ay) + ax) inside = !inside
            ax = bx
            ay = by
        }
        return inside
    }

    fun convexHull(input: List<Pt>): List<Pt> {
        val pts = input.map { Pair(it[0], it[1]) }.toSortedSet(compareBy<Pair<Double, Double>> { it.first }.thenBy { it.second })
            .map { doubleArrayOf(it.first, it.second) }
        if (pts.size < 3) return pts
        fun half(seq: List<Pt>): List<Pt> {
            val out = ArrayList<Pt>()
            for (p in seq) {
                while (out.size >= 2 && (
                        (out[out.size - 1][0] - out[out.size - 2][0]) * (p[1] - out[out.size - 2][1]) -
                            (out[out.size - 1][1] - out[out.size - 2][1]) * (p[0] - out[out.size - 2][0])
                        ) <= 0
                ) out.removeAt(out.size - 1)
                out.add(p)
            }
            out.removeAt(out.size - 1)
            return out
        }
        return half(pts) + half(pts.asReversed())
    }

    fun segsCross(a: Pt, b: Pt, c: Pt, d: Pt): Boolean {
        fun side(p: Pt, q: Pt, r: Pt) = (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
        return (side(a, b, c) > 0) != (side(a, b, d) > 0) && (side(c, d, a) > 0) != (side(c, d, b) > 0)
    }

    // -- scribble to erase ------------------------------------------------------------------

    private const val MIN_REVERSALS = 3       // a scribble is at least four back-and-forth passes
    private const val MIN_LENGTH_RATIO = 2.5  // path length relative to its extent along the main axis
    private const val SPREAD_FREE = 0.3       // a scribble this thin (width across / length along) needs no extra passes
    private const val SPREAD_STEP = 0.25      // one more pass for each step wider than that
    private const val DENSE_RATIO = 7.0       // path length relative to extent of a rub over one small spot
    private const val STRAIGHT_PASSES = 4     // straight lines back and forth, each along the one before

    /**
     * True when a stroke looks like a quick back-and-forth scratch-out.
     *
     * Writing turns a lot too, so turning alone does not decide: a word written in one stroke goes
     * round its letters. What a scratch-out does is go back over the same ground: each pass runs at
     * least half the stroke's length, it turns about half a turn at each end and seldom in between,
     * and the more room it spreads over the more passes it takes before it reads as a scribble. A
     * rub that keeps circling one small spot counts as well, and so do straight lines drawn back
     * and forth, each close along the one before. [loose] asks for the earlier rule.
     */
    fun isScribble(pts: List<Pt>, loose: Boolean = false): Boolean {
        if (pts.size < 10) return false
        if (loose) return looseScribble(pts)
        return scratched(pts) || straightPasses(pts)
    }

    private class Axis(val mx: Double, val my: Double, val ux: Double, val uy: Double)

    private fun mainAxis(pts: List<Pt>): Axis {
        val n = pts.size
        var mx = 0.0
        var my = 0.0
        for (p in pts) { mx += p[0]; my += p[1] }
        mx /= n
        my /= n
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in pts) {
            val dx = p[0] - mx
            val dy = p[1] - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)
        return Axis(mx, my, cos(ang), sin(ang))
    }

    private fun pathLength(pts: List<Pt>): Double {
        var length = 0.0
        for (i in 1 until pts.size) length += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        return length
    }

    private fun spread(v: DoubleArray): Double = v.max() - v.min()

    /** Direction reversals along the main axis, each at least [h] from the one before. */
    private fun reversalsAlong(t: DoubleArray, h: Double): Int {
        var direction = 0
        var ext = t[0]
        var reversals = 0
        for (i in 1 until t.size) {
            val v = t[i]
            if (direction == 0) {
                if (abs(v - ext) >= h) { direction = if (v > ext) 1 else -1; ext = v }
            } else if (direction == 1) {
                if (v > ext) ext = v
                else if (ext - v >= h) { reversals++; direction = -1; ext = v }
            } else {
                if (v < ext) ext = v
                else if (v - ext >= h) { reversals++; direction = 1; ext = v }
            }
        }
        return reversals
    }

    /** The earlier rule: four passes back and forth along the main axis, each at least 15% of
     *  the stroke's length. It catches more, joined-up writing included. */
    private fun looseScribble(pts: List<Pt>): Boolean {
        val a = mainAxis(pts)
        val t = DoubleArray(pts.size) { (pts[it][0] - a.mx) * a.ux + (pts[it][1] - a.my) * a.uy }
        val extent = spread(t)
        if (extent < 5.0) return false
        if (pathLength(pts) < MIN_LENGTH_RATIO * extent) return false
        return reversalsAlong(t, max(extent * 0.15, 1.5)) >= MIN_REVERSALS
    }

    private fun scratched(pts: List<Pt>): Boolean {
        val a = mainAxis(pts)
        val t = DoubleArray(pts.size) { (pts[it][0] - a.mx) * a.ux + (pts[it][1] - a.my) * a.uy }
        val across = DoubleArray(pts.size) { (pts[it][1] - a.my) * a.ux - (pts[it][0] - a.mx) * a.uy }
        val extent = spread(t)
        if (extent < 5.0) return false
        val width = spread(across)
        val length = pathLength(pts)
        if (length < MIN_LENGTH_RATIO * extent) return false
        // Reversals each at least half the stroke's length away from the one before, so that what
        // letters do inside a word is not counted.
        val reversals = reversalsAlong(t, extent * 0.5)
        if (reversals < MIN_REVERSALS) return false
        val turned = turning(pts, max(hypot(extent, width) / 40, 0.5))
        if (length >= DENSE_RATIO * extent && turned >= 4 * PI) return true
        if (turned - PI * reversals > 2 * PI) return false   // it went round in loops besides: handwriting
        val need = MIN_REVERSALS + max(0, ceil((width / extent - SPREAD_FREE) / SPREAD_STEP - 1e-9).toInt())
        return reversals >= need
    }

    /** Straight lines back and forth: each pass turns right round from the one before, runs close
     *  along it, and is about as long. Handwriting has strokes like that (an M, a W) but not four
     *  in a row lying on top of each other. */
    private fun straightPasses(pts: List<Pt>): Boolean {
        val (legs, size) = legsOf(pts)
        var run = 1
        for (i in 0 until legs.size - 1) {
            val a = legs[i]
            val b = legs[i + 1]
            val la = hypot(a[2] - a[0], a[3] - a[1])
            val lb = hypot(b[2] - b[0], b[3] - b[1])
            val longer = max(la, lb)
            val shorter = min(la, lb)
            val back = ((a[2] - a[0]) * (b[2] - b[0]) + (a[3] - a[1]) * (b[3] - b[1])) / (la * lb) <= -0.966   // 165° or more
            // how far the middle of this pass is from the line of the one before
            val mx = (b[0] + b[2]) / 2
            val my = (b[1] + b[3]) / 2
            val off = abs((a[2] - a[0]) * (a[1] - my) - (a[0] - mx) * (a[3] - a[1])) / la
            if (back && off <= 0.08 * longer && shorter >= 0.4 * longer && shorter >= 0.2 * size) {
                run++
                if (run >= STRAIGHT_PASSES) return true
            } else {
                run = 1
            }
        }
        return false
    }

    /** A stroke as the straight runs it is made of, [x0, y0, x1, y1] each, leaving out the short
     *  bits where it turns; and the size of the stroke. */
    private fun legsOf(pts: List<Pt>): Pair<List<DoubleArray>, Double> {
        val size = hypot(spread(DoubleArray(pts.size) { pts[it][0] }), spread(DoubleArray(pts.size) { pts[it][1] }))
        if (size < 5.0) return Pair(emptyList(), size)
        val step = size / 100
        val r = arrayListOf(pts[0])
        var run = 0.0
        for (i in 1 until pts.size) {
            run += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
            if (run >= step) { r.add(pts[i]); run = 0.0 }
        }
        if (r[r.size - 1] !== pts[pts.size - 1]) r.add(pts[pts.size - 1])
        // A run ends where the way on, a few steps ahead, leaves its direction by more than 45°.
        val legs = ArrayList<DoubleArray>()
        var start = 0
        for (i in 1 until r.size - 1) {
            val j = min(i + 3, r.size - 1)
            val lx = r[i][0] - r[start][0]
            val ly = r[i][1] - r[start][1]
            val nx = r[j][0] - r[i][0]
            val ny = r[j][1] - r[i][1]
            val a = hypot(lx, ly)
            val b = hypot(nx, ny)
            if (a > 1e-9 && b > 1e-9 && (lx * nx + ly * ny) / (a * b) < 0.7071) {
                legs.add(doubleArrayOf(r[start][0], r[start][1], r[i][0], r[i][1]))
                start = i
            }
        }
        val last = r[r.size - 1]
        legs.add(doubleArrayOf(r[start][0], r[start][1], last[0], last[1]))
        fun len(l: DoubleArray) = hypot(l[2] - l[0], l[3] - l[1])
        val longest = legs.maxOf { len(it) }
        return Pair(legs.filter { len(it) >= 0.15 * longest && longest > 0 }, size)
    }

    /** How far the direction of a stroke turns in all, left and right alike, measured on points
     *  about [step] apart so that the tremor of the hand does not add up. */
    fun turning(pts: List<Pt>, step: Double): Double {
        val kept = arrayListOf(pts[0])
        var run = 0.0
        for (i in 1 until pts.size) {
            run += hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
            if (run >= step) { kept.add(pts[i]); run = 0.0 }
        }
        val full = 2 * PI
        var total = 0.0
        var prev: Double? = null
        for (i in 1 until kept.size) {
            val heading = atan2(kept[i][1] - kept[i - 1][1], kept[i][0] - kept[i - 1][0])
            if (prev != null) {
                var d = (heading - prev + PI) % full   // Python's % is never negative
                if (d < 0) d += full
                total += abs(d - PI)
            }
            prev = heading
        }
        return total
    }

    /** Items a scribble covers: mostly inside its hull, or crossed repeatedly. */
    fun <T : Shaped> scribbleTargets(pts: List<Pt>, items: List<T>): List<T> {
        val sc = decimate(pts, 150)
        val bb = bboxOf(sc)
        val hull = convexHull(sc)
        if (hull.size < 3) return emptyList()
        val out = ArrayList<T>()
        for (it in items) {
            if ((it.kind != "s" && it.kind != "h") || !intersects(it.bbox, bb)) continue
            val pl = decimate(it.polyline() ?: continue, 150)
            if (pl.size == 1) {
                if (inPolygon(hull, pl[0][0], pl[0][1])) out.add(it)
                continue
            }
            var total = 0.0
            var inside = 0.0
            for (i in 1 until pl.size) {
                val a = pl[i - 1]
                val b = pl[i]
                val seg = hypot(b[0] - a[0], b[1] - a[1]) + 1e-6
                total += seg
                if (inPolygon(hull, (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)) inside += seg
            }
            val cover = inside / total
            if (cover >= 0.35) out.add(it)
            else if (cover >= 0.08 && crossings(sc, pl, 4) >= 4) out.add(it)
        }
        return out
    }

    private fun crossings(aPts: List<Pt>, bPts: List<Pt>, enough: Int): Int {
        var count = 0
        for (i in 1 until aPts.size) {
            for (j in 1 until bPts.size) {
                if (segsCross(aPts[i - 1], aPts[i], bPts[j - 1], bPts[j])) {
                    count++
                    if (count >= enough) return count
                }
            }
        }
        return count
    }

    /** Whether any part of an item lies in a region given by its box and a point test. */
    private fun touching(it: Shaped, box: Box, inside: (Double, Double) -> Boolean): Boolean {
        if (!intersects(it.bbox, box)) return false
        val line = it.polyline()
        val pts = if (line != null && line.size > 1) densify(line, 3.0) else it.sample()
        return pts.any { p -> inside(p[0], p[1]) }
    }

    /** Items the lasso touches: having any part inside it selects the whole item. */
    fun <T : Shaped> lassoPick(lasso: List<Pt>, items: List<T>): List<T> {
        val poly = decimate(lasso, 120)
        if (poly.size < 3) return emptyList()
        val bb = bboxOf(poly)
        val out = ArrayList<T>()
        for (it in items) {
            if (touching(it, bb) { x, y -> inPolygon(poly, x, y) }) {
                out.add(it)
            } else if ((it.kind == "i" || it.kind == "t") && intersects(it.bbox, bb)) {
                // Pictures and text boxes have an inside: a loop drawn over one counts too.
                val (x0, y0, x1, y1) = it.bbox
                if (poly.any { p -> x0 <= p[0] && p[0] <= x1 && y0 <= p[1] && p[1] <= y1 }) out.add(it)
            }
        }
        return out
    }

    fun <T : Shaped> rectPick(box: Box, items: List<T>): List<T> {
        val out = ArrayList<T>()
        for (it in items) {
            if (it.kind == "i" || it.kind == "t") {
                if (intersects(it.bbox, box)) out.add(it)
            } else if (touching(it, box) { x, y -> box[0] <= x && x <= box[2] && box[1] <= y && y <= box[3] }) {
                out.add(it)
            }
        }
        return out
    }

    // -- partial erasing --------------------------------------------------------------------

    /** Insert interpolated samples so no segment is longer than [step]. */
    fun densify(pts: List<Pt>, step: Double): List<Pt> {
        val out = arrayListOf(pts[0])
        for (i in 1 until pts.size) {
            val b = pts[i]
            val a = out[out.size - 1]
            val d = hypot(b[0] - a[0], b[1] - a[1])
            if (d > step) {
                val n = (d / step).toInt() + 1
                for (k in 1 until n) {
                    val t = k.toDouble() / n
                    // Outlines of shapes, pictures and text are plain (x, y); ink has a pressure too.
                    out.add(DoubleArray(min(a.size, b.size)) { j -> a[j] + (b[j] - a[j]) * t })
                }
            }
            out.add(b)
        }
        return out
    }

    /**
     * Cut the part of a stroke within [r] of segment a-b out of it. Returns the surviving runs of
     * points and the bounding box of the part that changed, or null when nothing was touched. Only
     * the segments next to the eraser are resampled, so the rest of the stroke keeps its shape.
     */
    fun eraseRuns(pts: List<Pt>, ax: Double, ay: Double, bx: Double, by: Double, r: Double): Pair<List<List<Pt>>, Box>? {
        val r2 = r * r
        val n = pts.size
        val inside = BooleanArray(n) { dist2Seg(pts[it][0], pts[it][1], ax, ay, bx, by) <= r2 }
        val step = max(r * 0.4, 0.3)
        val ea = doubleArrayOf(ax, ay)
        val eb = doubleArrayOf(bx, by)
        val runs = ArrayList<List<Pt>>()
        var run = ArrayList<Pt>()
        var cut = inside[0]
        var lo = if (inside[0]) 0 else n
        var hi = if (inside[0]) 0 else -1
        if (!inside[0]) run.add(pts[0])
        for (i in 1 until n) {
            val a = pts[i - 1]
            val b = pts[i]
            if (!(inside[i - 1] || inside[i] ||
                    dist2Seg(ax, ay, a[0], a[1], b[0], b[1]) <= r2 ||
                    dist2Seg(bx, by, a[0], a[1], b[0], b[1]) <= r2 ||
                    segsCross(a, b, ea, eb))
            ) {
                run.add(b)
                continue
            }
            lo = min(lo, i - 1)
            hi = max(hi, i)
            val k = max(1, (hypot(b[0] - a[0], b[1] - a[1]) / step).toInt() + 1)
            for (j in 1..k) {
                val t = j.toDouble() / k
                val q = if (j == k) b else doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
                if (dist2Seg(q[0], q[1], ax, ay, bx, by) <= r2) {
                    cut = true
                    if (run.isNotEmpty()) { runs.add(run); run = ArrayList() }
                } else {
                    run.add(q)
                }
            }
        }
        if (!cut) return null
        if (run.isNotEmpty()) runs.add(run)
        return Pair(runs, bboxOf(pts.subList(max(0, lo - 2), min(n, hi + 3))))
    }

    // -- hold to snap to a shape ------------------------------------------------------------

    /** A recognised shape: "line", "rect" or "ellipse" and its two corners. */
    data class Recognized(val shape: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /** Classify a freehand stroke as a line, a rectangle or an ellipse, or null. */
    fun recognizeShape(pts: List<Pt>): Recognized? {
        val n = pts.size
        if (n < 6) return null
        val length = pathLength(pts)
        val first = pts[0]
        val last = pts[n - 1]
        val chord = hypot(last[0] - first[0], last[1] - first[1])
        if (length < 8) return null
        if (chord > 0.88 * length) return Recognized("line", first[0], first[1], last[0], last[1])
        if (chord > 0.25 * length) return null   // neither straight nor closed
        val (x0, y0, x1, y1) = bboxOf(pts)
        val w = x1 - x0
        val h = y1 - y0
        if (min(w, h) < 4) return null
        val cx = (x0 + x1) / 2
        val cy = (y0 + y1) / 2
        val rx = w / 2
        val ry = h / 2
        var eErr = 0.0
        var rErr = 0.0
        for (p in pts) {
            eErr += abs(hypot((p[0] - cx) / rx, (p[1] - cy) / ry) - 1)
            rErr += minOf(p[0] - x0, x1 - p[0], p[1] - y0, y1 - p[1]) / min(w, h)
        }
        eErr /= n
        rErr /= n
        if (rErr < 0.07 && rErr < eErr) return Recognized("rect", x0, y0, x1, y1)
        if (eErr < 0.16) return Recognized("ellipse", x0, y0, x1, y1)
        return null
    }
}
