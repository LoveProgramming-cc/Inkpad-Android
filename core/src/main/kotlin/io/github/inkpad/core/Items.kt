package io.github.inkpad.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// The four kinds of things on a page: strokes, shapes, text boxes and pictures. Translated from
// B:model.py (W:src/core/items.js). Units are PDF points; colours are r, g, b in 0..1; points are
// x, y, pressure. Items are compared by identity: two equal strokes are still two strokes.

typealias Color = DoubleArray

fun rgb(r: Double, g: Double, b: Double): Color = doubleArrayOf(r, g, b)

const val HL_ALPHA = 0.4
const val TAU = 2 * Math.PI

/** round(v, 2) as the files want it. */
fun round2(v: Double) = pyRound(v * 100) / 100.0

/** Rotate a point with its w × h page by k quarter turns clockwise. */
fun rotPt(k: Int, w: Double, h: Double, x: Double, y: Double): Pair<Double, Double> = when (k) {
    1 -> Pair(h - y, x)
    2 -> Pair(w - x, h - y)
    3 -> Pair(y, w - x)
    else -> Pair(x, y)
}

fun rotBox(k: Int, w: Double, h: Double, box: Box): Box {
    val (ax, ay) = rotPt(k, w, h, box[0], box[1])
    val (bx, by) = rotPt(k, w, h, box[2], box[3])
    return doubleArrayOf(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
}

/** Split a polyline into the "pen down" runs of a dash pattern (on == 0 gives dots). */
fun dashRuns(pts: List<Pt>, on: Double, off: Double): List<List<Pt>> {
    val runs = ArrayList<List<Pt>>()
    var run = arrayListOf(pts[0])
    var drawing = true
    var left = on
    var a = pts[0]
    for (i in 1 until pts.size) {
        val b = pts[i]
        val seg = hypot(b[0] - a[0], b[1] - a[1])
        var pos = 0.0
        while (seg - pos > left) {
            pos += left
            val t = pos / seg
            val q = doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
            if (drawing) {
                run.add(q)
                runs.add(run)
                run = ArrayList()
            } else {
                run = arrayListOf(q)
            }
            drawing = !drawing
            left = if (drawing) on else off
        }
        left -= seg - pos
        if (drawing) run.add(b)
        a = b
    }
    if (drawing && run.isNotEmpty()) runs.add(run)
    return runs
}

/** A picture's PNG bytes. Identity matters: a file keeps one copy of each. */
class ImageData(val source: Source) {
    val size get() = source.size
    private var sha: String? = null

    fun sha256(): String = sha ?: Hash.sha256(source).also { sha = it }

    /** Turned copies, made once (encoding a picture again is slow). */
    internal val turned = HashMap<Int, ImageData>()

    fun sameBytes(other: ImageData) = this === other || (size == other.size && sha256() == other.sha256())
}

/** What a saved picture is called when it is written: the file keeps one copy of each. */
class SaveCtx {
    val images = ArrayList<ImageData>()
    private val seen = java.util.IdentityHashMap<ImageData, Int>()
    fun image(data: ImageData): Int = seen.getOrPut(data) { images.add(data); images.size - 1 }
}

sealed class Item : Shaped {
    /** The permanent id, given on first use. Syncing merges two versions of a page by these ids,
     *  so an item keeps its id for as long as it is the same object. */
    var id: String? = null
    val itemId: String get() = id ?: Ids.short().also { id = it }

    override var bbox: Box = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        protected set

    abstract fun updateBbox()
    abstract fun hit(x: Double, y: Double, r: Double): Boolean

    /** A copy scaled by s about (ox, oy), then moved by (dx, dy). */
    abstract fun transformed(s: Double, ox: Double, oy: Double, dx: Double, dy: Double): Item

    /** A copy turned k quarter turns with its w × h page. */
    abstract fun rotated(k: Int, w: Double, h: Double): Item

    abstract fun toData(ctx: SaveCtx): LinkedHashMap<String, Any?>

    companion object {
        fun fromData(d: Map<String, Any?>, images: List<ImageData>): Item = when (d["k"]) {
            "s" -> Stroke.fromData(d)
            "h" -> Shape.fromData(d)
            "t" -> Text.fromData(d)
            "i" -> Image.fromData(d, images)
            else -> throw IllegalArgumentException("unknown item kind ${d["k"]}")
        }

        internal fun color(v: Any?): Color = v.point()
    }
}

class Stroke(
    val tool: String,            // "ball" | "fountain" | "brush" | "hl" | "tape"
    var color: Color,
    val width: Double,
    pts: List<Pt> = emptyList(),
    val sens: Double = 1.0,      // pressure sensitivity, 1.0 is the default response
    val dash: Int = 0,           // 0 solid, 1 dashed, 2 dotted
) : Item() {
    override val kind get() = "s"
    var open = false             // a tape that has been lifted: it shows what it covers
    val pts: MutableList<Pt> = ArrayList(pts)

    init {
        if (this.pts.isNotEmpty()) updateBbox()
    }

    fun wAt(p: Double): Double = when (tool) {
        "fountain" -> width * max(0.15, 1 + sens * 1.2 * (p - 0.5))
        "brush" -> width * max(0.12, 1.4 + sens * 2.2 * (p - 0.5))
        else -> width
    }

    fun pad() = wAt(1.0) / 2 + 1

    override fun updateBbox() {
        bbox = Geom.bboxOf(pts, pad())
    }

    override fun polyline(): List<Pt> = pts
    override fun sample(): List<Pt> = Geom.decimate(pts, 40)
    override fun hit(x: Double, y: Double, r: Double) = Geom.polylineHit(pts, x, y, r + width / 2)

    override fun transformed(s: Double, ox: Double, oy: Double, dx: Double, dy: Double): Stroke {
        val moved = pts.map { doubleArrayOf(ox + (it[0] - ox) * s + dx, oy + (it[1] - oy) * s + dy, it[2]) }
        return Stroke(tool, color, width * s, moved, sens, dash).also { it.open = open }
    }

    override fun rotated(k: Int, w: Double, h: Double): Stroke =
        Stroke(tool, color, width, pts.map { val (x, y) = rotPt(k, w, h, it[0], it[1]); doubleArrayOf(x, y, it[2]) }, sens, dash)

    override fun toData(ctx: SaveCtx) = linkedMapOf<String, Any?>(
        "k" to "s", "t" to tool, "c" to color.toList(), "w" to width, "ps" to sens, "d" to dash, "q" to packPoints(pts),
    ).also { if (open) it["o"] = true }

    companion object {
        fun fromData(d: Map<String, Any?>): Stroke {
            val pts = if ("q" in d) unpackPoints(d["q"] as String) else {
                // files from before version 3 list the numbers
                val p = d["p"].arr().map { it.num() }
                (0 until p.size / 3).map { doubleArrayOf(p[3 * it], p[3 * it + 1], p[3 * it + 2]) }
            }
            return Stroke(d["t"] as String, color(d["c"]), d["w"].num(), pts, d["ps"]?.num() ?: 1.0, d["d"]?.num()?.toInt() ?: 0)
                .also { it.open = d["o"] == true }
        }
    }
}

class Shape(
    val shape: String,           // "line" | "arrow" | "rect" | "ellipse"
    val x0: Double, val y0: Double, val x1: Double, val y1: Double,
    var color: Color,
    val width: Double,
) : Item() {
    override val kind get() = "h"

    init {
        updateBbox()
    }

    override fun updateBbox() {
        val pad = width / 2 + if (shape == "arrow") max(8.0, width * 4) else 1.0
        bbox = doubleArrayOf(min(x0, x1) - pad, min(y0, y1) - pad, max(x0, x1) + pad, max(y0, y1) + pad)
    }

    override fun polyline(): List<Pt> {
        if (shape == "ellipse") {
            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            val rx = abs(x1 - x0) / 2
            val ry = abs(y1 - y0) / 2
            return (0..40).map { doubleArrayOf(cx + rx * cos(TAU * it / 40), cy + ry * sin(TAU * it / 40)) }
        }
        val corners = if (shape == "rect") listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1), doubleArrayOf(x0, y0))
        else listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y1))
        val out = ArrayList<Pt>()
        for (k in 0 until corners.size - 1) {
            val a = corners[k]
            val b = corners[k + 1]
            for (i in 0 until 10) out.add(doubleArrayOf(a[0] + (b[0] - a[0]) * i / 10, a[1] + (b[1] - a[1]) * i / 10))
        }
        out.add(corners.last())
        return out
    }

    override fun sample() = polyline()
    override fun hit(x: Double, y: Double, r: Double) = Geom.polylineHit(polyline(), x, y, r + width / 2)

    override fun transformed(s: Double, ox: Double, oy: Double, dx: Double, dy: Double) =
        Shape(shape, ox + (x0 - ox) * s + dx, oy + (y0 - oy) * s + dy, ox + (x1 - ox) * s + dx, oy + (y1 - oy) * s + dy, color, width * s)

    override fun rotated(k: Int, w: Double, h: Double): Shape {
        val (ax, ay) = rotPt(k, w, h, x0, y0)
        val (bx, by) = rotPt(k, w, h, x1, y1)
        return Shape(shape, ax, ay, bx, by, color, width)
    }

    override fun toData(ctx: SaveCtx) = linkedMapOf<String, Any?>(
        "k" to "h", "t" to shape, "c" to color.toList(), "w" to width, "r" to listOf(x0, y0, x1, y1).map(::round2),
    )

    companion object {
        fun fromData(d: Map<String, Any?>): Shape {
            val r = d["r"].point()
            return Shape(d["t"] as String, r[0], r[1], r[2], r[3], color(d["c"]), d["w"].num())
        }
    }
}

/** Shared behaviour of rectangular items (text, images). */
sealed class BoxItem : Item() {
    override fun polyline(): List<Pt> {
        val (x0, y0, x1, y1) = bbox
        return listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1), doubleArrayOf(x0, y0))
    }

    override fun sample(): List<Pt> {
        val (x0, y0, x1, y1) = bbox
        return listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1), doubleArrayOf((x0 + x1) / 2, (y0 + y1) / 2))
    }

    override fun hit(x: Double, y: Double, r: Double) = false
}

class Text(
    val x: Double, val y: Double,
    val text: String,
    val size: Double,
    var color: Color,
    val wrap: Double = 0.0,
    val bg: Color? = null,       // a sticky note: the text sits on a coloured slip of this colour
) : BoxItem() {
    override val kind get() = "t"

    init {
        updateBbox()
    }

    override fun updateBbox() {
        var (w, h) = Ptext.measure(text, size, wrap)
        val pad = if (bg != null) size * 0.7 else 2.0
        if (bg != null && wrap != 0.0) w = max(w, wrap)   // a note keeps its width however little is written on it
        bbox = doubleArrayOf(x - pad, y - pad, x + w + pad, y + h + pad)
    }

    override fun transformed(s: Double, ox: Double, oy: Double, dx: Double, dy: Double) =
        Text(ox + (x - ox) * s + dx, oy + (y - oy) * s + dy, text, size * s, color, wrap * s, bg)

    override fun rotated(k: Int, w: Double, h: Double): Text {
        val box = rotBox(k, w, h, bbox)
        val pad = x - bbox[0]
        return Text(box[0] + pad, box[1] + pad, text, size, color, wrap, bg)
    }

    override fun toData(ctx: SaveCtx) = linkedMapOf<String, Any?>(
        "k" to "t", "x" to round2(x), "y" to round2(y), "s" to text, "z" to size, "c" to color.toList(), "wr" to wrap,
    ).also { if (bg != null) it["bg"] = bg.toList() }

    companion object {
        fun fromData(d: Map<String, Any?>): Text {
            val bg = d["bg"]?.arr()?.takeIf { it.isNotEmpty() }?.map { it.num() }?.toDoubleArray()
            return Text(d["x"].num(), d["y"].num(), d["s"] as String, d["z"].num(), color(d["c"]), d["wr"]?.num() ?: 0.0, bg)
        }
    }
}

class Image(val x: Double, val y: Double, val w: Double, val h: Double, val data: ImageData) : BoxItem() {
    override val kind get() = "i"

    init {
        updateBbox()
    }

    override fun updateBbox() {
        bbox = doubleArrayOf(x, y, x + w, y + h)
    }

    override fun transformed(s: Double, ox: Double, oy: Double, dx: Double, dy: Double) =
        Image(ox + (x - ox) * s + dx, oy + (y - oy) * s + dy, w * s, h * s, data)

    override fun rotated(k: Int, w: Double, h: Double): Image {
        val q = ((k % 4) + 4) % 4
        val box = rotBox(q, w, h, bbox)
        val turned = if (q == 0) data else data.turned.getOrPut(q) {
            val codec = Platform.images ?: error("no image codec installed")
            ImageData(codec.rotate(data.source.bytes(), q).source())
        }
        return Image(box[0], box[1], box[2] - box[0], box[3] - box[1], turned)
    }

    override fun toData(ctx: SaveCtx) = linkedMapOf<String, Any?>(
        "k" to "i", "r" to listOf(x, y, w, h).map(::round2), "img" to ctx.image(data),
    )

    companion object {
        fun fromData(d: Map<String, Any?>, images: List<ImageData>): Image {
            val r = d["r"].point()
            return Image(r[0], r[1], r[2], r[3], images[d["img"].num().toInt()])
        }
    }
}
