package io.github.inkpad.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

// Stroke point packing (B:model.py pack_points / unpack_points; W:src/core/pack.js).
//
// Byte stream = float64LE(x0) + float64LE(y0), then for every point (the first included)
// zigzag-varint(dx) + zigzag-varint(dy) + one pressure byte. The steps are between neighbouring
// points in hundredths of a point; the first point's step is 0. Whiteboard coordinates times 100
// exceed 2^31, so everything here is a Long.

/** A point of a stroke: x, y and pressure (0..1). Outlines of shapes may have only x and y. */
typealias Pt = DoubleArray

fun pt(x: Double, y: Double, p: Double = 0.5): Pt = doubleArrayOf(x, y, p)

/** Python's round(): halves go to the even neighbour, so the bytes match B's exactly. */
fun pyRound(x: Double): Long = Math.rint(x).toLong()

private fun zig(value: Long, out: java.io.ByteArrayOutputStream) {
    var n = if (value >= 0) 2 * value else -2 * value - 1
    while (true) {
        val b = (n and 0x7f).toInt()
        n = n ushr 7
        if (n != 0L) {
            out.write(b or 0x80)
        } else {
            out.write(b)
            return
        }
    }
}

fun packPoints(pts: List<Pt>): String {
    if (pts.isEmpty()) return ""
    val out = java.io.ByteArrayOutputStream()
    val head = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
    head.putDouble(pts[0][0]).putDouble(pts[0][1])
    out.write(head.array())
    var px = pyRound(pts[0][0] * 100)
    var py = pyRound(pts[0][1] * 100)
    for (p in pts) {
        val cx = pyRound(p[0] * 100)
        val cy = pyRound(p[1] * 100)
        zig(cx - px, out)
        zig(cy - py, out)
        out.write(pyRound(p[2] * 255).coerceIn(0, 255).toInt())
        px = cx
        py = cy
    }
    return Base64.getEncoder().encodeToString(out.toByteArray())
}

class BadStrokeData : Exception("truncated stroke data")

fun unpackPoints(text: String): List<Pt> {
    // Like Python's b64decode, characters outside the alphabet (line breaks, say) are skipped.
    val data = Base64.getMimeDecoder().decode(text)
    if (data.isEmpty()) return emptyList()
    val head = ByteBuffer.wrap(data, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
    val x0 = head.getDouble()
    val y0 = head.getDouble()
    var px = pyRound(x0 * 100)
    var py = pyRound(y0 * 100)
    val pts = ArrayList<Pt>()
    var i = 16
    val n = data.size
    val step = LongArray(2)
    while (i < n) {
        for (k in 0..1) {
            var value = 0L
            var shift = 0
            while (true) {
                if (i >= n) throw BadStrokeData()
                val c = data[i++].toInt() and 0xff
                value = value or ((c and 0x7f).toLong() shl shift)
                shift += 7
                if (c and 0x80 == 0) break
            }
            step[k] = (value ushr 1) xor -(value and 1)
        }
        px += step[0]
        py += step[1]
        if (i >= n) throw BadStrokeData()
        pts.add(doubleArrayOf(px / 100.0, py / 100.0, (data[i].toInt() and 0xff) / 255.0))
        i++
    }
    if (pts.isNotEmpty()) pts[0] = doubleArrayOf(x0, y0, pts[0][2])
    return pts
}
