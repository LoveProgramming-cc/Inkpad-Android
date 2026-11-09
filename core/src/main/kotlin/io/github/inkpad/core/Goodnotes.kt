package io.github.inkpad.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Reading and writing Goodnotes documents (.goodnotes). Translated from B:goodnotes.py through
// W:src/io/goodnotes.js; the format is not published, what is here was worked out from files. A
// document is a zip archive:
//
//   index.events.pb       a log of length-prefixed protobuf records describing the document: its
//                         title, its page templates (a PDF attachment, a page of it, and the size the
//                         template is laid out at) and its pages (which template, and a sort key)
//   index.notes.pb        page id -> the file with that page's content
//   notes/<id>            length-prefixed protobuf records, a header and a body per element; a later
//                         body for the same element replaces the earlier one, and one marked as
//                         deleted removes it
//   attachments/<id>      the PDFs that the pages are drawn on (paper templates included)
//
// A stroke's geometry is an Apple-LZ4 compressed "tpl" block: a type string followed by the values
// it describes. Only the centre line and the width are read; the cached outline that follows is
// ignored. What is imported: pages in order, their PDF backgrounds, pen strokes with colour and
// width, text boxes and notes (plain text), pictures and straight connectors.

object Goodnotes {
    /** Goodnotes lays a template out larger than its PDF page; this is the factor when there is no PDF to measure. */
    const val FALLBACK_SCALE = 6.0 / 11
    /** The number stored with each point of a pressure-sensitive stroke is not the line's half width:
     *  measured against the outlines Goodnotes caches next to it, the line is about 1.42 times that wide. */
    const val PEN_WIDTH = 1.42
    const val IMAGE_LIMIT = 2400

    class GoodnotesError(message: String) : Exception(message)

    data class Stats(var pages: Int = 0, var strokes: Int = 0, var texts: Int = 0, var shapes: Int = 0, var formulas: Int = 0, var images: Int = 0, var skipped: Int = 0)

    // -- protobuf, as much of it as is needed ---------------------------------------------------

    private class Field(val number: Int, val wire: Int, val value: Any)   // Long, or ByteArray

    private fun varint(b: ByteArray, i0: Int): Pair<Long, Int> {
        var i = i0
        var value = 0L
        var shift = 0
        while (true) {
            if (i >= b.size) throw IllegalArgumentException("truncated message")
            val c = b[i++].toInt() and 0xff
            if (shift < 64) value = value or ((c and 0x7f).toLong() shl shift)
            shift += 7
            if (c and 0x80 == 0) return Pair(value, i)
        }
    }

    private fun fields(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        val n = b.size
        while (i < n) {
            val (key, j) = varint(b, i)
            i = j
            val number = (key ushr 3).toInt()
            val wire = (key and 7).toInt()
            val value: Any = when (wire) {
                0 -> varint(b, i).also { i = it.second }.first
                1 -> b.copyOfRange(i, i + 8).also { i += 8 }
                2 -> {
                    val (size, k) = varint(b, i)
                    i = k
                    if (i + size > n) throw IllegalArgumentException("truncated message")
                    b.copyOfRange(i, i + size.toInt()).also { i += size.toInt() }
                }
                5 -> b.copyOfRange(i, i + 4).also { i += 4 }
                else -> throw IllegalArgumentException("wire type $wire")
            }
            if (i > n) throw IllegalArgumentException("truncated message")
            out.add(Field(number, wire, value))
        }
        return out
    }

    private class Msg(val m: Map<Int, List<Any>>) {
        operator fun contains(n: Int) = n in m
        fun all(n: Int): List<Any> = m[n] ?: emptyList()
        fun bytes(n: Int): ByteArray? = m[n]?.firstOrNull() as? ByteArray
        fun b(n: Int): ByteArray = bytes(n) ?: ByteArray(0)
        fun num(n: Int, def: Long = 0): Long = m[n]?.firstOrNull() as? Long ?: def
        fun first(n: Int): Any? = m[n]?.firstOrNull()
        fun text(n: Int) = bytes(n)?.toString(Charsets.UTF_8) ?: ""
        fun sub(n: Int) = message(b(n))
    }

    private fun message(b: ByteArray?): Msg {
        val out = LinkedHashMap<Int, MutableList<Any>>()
        for (f in fields(b ?: ByteArray(0))) out.getOrPut(f.number) { ArrayList() }.add(f.value)
        return Msg(out)
    }

    private fun records(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < b.size) {
            val (size, j) = varint(b, i)
            i = j
            out.add(b.copyOfRange(i, min(b.size, i + size.toInt())))
            i += size.toInt()
        }
        return out
    }

    private fun f32(b: ByteArray, at: Int = 0) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).float.toDouble()

    /** A point, size or colour: float fields 1..n. A component that is zero is simply left out of the
     *  file, so the values have to be placed by field number, not by position. */
    private fun vec(value: ByteArray?, n: Int): DoubleArray {
        val out = DoubleArray(n)
        for (f in fields(value ?: ByteArray(0))) if (f.wire == 5 && f.number in 1..n) out[f.number - 1] = f32(f.value as ByteArray)
        return out
    }

    // -- Apple's LZ4 framing ----------------------------------------------------------------------

    private fun lz4Block(src: ByteArray, from: Int, to: Int, out: ByteArrayOutputStream) {
        val buf = ArrayList<Byte>()
        var i = from
        while (i < to) {
            val token = src[i++].toInt() and 0xff
            var literal = token shr 4
            if (literal == 15) while (true) {
                val c = src[i++].toInt() and 0xff
                literal += c
                if (c != 255) break
            }
            for (k in 0 until literal) buf.add(src[i + k])
            i += literal
            if (i >= to) break
            val offset = (src[i].toInt() and 0xff) or ((src[i + 1].toInt() and 0xff) shl 8)
            i += 2
            var length = token and 15
            if (length == 15) while (true) {
                val c = src[i++].toInt() and 0xff
                length += c
                if (c != 255) break
            }
            length += 4
            val start = buf.size - offset
            for (k in 0 until length) buf.add(buf[start + k])   // the match may overlap what it produces
        }
        out.write(buf.toByteArray())
    }

    /** Blocks tagged bv41 (LZ4) or bv4- (stored), ended by bv4$. */
    private fun decompress(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 4 <= b.size) {
            val magic = String(b, i, 4, Charsets.ISO_8859_1)
            i += 4
            when (magic) {
                "bv41" -> {
                    val size = d.getInt(i + 4)
                    i += 8
                    lz4Block(b, i, i + size, out)
                    i += size
                }
                "bv4-" -> {
                    val size = d.getInt(i)
                    i += 4
                    out.write(b, i, size)
                    i += size
                }
                "bv4$" -> return out.toByteArray()
                else -> throw IllegalArgumentException("not an LZ4 stream")
            }
        }
        return out.toByteArray()
    }

    // -- "tpl" blocks -------------------------------------------------------------------------------

    /** The type string as a tree: v u f are scalars, A(..) an array, S(..) a struct. */
    private fun signature(sig: String, i0: Int = 0): Pair<List<Any>, Int> {
        val out = ArrayList<Any>()
        var i = i0
        while (i < sig.length && sig[i] != ')') {
            val c = sig[i]
            if (c == 'A' || c == 'S') {
                val (inner, j) = signature(sig, i + 2)
                out.add(Pair(c, inner))
                i = j + 1
            } else {
                out.add(c)
                i += 1
            }
        }
        return Pair(out, i)
    }

    @Suppress("UNCHECKED_CAST")
    private fun readTpl(types: List<Any>, d: ByteBuffer, i0: Int): Pair<List<Any>, Int> {
        val values = ArrayList<Any>()
        var i = i0
        for (t in types) {
            when (t) {
                'v' -> { values.add((d.getShort(i).toInt() and 0xffff).toDouble()); i += 2 }
                'u', 'f' -> { values.add(d.getFloat(i).toDouble()); i += 4 }
                else -> {
                    val (kind, inner) = t as Pair<Char, List<Any>>
                    if (kind == 'A') {
                        val count = d.getInt(i)
                        i += 4
                        if (inner.size == 1 && inner[0] == 'v') {
                            values.add((0 until count).map { (d.getShort(i + 2 * it).toInt() and 0xffff).toDouble() })
                            i += 2 * count
                        } else if (inner.size == 1 && (inner[0] == 'u' || inner[0] == 'f')) {
                            values.add((0 until count).map { d.getFloat(i + 4 * it).toDouble() })
                            i += 4 * count
                        } else {
                            val items = ArrayList<Any>()
                            repeat(count) {
                                val (item, j) = readTpl(inner, d, i)
                                items.add(item)
                                i = j
                            }
                            values.add(items)
                        }
                    } else {
                        val (item, j) = readTpl(inner, d, i)
                        values.add(item)
                        i = j
                    }
                }
            }
        }
        return Pair(values, i)
    }

    private fun tpl(raw: ByteArray): Pair<String, List<Any>> {
        if (!(raw[0] == 0x74.toByte() && raw[1] == 0x70.toByte() && raw[2] == 0x6c.toByte() && raw[3] == 0.toByte())) throw IllegalArgumentException("not a tpl block")
        var end = 8
        while (raw[end] != 0.toByte()) end++
        val sig = String(raw, 8, end - 8, Charsets.ISO_8859_1)
        val d = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return Pair(sig, readTpl(signature(sig).first, d, end + 1).first)
    }

    // -- strokes --------------------------------------------------------------------------------------

    /** Add the middle and the end of a quadratic curve to a polyline of [x, y, width]. */
    private fun quad(points: MutableList<DoubleArray>, p0: DoubleArray, c: DoubleArray, p1: DoubleArray) {
        points.add(DoubleArray(3) { 0.25 * p0[it] + 0.5 * c[it] + 0.25 * p1[it] })
        points.add(p1)
    }

    /** A width factor, or 1 where the file holds something that is not one. */
    private fun factor(k: Double) = if (k > 0.05 && k < 8) k else 1.0

    @Suppress("UNCHECKED_CAST")
    private fun nums(v: Any) = v as List<Double>

    @Suppress("UNCHECKED_CAST")
    private fun structs(v: Any) = (v as List<Any>).map { (it as List<Any>)[0] as List<Double> }

    /** A stroke's centre lines as (tool hint, [[x, y, width]...]); width is a diameter. */
    private fun paths(raw: ByteArray): List<Pair<String, MutableList<DoubleArray>>> {
        val (sig, v) = tpl(raw)
        val out = ArrayList<Pair<String, MutableList<DoubleArray>>>()
        if (sig.startsWith("vuA(v)A(S(uu))A(S(uuuu))")) {
            // Constant width: op 0 starts at the next point, op 1 is a curve (control, end).
            val width = v[1] as Double
            val ops = nums(v[2])
            val moves = structs(v[3])
            val curves = structs(v[4])
            var mi = 0
            var ci = 0
            var current: MutableList<DoubleArray>? = null
            for (op in ops) {
                if (op == 0.0 && mi < moves.size) {
                    val (x, y) = moves[mi++]
                    current = arrayListOf(doubleArrayOf(x, y, width))
                    out.add(Pair("ball", current))
                } else if (op == 1.0 && ci < curves.size && current != null) {
                    val (cx, cy, x, y) = curves[ci++]
                    quad(current, current.last(), doubleArrayOf(cx, cy, width), doubleArrayOf(x, y, width))
                }
            }
        } else if (sig.startsWith("vA(v)A(u)A(u)")) {
            // Variable width: op 4 starts at (x, y, radius, ..), op 5 is a curve whose control and end point each carry a radius.
            val ops = nums(v[1])
            val moves = nums(v[2])
            val curves = nums(v[3])
            var mi = 0
            var ci = 0
            var current: MutableList<DoubleArray>? = null
            for (op in ops) {
                if (op == 4.0 && mi + 4 <= moves.size) {
                    current = arrayListOf(doubleArrayOf(moves[mi], moves[mi + 1], PEN_WIDTH * moves[mi + 2]))
                    mi += 4
                    out.add(Pair("fountain", current))
                } else if (op == 5.0 && ci + 9 <= curves.size && current != null) {
                    quad(current, current.last(), doubleArrayOf(curves[ci], curves[ci + 1], PEN_WIDTH * curves[ci + 2]), doubleArrayOf(curves[ci + 3], curves[ci + 4], PEN_WIDTH * curves[ci + 5]))
                    ci += 9
                }
            }
        } else if (sig.startsWith("vuA(v)A(u)A(u)")) {
            // A brush stroke, in up to two stretches that share the two arrays. It starts with a taper of
            // overlapping dabs: op 2 is a dab's centre (x, y, size) and op 3 its extent. It may go on at a
            // constant width, op 0 (x, y) and op 1 (control, end), or as a broad nib with a size at every
            // point: op 6 (x, y, size, ..) and op 7 (control, end, their sizes, three more numbers).
            var base = v[1] as Double
            val ops = nums(v[2])
            val points = nums(v[3])
            val curves = nums(v[4])
            val k = 1.0
            var nib = 0.74
            if (base > 0) base /= PEN_WIDTH
            var edge: DoubleArray? = null
            val extra = v.getOrNull(10) as? List<*>
            if (extra != null && extra.size >= 7 && (extra[3] as Double) > 0) {
                edge = doubleArrayOf(extra[4] as Double, extra[5] as Double)
                nib = min(1.0, 2 * hypot(edge[0], edge[1]) / (extra[3] as Double)).takeIf { it > 0 } ?: 0.74
            }
            val broad = ArrayList<MutableList<DoubleArray>>()
            var pi = 0
            var ci = 0
            var current: MutableList<DoubleArray>? = null
            var tail: DoubleArray? = null   // where the last dab reaches to, used when nothing follows the taper
            fun to(x: Double, y: Double, w: Double) {
                val cur = current
                if (cur == null) {
                    current = arrayListOf(doubleArrayOf(x, y, w))
                    out.add(Pair("fountain", current!!))
                } else if (abs(cur.last()[0] - x) + abs(cur.last()[1] - y) > 0.01) {
                    cur.add(doubleArrayOf(x, y, w))
                }
            }
            for (op in ops) {
                when {
                    op == 2.0 && pi + 3 <= points.size -> {
                        to(points[pi], points[pi + 1], k * points[pi + 2])
                        pi += 3
                    }
                    op == 3.0 && ci + 6 <= curves.size -> {
                        tail = doubleArrayOf(curves[ci + 2], curves[ci + 3], k * curves[ci + 5])
                        ci += 6
                    }
                    op == 0.0 && pi + 2 <= points.size -> {
                        to(points[pi], points[pi + 1], if (base > 0) base else current?.last()?.get(2) ?: 1.0)
                        pi += 2
                        tail = null
                    }
                    op == 1.0 && ci + 4 <= curves.size && current != null -> {
                        val cur = current!!
                        val w = if (base > 0) base else cur.last()[2]
                        quad(cur, cur.last(), doubleArrayOf(curves[ci], curves[ci + 1], w), doubleArrayOf(curves[ci + 2], curves[ci + 3], w))
                        ci += 4
                    }
                    op == 6.0 && pi + 4 <= points.size -> {
                        val cur0 = current
                        if (nib < 1 && cur0 != null) for (j in cur0.indices) cur0[j] = doubleArrayOf(cur0[j][0], cur0[j][1], nib * cur0[j][2])   // the taper before it is in the same unit
                        to(points[pi], points[pi + 1], nib * points[pi + 2])
                        pi += 4
                        if (broad.none { it === current }) broad.add(current!!)
                        tail = null
                    }
                    op == 7.0 && ci + 9 <= curves.size && current != null -> {
                        val cur = current!!
                        quad(cur, cur.last(), doubleArrayOf(curves[ci], curves[ci + 1], nib * curves[ci + 4]), doubleArrayOf(curves[ci + 2], curves[ci + 3], nib * curves[ci + 5]))
                        ci += 9
                    }
                    else -> throw IllegalArgumentException("unknown op $op in a brush stroke")
                }
            }
            val t = tail
            if (t != null && current != null) to(t[0], t[1], t[2])
            current?.let { if (it.size == 1) it.add(doubleArrayOf(it[0][0] + 0.01, it[0][1], it[0][2])) }
            if (edge != null && hypot(edge[0], edge[1]) > 0) {
                val len = hypot(edge[0], edge[1])
                val ex = edge[0] / len
                val ey = edge[1] / len
                for (pts in broad) {
                    val orig = pts.map { it.copyOf() }
                    for (i in pts.indices) {
                        val a = orig[max(0, i - 1)]
                        val b = orig[min(pts.size - 1, i + 1)]
                        val dx = b[0] - a[0]
                        val dy = b[1] - a[1]
                        val dd = hypot(dx, dy)
                        val across = if (dd > 0) abs(ex * dy - ey * dx) / dd else 1.0
                        pts[i] = doubleArrayOf(pts[i][0], pts[i][1], pts[i][2] * max(0.6, across))
                    }
                }
            }
        } else if (sig.startsWith("vuA(v)A(S(uuuuu))A(S(uuuuuuuuuuu))")) {
            // A base width, and points that each carry a factor for it.
            val width = v[1] as Double
            val ops = nums(v[2])
            val moves = structs(v[3])
            val curves = structs(v[4])
            var mi = 0
            var ci = 0
            var current: MutableList<DoubleArray>? = null
            for (op in ops) {
                if (op == 0.0 && mi < moves.size) {
                    val m = moves[mi++]
                    current = arrayListOf(doubleArrayOf(m[0], m[1], width * factor(m[2])))
                    out.add(Pair("fountain", current))
                } else if (op == 1.0 && ci < curves.size && current != null) {
                    val c = curves[ci++]
                    quad(current, current.last(), doubleArrayOf(c[1], c[2], width * factor(c[3])), doubleArrayOf(c[6], c[7], width * factor(c[8])))
                }
            }
        } else {
            throw IllegalArgumentException("unknown stroke layout $sig")
        }
        for ((_, pts) in out) {
            if (pts.any { (x, y, w) -> !(abs(x) < 1e7 && abs(y) < 1e7 && w >= 0 && w < 400) }) throw IllegalArgumentException("stroke read as nonsense")   // better none than a blot over the page
        }
        return out.filter { it.second.isNotEmpty() }
    }

    /** The outline of a recognised shape, stored as its geometry instead of as a drawn path. */
    private fun shapePaths(shape: ByteArray): List<Pair<String, MutableList<DoubleArray>>> {
        val m = message(shape)
        val width = vec(shape, 15)[14].takeIf { it != 0.0 } ?: 2.0
        var pts: List<DoubleArray> = emptyList()
        var corners = false
        if (1 in m) {
            pts = message(m.all(1)[0] as ByteArray).all(1).map { vec(it as ByteArray, 2) }
            if (pts.size == 1) pts = listOf(pts[0], pts[0])
            corners = true
        } else if (2 in m) {
            // The middle point is taken to be the curve's control point; the file does not say.
            val g = message(m.all(2)[0] as ByteArray)
            val a = vec(g.bytes(1), 2)
            val c = vec(g.bytes(2), 2)
            val b = vec(g.bytes(3), 2)
            pts = (0..16).map { i ->
                val t = i / 16.0
                DoubleArray(2) { k -> (1 - t) * (1 - t) * a[k] + 2 * t * (1 - t) * c[k] + t * t * b[k] }
            }
        } else if (3 in m) {
            val g = message(m.all(3)[0] as ByteArray)
            val (cx, cy) = vec(g.bytes(1), 2)
            val (w, h) = vec(g.bytes(2), 2)
            pts = listOf(doubleArrayOf(cx - w / 2, cy - h / 2), doubleArrayOf(cx + w / 2, cy - h / 2), doubleArrayOf(cx + w / 2, cy + h / 2), doubleArrayOf(cx - w / 2, cy + h / 2), doubleArrayOf(cx - w / 2, cy - h / 2))
            corners = true
        } else if (4 in m) {
            val g = message(m.all(4)[0] as ByteArray)
            val (cx, cy) = vec(g.bytes(1), 2)
            val (rx, ry) = vec(g.bytes(2), 2)
            val turn = vec(m.all(4)[0] as ByteArray, 3)[2]
            val c = cos(turn)
            val s = sin(turn)
            pts = (0..48).map { i ->
                val a = i * TAU / 48
                val x = rx * cos(a)
                val y = ry * sin(a)
                doubleArrayOf(cx + x * c - y * s, cy + x * s + y * c)
            }
        }
        if (corners) {
            // Inkpad rounds a stroke through its points; a point given twice stays a sharp corner.
            pts = listOf(pts.first()) + pts.subList(1, pts.size - 1).flatMap { listOf(it, it) } + listOf(pts.last())
        }
        val dashed = 1 in message(m.b(5))
        return if (pts.size > 1) listOf(Pair(if (dashed) "dash" else "ball", pts.map { doubleArrayOf(it[0], it[1], width) }.toMutableList())) else emptyList()
    }

    /** An element's bounding box, from its origin and size. */
    private fun frame(body: Msg): DoubleArray {
        val f = body.sub(2)
        val (x, y) = vec(f.bytes(1), 2)
        val (w, h) = vec(f.bytes(2), 2)
        return doubleArrayOf(x - 4, y - 4, x + w + 4, y + h + 4)
    }

    /** Inkpad strokes for one stroke element. [within] drops paths outside a box: a formula's parts
     *  include helper strokes in a coordinate system of their own. */
    private fun strokes(body: Msg, scale: Double, dataField: Int = 2, colorField: Int = 4, within: DoubleArray? = null): List<Stroke> {
        val data = body.bytes(dataField)
        if (data == null || data.isEmpty()) return emptyList()
        val rgba = vec(body.bytes(colorField), 4)
        val color = DoubleArray(3) { rgba[it].coerceIn(0.0, 1.0) }
        val alpha = rgba[3]
        val (dx, dy) = vec(body.bytes(6), 2)   // where the stroke was moved to after it was drawn
        val marker = body.num(5) != 0L         // a highlighter: drawn in full colour, blended under the text
        val out = ArrayList<Stroke>()
        var ps = paths(decompress(data))
        if (ps.isEmpty()) body.bytes(9)?.let { ps = shapePaths(it) }
        for ((tool, pts0) in ps) {
            if (within != null && !pts0.all { (x, y) -> within[0] <= x && x <= within[2] && within[1] <= y && y <= within[3] }) continue
            val pts = pts0.map { (x, y, w) -> doubleArrayOf((x + dx) * scale, (y + dy) * scale, w * scale) }
            val widths = pts.map { it[2] }
            val mean = max(0.2, widths.sum() / widths.size)
            if (marker || alpha < 0.9) out.add(Stroke("hl", color, mean, pts.map { doubleArrayOf(it[0], it[1], 0.5) }))
            else if (tool == "fountain" && widths.max() - widths.min() > 0.02) {
                // Inkpad's fountain pen draws width * (0.4 + 1.2 * pressure): turn each width back into the pressure that gives it.
                out.add(Stroke("fountain", color, mean, pts.map { doubleArrayOf(it[0], it[1], ((it[2] / mean - 0.4) / 1.2).coerceIn(0.0, 1.0)) }, 1.0))
            } else out.add(Stroke("ball", color, mean, pts.map { doubleArrayOf(it[0], it[1], 0.5) }, 1.0, if (tool == "dash") 1 else 0))
        }
        return out
    }

    /** The plain text of a compressed rich-text block. */
    private fun runs(packed: ByteArray): String =
        message(decompress(packed)).all(1).joinToString("") { message(it as ByteArray).text(1) }.trim('\n')

    /** A text box as plain text; its styling is not read. */
    private fun textBox(body: Msg, scale: Double): Text? {
        val content = body.sub(32)
        val packed = content.sub(1).bytes(2)
        val t = if (packed != null) runs(packed) else ""
        if (t.isBlank()) return null
        val (x, y) = vec(body.sub(20).bytes(1), 2)
        val layout = body.sub(21)
        val width = if (2 in layout) vec(layout.bytes(2), 1)[0] else vec(content.bytes(2), 1)[0]
        val style = content.sub(5).sub(1)
        val font = style.bytes(40)?.let { f32(it) } ?: 18.0
        val pad = 10 * scale   // the box's inner margin
        return Text(x * scale + pad, y * scale + pad, t, max(6.0, font * scale), doubleArrayOf(0.1, 0.1, 0.1), max(60.0, (if (width != 0.0) width else 400.0) * scale - 2 * pad))
    }

    /** A comment or sticky note: its text on a slip of its colour. */
    private fun sticky(body: Msg, scale: Double): Text? {
        val packed = body.sub(31).sub(1).bytes(2)
        val t = if (packed != null) runs(packed) else ""
        if (t.isBlank()) return null
        val (x, y) = vec(body.sub(20).bytes(1), 2)
        val color = vec(body.bytes(30), 3).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
        val layout = body.sub(21)
        val width = if (2 in layout) vec(layout.bytes(2), 1)[0] * scale else 0.0
        return Text(x * scale + 8, y * scale + 8, t, 11.0, doubleArrayOf(0.15, 0.15, 0.15), max(160.0, width - 16), color)
    }

    /** The text of an RTF fragment, without its formatting. */
    fun unrtf(rtf: String): String {
        val out = StringBuilder()
        var i = 0
        var depth = 0
        var skip: Int? = null
        val skipped = listOf("{\\fonttbl", "{\\colortbl", "{\\*", "{\\stylesheet", "{\\info")
        while (i < rtf.length) {
            val c = rtf[i]
            if (c == '{') {
                depth++
                if (skip == null && skipped.any { rtf.startsWith(it, i) }) skip = depth
                i++
            } else if (c == '}') {
                if (skip == depth) skip = null
                depth--
                i++
            } else if (c == '\\') {
                val j = i + 1
                if (j < rtf.length && rtf[j] == '\'') {   // \'hh: a byte in the document's code page
                    if (skip == null) rtf.substring(j + 1, min(rtf.length, j + 3)).toIntOrNull(16)?.let { out.append(String(byteArrayOf(it.toByte()), charset("windows-1252"))) }
                    i = j + 3
                } else if (j < rtf.length && !rtf[j].isLetter()) {   // an escaped character, or a line break
                    if (skip == null) out.append(rtf[j])
                    i = j + 1
                } else {
                    var k = j
                    while (k < rtf.length && rtf[k].isLetter() && rtf[k].code < 128) k++
                    val word = rtf.substring(j, k)
                    var m = k
                    while (m < rtf.length && (rtf[m].isDigit() || rtf[m] == '-')) m++
                    if (skip == null) {
                        if (word == "par" || word == "line") out.append('\n')
                        else if (word == "tab") out.append('\t')
                        else if (word == "u" && m > k) {
                            out.append((((rtf.substring(k, m).toInt() % 65536) + 65536) % 65536).toChar())
                            if (m < rtf.length && rtf[m] == ' ') m++
                            if (m < rtf.length && rtf[m] !in "\\{}") m++   // the fallback character
                            i = m
                            continue
                        }
                    }
                    i = if (m < rtf.length && rtf[m] == ' ') m + 1 else m
                }
            } else {
                if (skip == null && c != '\r' && c != '\n') out.append(c)
                i++
            }
        }
        return out.toString().trim()
    }

    /** An older kind of text box, stored as RTF with its frame. */
    private fun rtfBox(body: Msg, scale: Double): Text? {
        val t = unrtf(body.text(6))
        if (t.isEmpty()) return null
        val f = message(body.bytes(3) ?: body.b(2))
        val (x, y) = vec(f.bytes(1), 2)
        val w = vec(f.bytes(2), 2)[0]
        return Text(x * scale, y * scale, t, 11.0, doubleArrayOf(0.1, 0.1, 0.1), max(60.0, w * scale))
    }

    /** A straight connector between two points. */
    private fun line(body: Msg, scale: Double): Shape {
        val geometry = body.sub(20)
        val (x0, y0) = vec(geometry.bytes(2), 2)
        val (x1, y1) = vec(geometry.sub(3).bytes(1), 2)
        val style = body.sub(32)
        val width = vec(body.bytes(32), 1)[0].takeIf { it != 0.0 } ?: 2.0
        val color = vec(style.sub(3).bytes(1), 3).map { it.coerceIn(0.0, 1.0) }.toDoubleArray()
        return Shape("line", x0 * scale, y0 * scale, x1 * scale, y1 * scale, color, max(0.3, width * scale))
    }

    private fun made(kind: Int, body: Msg): Long = try {
        val stamp = body.b(if (kind == 1) 5 else 7)
        message(message(stamp).b(1)).num(1)
    } catch (e: Exception) {
        0
    }

    /** A page's content is stored under its own id with the last byte one higher. */
    fun notesId(pageId: String): String {
        val n = pageId.takeLast(2).toIntOrNull(16) ?: return pageId
        return pageId.dropLast(2) + "%02X".format((n + 1) and 0xff)
    }

    /**
     * Read a .goodnotes file: the document, its title and what was read. [sizes] gives a PDF's page
     * sizes in points (the app reads them with the system's renderer).
     */
    fun load(file: java.io.File, sizes: (Source) -> List<Pair<Double, Double>>): Triple<Document, String, Stats> = load(OpenFile(file), sizes)

    fun load(src: Source, sizes: (Source) -> List<Pair<Double, Double>>): Triple<Document, String, Stats> {
        val z = try {
            ZipReader.open(src)
        } catch (e: Exception) {
            throw GoodnotesError(tr("不是 Goodnotes 文档"))
        }
        val names = z.names.toSet()
        if ("index.events.pb" !in names || "index.notes.pb" !in names) throw GoodnotesError(tr("不是 Goodnotes 文档"))
        var title: String? = null
        val templates = HashMap<String, Triple<String, Long, DoubleArray?>>()   // id -> (attachment, PDF page number from 1, size)
        val pages = LinkedHashMap<String, Array<String?>>()                      // id -> (template, sort key)
        for (record in records(z.bytes("index.events.pb"))) {
            for (f in runCatching { fields(record) }.getOrDefault(emptyList())) {
                if (f.wire != 2 || f.number == 1) continue
                val m = runCatching { message(f.value as ByteArray) }.getOrNull() ?: continue
                when (f.number) {
                    30 -> title = m.sub(2).text(1).ifEmpty { title }
                    2 -> {
                        val size = vec(m.bytes(8), 2)
                        templates[m.text(2)] = Triple(m.text(4), m.num(5, 1), if (size[0] > 0 && size[1] > 0) size else null)
                    }
                    54 -> {
                        val page = pages.getOrPut(m.text(2)) { arrayOf(null, "") }
                        val template = m.sub(3).text(1)
                        val key = m.sub(4).text(1)
                        if (template.isNotEmpty()) page[0] = template
                        if (key.isNotEmpty()) page[1] = key
                    }
                    55 -> {
                        // A page that was moved gets a new sort key.
                        val key = m.sub(3).text(1)
                        val page = pages[m.text(2)]
                        if (page != null && key.isNotEmpty()) page[1] = key
                    }
                }
            }
        }
        val files = HashMap<String, String>()
        for (record in records(z.bytes("index.notes.pb"))) {
            val m = message(record)
            files[m.text(1)] = m.text(2)
        }
        val attachments = HashMap<String, String>()
        if ("index.attachments.pb" in names) for (record in records(z.bytes("index.attachments.pb"))) {
            val m = message(record)
            attachments[m.text(1)] = m.text(2)
        }
        val pdfs = HashMap<String, Pair<PdfData, List<Pair<Double, Double>>>?>()
        fun pdfFor(attachment: String): Pair<PdfData, List<Pair<Double, Double>>>? = pdfs.getOrPut(attachment) {
            val name = attachments[attachment]
            if (name == null || name !in names) return@getOrPut null
            val data = z.sourceOf(name)
            if (data.size < 5 || String(data.read(0, 5), Charsets.ISO_8859_1) != "%PDF-") return@getOrPut null
            runCatching { val p = PdfData(data); Pair(p, sizes(data)) }.getOrNull()
        }

        val doc = Document()
        doc.pages = ArrayList()
        val stats = Stats()
        for ((pageId, entry) in pages.entries.sortedBy { it.value[1] ?: "" }) {
            val (attachment, number, size) = templates[entry[0] ?: ""] ?: Triple("", 1L, null)
            val pdf = if (attachment.isNotEmpty()) pdfFor(attachment) else null
            val page: Page
            val scale: Double
            if (pdf != null && number >= 1 && number <= pdf.second.size) {
                val (w, h) = pdf.second[(number - 1).toInt()]
                page = Page(w, h, pdf = pdf.first, pdfPage = (number - 1).toInt())
                scale = if (size != null && size[0] > 0) w / size[0] else FALLBACK_SCALE
            } else if (size != null) {
                scale = FALLBACK_SCALE
                page = Page(size[0] * scale, size[1] * scale)
            } else {
                // No template and no size: a whiteboard, which has no edges in Inkpad either.
                scale = FALLBACK_SCALE
                page = Page(BOARD_SIZE.first, BOARD_SIZE.second, "dots", board = true)
            }
            val name = files[notesId(pageId)] ?: files[pageId]
            val elements = LinkedHashMap<String, Pair<Int, Msg>>()   // element id -> (kind, body); a later record replaces an earlier one
            if (name != null && name in names) {
                for (record in records(z.bytes(name))) {
                    try {
                        val fs = fields(record)
                        val first = fs.firstOrNull() ?: continue
                        if (first.wire != 2 || (first.number == 1 && fs.size > 1)) continue   // the header that precedes each element
                        val body = message(first.value as ByteArray)
                        if (first.number == 1 && body.bytes(1) == null) continue
                        elements[body.text(1)] = Pair(first.number, body)
                    } catch (e: Exception) {
                        continue
                    }
                }
            }
            // Elements are numbered as they were made; later ones lie on top of earlier ones.
            for ((kind, body) in elements.values.sortedBy { made(it.first, it.second) }) {
                if (body.num(14) != 0L || body.bytes(14) != null) continue   // erased or removed
                try {
                    when (kind) {
                        7 -> {
                            val s = strokes(body, scale)
                            page.items.addAll(s)
                            stats.strokes += s.size
                        }
                        21, 20, 8 -> {
                            val t = when (kind) {
                                21 -> textBox(body, scale)
                                20 -> sticky(body, scale)
                                else -> rtfBox(body, scale)
                            }
                            if (t != null) {
                                page.items.add(t)
                                stats.texts++
                            }
                        }
                        11 -> {
                            // Handwriting that Goodnotes shows typeset as a formula. Inkpad cannot typeset, so the handwriting is kept.
                            if (body.num(6) != 0L || body.bytes(6) != null) continue   // a conversion that was undone
                            for (part in body.all(11)) {
                                val s = strokes(message(part as ByteArray), scale, 1, 2, frame(body))
                                page.items.addAll(s)
                                stats.strokes += s.size
                            }
                            stats.formulas++
                        }
                        1 -> {
                            if (vec(body.sub(2).bytes(2), 2).max() < 1) continue   // a speck too small to see, which Goodnotes leaves as a marker
                            val im = image(body, scale, z, attachments, names)
                            if (im != null) {
                                page.items.add(im)
                                stats.images++
                            } else stats.skipped++
                        }
                        9 -> {}   // a region marked for some tool; it shows nothing on the page
                        22 -> {
                            page.items.add(line(body, scale))
                            stats.shapes++
                        }
                        else -> stats.skipped++
                    }
                } catch (e: Exception) {
                    stats.skipped++
                }
            }
            doc.pages.add(page)
        }
        // Inkpad treats a document as either pages or whiteboards, going by its first page.
        if (doc.pages.any { it.board } && !doc.pages.all { it.board }) doc.pages = ArrayList(doc.pages.filter { it.board == doc.pages[0].board })
        if (doc.pages.isEmpty()) throw GoodnotesError(tr("这个 Goodnotes 文档里没有可以读取的页面"))
        stats.pages = doc.pages.size
        doc.rev = 1   // not on disk yet
        return Triple(doc, title ?: "Goodnotes", stats)
    }

    /** A picture placed on the page: its frame and the attachment that holds the file. */
    private fun image(body: Msg, scale: Double, z: ZipReader, attachments: Map<String, String>, names: Set<String>): Image? {
        val name = attachments[body.text(4)] ?: return null
        if (name !in names) return null
        val f = body.sub(2)
        val (x, y) = vec(f.bytes(1), 2)
        val (w, h) = vec(f.bytes(2), 2)
        if (w <= 0 || h <= 0) return null
        val codec = Platform.images ?: return null
        val png = runCatching { codec.toPng(z.bytes(name), IMAGE_LIMIT) }.getOrNull() ?: return null
        return Image(x * scale, y * scale, w * scale, h * scale, ImageData(png.source()))
    }

    // -- writing --------------------------------------------------------------------------------------
    //
    // Experimental. The records below copy the shape of what Goodnotes itself writes, but the meaning
    // of several of their fields (the counters and hashes that look like sync clocks) is a guess, and
    // no Goodnotes app was available to open the result. What is certain is only that load() reads it.

    private val random = SecureRandom()

    private fun encVarint(n0: Long, out: ByteArrayOutputStream) {
        var n = n0
        while (true) {
            val b = (n and 0x7f).toInt()
            n = n ushr 7
            if (n != 0L) out.write(b or 0x80) else {
                out.write(b)
                return
            }
        }
    }

    /** A float32 field's value: a number to be written as a float, not an int. */
    private class F32(val v: Double)

    private fun fl(v: Double) = F32(v)

    /** One field: an int is a varint, a float a float32, bytes or a string length-delimited. */
    private fun f(number: Int, value: Any): ByteArray {
        val out = ByteArrayOutputStream()
        when (value) {
            is Boolean -> { encVarint((number shl 3).toLong(), out); encVarint(if (value) 1 else 0, out) }
            is Int -> { encVarint((number shl 3).toLong(), out); encVarint(value.toLong(), out) }
            is Long -> { encVarint((number shl 3).toLong(), out); encVarint(value, out) }
            is F32 -> {
                encVarint(((number shl 3) or 5).toLong(), out)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value.v.toFloat()).array())
            }
            else -> {
                val bytes = if (value is String) value.toByteArray(Charsets.UTF_8) else value as ByteArray
                encVarint(((number shl 3) or 2).toLong(), out)
                encVarint(bytes.size.toLong(), out)
                out.write(bytes)
            }
        }
        return out.toByteArray()
    }

    private fun f64(number: Int, value: Double): ByteArray {
        val out = ByteArrayOutputStream()
        encVarint(((number shl 3) or 1).toLong(), out)
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(value).array())
        return out.toByteArray()
    }

    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    private fun record(m: ByteArray): ByteArray = ByteArrayOutputStream().also {
        encVarint(m.size.toLong(), it)
        it.write(m)
    }.toByteArray()

    private fun rnd32() = random.nextInt().toLong() and 0xffffffffL
    private fun stamp(counter: Long = 1) = cat(f(1, counter), f(2, rnd32()))
    private fun newId() = UUID.randomUUID().toString().uppercase()

    /** A constant-width path in the simplest of the three layouts, stored uncompressed. */
    private fun packStroke(points: List<DoubleArray>, width: Double): ByteArray {
        val ops = listOf(0) + List(points.size - 1) { 1 }
        val sig = "vuA(v)A(S(uu))A(S(uuuu))vA(f)\u0000".toByteArray(Charsets.ISO_8859_1)
        val data = ByteBuffer.allocate(2 + 4 + 4 + ops.size * 2 + 4 + 8 + 4 + (points.size - 1) * 16 + 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        data.putShort(2).putFloat(width.toFloat())
        data.putInt(ops.size)
        for (op in ops) data.putShort(op.toShort())
        data.putInt(1).putFloat(points[0][0].toFloat()).putFloat(points[0][1].toFloat())
        data.putInt(points.size - 1)
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            // A straight piece as a curve whose control point is its middle.
            data.putFloat(((x0 + x1) / 2).toFloat()).putFloat(((y0 + y1) / 2).toFloat()).putFloat(x1.toFloat()).putFloat(y1.toFloat())
        }
        data.putShort(1).putInt(0)
        val body = data.array().copyOf(data.position())
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.put(0x74).put(0x70).put(0x6c).put(0).putInt(8 + sig.size + body.size)
        val raw = cat(head.array(), sig, body)
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.size).array()
        return cat("bv4-".toByteArray(), size, raw, "bv4$".toByteArray())
    }

    /**
     * Write a document as a .goodnotes file (experimental, see above). Each page's paper, PDF, pictures,
     * shapes and text are in [background], one PDF of every page without its strokes (the app makes
     * it); pen and highlighter strokes are written as strokes, at their average width.
     */
    fun save(doc: Document, title: String, background: Source, out: OutputStream) {
        val now = Clock.now()
        val device = (rnd32() shl 30) or (rnd32() ushr 2)
        var clock = (now * 1000).toLong()
        fun tick() = ++clock
        val docId = newId()
        val attachment = newId()
        val up = 1 / FALLBACK_SCALE   // Goodnotes lays pages out this much larger than their PDF
        val pdf = background.bytes()
        val boxes = doc.pages.map { page ->
            val (x0, y0, x1, y1) = pageBox(page)
            val k = min(1.0, 14400 / max(x1 - x0, y1 - y0))
            doubleArrayOf(x0, y0, k, (x1 - x0) * k, (y1 - y0) * k)
        }
        val events = arrayListOf(record(cat(f(1, docId), f(30, cat(
            f(1, docId), f(2, cat(f(1, title), f(2, stamp()))), f(6, cat(f(1, "P"), f(2, stamp()))),
            f(9, "en_US"), f64(10, now * 1000), f(11, newId()), f(13, device), f(14, tick()),
            f(17, ByteArray(0)), f(18, ByteArray(0)), f(19, f(2, stamp())), f(20, 24))))))
        events.add(record(cat(f(1, attachment), f(6, cat(
            f(1, attachment), f(2, attachment), f(5, pdf.size), f(6, docId), f64(10, now * 1000),
            f(11, newId()), f(12, cat(f(1, 1), f(2, 1))), f(14, device), f(15, tick()), f(16, 24))))))
        val notesIndex = ArrayList<ByteArray>()
        val files = LinkedHashMap<String, ByteArray>()
        doc.pages.forEachIndexed { n, page ->
            val (bx, by, k, pw, ph) = boxes[n]
            val template = newId()
            var pageId = newId()
            pageId = pageId.dropLast(2) + "%02X".format(pageId.takeLast(2).toInt(16) and 0xfe)   // so that +1 does not wrap
            events.add(record(cat(f(1, template), f(2, cat(
                f(1, docId), f(2, template), f(4, attachment), f(5, n + 1), f(6, 1),
                f(8, cat(f(1, fl(pw * up)), f(2, fl(ph * up)))), f64(10, now * 1000), f(11, newId()),
                f(12, f(2, stamp())), f(13, f(2, stamp())), f(15, device), f(16, tick()),
                f(17, cat(f(1, 1), f(2, stamp()))), f(19, f(2, stamp())), f(21, 24))))))
            events.add(record(cat(f(1, pageId), f(54, cat(
                f(1, docId), f(2, pageId), f(3, cat(f(1, template), f(2, stamp()))),
                f(4, cat(f(1, "P" + n.toString().padStart(6, '0')), f(2, stamp()))), f64(10, now * 1000), f(11, newId()),
                f(13, device), f(14, tick()), f(15, 24))))))
            val body = ArrayList<ByteArray>()
            var counter = 1_000_000L
            for (it in page.items) {
                if (it !is Stroke || it.pts.isEmpty()) continue
                val pts = it.pts.map { p -> doubleArrayOf((p[0] - bx) * k * up, (p[1] - by) * k * up) }.toMutableList()
                if (pts.size == 1) pts.add(doubleArrayOf(pts[0][0] + 0.01, pts[0][1]))
                val width = it.pts.sumOf { p -> it.wAt(p[2]) } / it.pts.size * k * up
                val sid = newId()
                val st = stamp(2)
                counter++
                body.add(record(cat(f(1, sid), f(2, st), f(6, sid), f(8, device), f(9, counter), f(14, 5381), f(16, 24))))
                val color = cat(f(1, fl(it.color[0])), f(2, fl(it.color[1])), f(3, fl(it.color[2])), f(4, fl(if (it.tool == "hl") 0.5 else 1.0)))
                body.add(record(f(7, cat(f(1, sid), f(2, packStroke(pts, width)), f(4, color), f(6, ByteArray(0)),
                    f(7, f(1, stamp(pts.size.toLong()))), f(9, ByteArray(0)), f(15, st), f(20, ByteArray(0)), f(21, 24)))))
            }
            val nid = notesId(pageId)
            files["notes/$nid"] = cat(*body.toTypedArray())
            notesIndex.add(record(cat(f(1, nid), f(2, "notes/$nid"))))
        }
        val z = ZipWriter(out)
        z.add("document.info.pb", ByteArray(0))
        z.add("schema.pb", f(1, 25))
        z.add("index.events.pb", cat(*events.toTypedArray()))
        z.add("index.notes.pb", cat(*notesIndex.toTypedArray()))
        z.add("index.search.pb", ByteArray(0))
        z.add("index.attachments.pb", record(cat(f(1, attachment), f(2, "attachments/$attachment"))))
        z.add("attachments/$attachment", pdf)
        for ((name, data) in files) z.add(name, data)
        z.finish()
    }
}
