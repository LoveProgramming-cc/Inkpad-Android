package io.github.inkpad.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.inkpad.core.Box
import io.github.inkpad.core.Geom
import io.github.inkpad.core.Image
import io.github.inkpad.core.ImageData
import io.github.inkpad.core.Item
import io.github.inkpad.core.Page
import io.github.inkpad.core.Shape
import io.github.inkpad.core.Stroke
import io.github.inkpad.core.Text
import io.github.inkpad.core.tr
import io.github.inkpad.render.Env
import io.github.inkpad.render.argb
import io.github.inkpad.render.drawItem
import io.github.inkpad.render.drawShape
import io.github.inkpad.render.drawStroke
import io.github.inkpad.render.inkOn
import io.github.inkpad.render.renderPage
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// What the tools do (B:canvas.py _down, _move, _up, _erase, the selection, text, laser;
// W:src/editor/tools.js). A tool is a little state machine kept in `op`.

/** The work in progress: kind is "stroke", "erase", "shape", "lasso", "move", "ruler" or "laser". */
class Op(var kind: String, val page: Page?, val i: Int) {
    var item: Item? = null
    var smooth = Pair(0.0, 0.0)
    var rule: DoubleArray? = null
    var anchor = Pair(0.0, 0.0)
    var baked = 0
    var fixed = false
    // erase
    var pos = Pair(0.0, 0.0)
    var last = Pair(0.0, 0.0)
    var before: List<Item> = emptyList()
    var changed = false
    // lasso
    val pts = ArrayList<DoubleArray>()
    var rect = false
    // laser
    var trail = false
    // ruler
    var grab = Pair(0.0, 0.0)
    // move
    var start = Pair(0.0, 0.0)
    var origin = Pair(0.0, 0.0)
    var copy = false
    var scaling = false
    var s = 1.0
    var dx = 0.0
    var dy = 0.0
    var raster: Raster? = null
    var at = Pair(0.0, 0.0)
    var slack = 0.0
    var live = false
}

/** A picture of part of a page, and where it is (page points). */
class Raster(val bitmap: Bitmap, val rect: DoubleArray)

class Selection(val page: Page, val items: List<Item>, val bbox: Box)

private const val ERASER_RING = 0xCC4D4D4D.toInt()
private const val ERASER_FILL = 0x80FFFFFF.toInt()

fun lassoKind(it: Item) = when (it.kind) { "s" -> "ink"; "h" -> "shape"; "i" -> "image"; else -> "text" }

fun eraseKind(it: Item): String? = when (it) {
    is Stroke -> if (it.tool == "hl") "hl" else "pen"
    is Shape -> "shape"
    else -> null
}

private fun stroke(color: Int, width: Double) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = width.toFloat()
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    this.color = color
}

private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

// -- the overlay: what is being done now ---------------------------------------------------------

/** Everything that floats over the pages: the stroke being written, the selection, the laser, the ruler. */
internal fun PageCanvas.drawOverlay(c: Canvas) {
    val o = op
    if (o != null && o.page != null) {
        val idx = index(o.page)
        if (idx != null) {
            c.save()
            c.scale(dev.toFloat(), dev.toFloat())
            if (!board) {
                val r = pageRect(idx)
                c.clipRect(r[0].toFloat(), r[1].toFloat(), (r[0] + r[2]).toFloat(), (r[1] + r[3]).toFloat())
            }
            c.scale((1 / dev).toFloat(), (1 / dev).toFloat())
            when (o.kind) {
                "stroke" -> snapStroke(c, o, idx)
                "shape" -> {
                    c.save()
                    drawShape(c, pageEnv(c, idx), o.item as Shape)
                    c.restore()
                }
                "erase" -> {
                    c.save()
                    c.scale(dev.toFloat(), dev.toFloat())
                    val r = eraserR * pageRect(idx)[2] / o.page.w
                    c.drawCircle(o.pos.first.toFloat(), o.pos.second.toFloat(), max(r, 2.0).toFloat(), fill(ERASER_FILL))
                    c.drawCircle(o.pos.first.toFloat(), o.pos.second.toFloat(), max(r, 2.0).toFloat(), stroke(ERASER_RING, 1.0))
                    c.restore()
                }
                "lasso" -> snapLasso(c, o, idx)
                "move" -> snapMove(c, o, idx)
            }
            c.restore()
        }
    }
    c.save()
    c.scale(dev.toFloat(), dev.toFloat())
    if (sel != null) snapSelection(c)
    if (hover != null && op == null && !penDown) drawHover(c)
    if (laserTrails.isNotEmpty() || laserDot != null) drawLaser(c)
    if (ruler != null) drawRuler(c)
    if (pull != 0.0) drawPull(c)
    c.restore()
}

/** Put the canvas in page units for page i (device pixels underneath); returns what the drawing
 *  code needs. The origin is subtracted in doubles, never in the matrix. */
internal fun PageCanvas.pageEnv(c: Canvas, i: Int): Env {
    val page = doc!!.pages[i]
    val r = pageRect(i)
    val k = r[2] / page.w
    c.scale((dev * k).toFloat(), (dev * k).toFloat())
    return Env(-r[0] / k, -r[1] / k, 1 / (k * dev), inkOn(page))
}

/** A path along page points, in view dp. Returns the dp per page point. */
private fun PageCanvas.pagePath(i: Int, pts: List<DoubleArray>, smooth: Boolean = false, close: Boolean = false): Pair<Path, Double> {
    val r = pageRect(i)
    val k = r[2] / doc!!.pages[i].w
    val p = Path()
    p.moveTo((r[0] + pts[0][0] * k).toFloat(), (r[1] + pts[0][1] * k).toFloat())
    val n = pts.size
    if (smooth && n > 2) {
        for (j in 1 until n - 1) {
            val cx = pts[j][0]
            val cy = pts[j][1]
            p.quadTo((r[0] + cx * k).toFloat(), (r[1] + cy * k).toFloat(), (r[0] + (cx + pts[j + 1][0]) / 2 * k).toFloat(), (r[1] + (cy + pts[j + 1][1]) / 2 * k).toFloat())
        }
        p.lineTo((r[0] + pts[n - 1][0] * k).toFloat(), (r[1] + pts[n - 1][1] * k).toFloat())
    } else {
        for (j in 1 until n) p.lineTo((r[0] + pts[j][0] * k).toFloat(), (r[1] + pts[j][1] * k).toFloat())
    }
    if (close) p.close()
    return Pair(p, k)
}

private fun PageCanvas.snapStroke(c: Canvas, o: Op, i: Int) {
    val item = o.item as Stroke
    val pts = item.pts
    if (item.tool == "hl" || item.tool == "tape") {
        c.save()
        c.scale(dev.toFloat(), dev.toFloat())
        val (p, k) = pagePath(i, if (pts.size > 1) pts else listOf(pts[0], pts[0]), smooth = true)
        c.drawPath(p, stroke(argb(item.color, if (item.tool == "hl") 0.4 else 1.0), max(item.width * k, 0.5)))
        c.restore()
        return
    }
    layer?.let { if (o.baked > 0) c.drawBitmap(it, 0f, 0f, null) }
    val tail = Stroke(item.tool, item.color, item.width, pts.subList(max(0, o.baked - 1), pts.size), item.sens, item.dash)
    c.save()
    drawStroke(c, pageEnv(c, i), tail)
    c.restore()
}

private fun PageCanvas.snapLasso(c: Canvas, o: Op, i: Int) {
    var pts: List<DoubleArray> = o.pts
    if (pts.size < 2) return
    if (o.rect) {
        val (ax, ay) = Pair(pts[0][0], pts[0][1])
        val (bx, by) = Pair(pts[1][0], pts[1][1])
        pts = listOf(doubleArrayOf(ax, ay), doubleArrayOf(bx, ay), doubleArrayOf(bx, by), doubleArrayOf(ax, by))
    }
    c.save()
    c.scale(dev.toFloat(), dev.toFloat())
    val (p, _) = pagePath(i, pts, close = o.rect)
    c.drawPath(p, stroke(ACCENT, 1.5).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f) })
    c.restore()
}

private fun PageCanvas.snapMove(c: Canvas, o: Op, i: Int) {
    val raster = o.raster ?: return
    val (ox, oy) = o.origin
    val (rx, ry, rw, rh) = raster.rect
    put(c, i, raster.bitmap, doubleArrayOf(ox + (rx - ox) * o.s + o.dx, oy + (ry - oy) * o.s + o.dy, rw * o.s, rh * o.s))
}

/** A picture put at a place on the page (rect: x, y, w, h in page points). */
private fun PageCanvas.put(c: Canvas, i: Int, bmp: Bitmap, rect: DoubleArray) {
    val r = pageRect(i)
    val k = r[2] / doc!!.pages[i].w
    c.save()
    c.scale(dev.toFloat(), dev.toFloat())
    c.drawBitmap(bmp, null, RectF((r[0] + rect[0] * k).toFloat(), (r[1] + rect[1] * k).toFloat(), (r[0] + (rect[0] + rect[2]) * k).toFloat(), (r[1] + (rect[1] + rect[3]) * k).toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
    c.restore()
}

/** Rasterise what [draw] draws over a page-space box, at screen resolution. */
internal fun PageCanvas.raster(i: Int, bbox: Box, draw: (Canvas, Env) -> Unit): Raster? {
    val page = doc!!.pages[i]
    val s = pageRect(i)[2] / page.w * dev
    val vis = visible(i)
    val x0 = floor(max(bbox[0], vis[0]) * s).toInt()
    val y0 = floor(max(bbox[1], vis[1]) * s).toInt()
    val x1 = ceil(min(bbox[2], vis[2]) * s).toInt()
    val y1 = ceil(min(bbox[3], vis[3]) * s).toInt()
    if (x1 <= x0 || y1 <= y0) return null
    val bmp = Bitmap.createBitmap(x1 - x0, y1 - y0, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(s.toFloat(), s.toFloat())
    draw(c, Env(x0 / s, y0 / s, 1 / s, inkOn(page)))
    return Raster(bmp, doubleArrayOf(x0 / s, y0 / s, (x1 - x0) / s, (y1 - y0) / s))
}

/** Where a hovering pen (or the mouse, once a pen has been seen) would write (A:CanvasExtras
 *  hovered): a crosshair, a small dot, the eraser's circle or a text cursor. */
private fun PageCanvas.drawHover(c: Canvas) {
    val (x, y) = hover ?: return
    val fx = x.toFloat()
    val fy = y.toFloat()
    when {
        tool == "eraser" -> {
            val loc = locate(x, y)
            val r = if (loc != null) max(eraserR * pageRect(loc.first)[2] / doc!!.pages[loc.first].w, 2.0) else 6.0
            c.drawCircle(fx, fy, r.toFloat(), fill(ERASER_FILL))
            c.drawCircle(fx, fy, r.toFloat(), stroke(ERASER_RING, 1.0))
        }
        tool == "text" || tool == "note" -> {
            val p = Path()
            p.moveTo(fx - 4, fy - 9); p.lineTo(fx + 4, fy - 9)
            p.moveTo(fx, fy - 9); p.lineTo(fx, fy + 9)
            p.moveTo(fx - 4, fy + 9); p.lineTo(fx + 4, fy + 9)
            c.drawPath(p, stroke(0xD91E1E1E.toInt(), 1.0))
        }
        !showCross && (tool == "pen" || tool == "hl") -> {
            // The mark the pen is about to leave: its colour, and its width at this zoom (A).
            val hl = tool == "hl"
            val r = max(2.5, (if (hl) hlWidth else penWidth) * zoom) / 2
            c.drawCircle(fx, fy, (r + 0.8).toFloat(), fill(0xE6FFFFFF.toInt()))
            c.drawCircle(fx, fy, r.toFloat(), fill(argb(if (hl) hlColor else penColor, if (hl) 0.55 else 1.0)))
            c.drawCircle(fx, fy, r.toFloat(), stroke(0x591A1A1A, 0.5))
        }
        !showCross || tool == "laser" -> {
            c.drawCircle(fx, fy, 3.1f, fill(0xE6FFFFFF.toInt()))
            c.drawCircle(fx, fy, 2.1f, fill(if (tool == "laser") argb(laserColor) else 0xF21A1A1A.toInt()))
        }
        else -> for ((w, col) in listOf(3.0 to 0xCCFFFFFF.toInt(), 1.0 to 0xE61A1A1A.toInt())) {
            val p = Path()
            p.moveTo(fx - 9, fy); p.lineTo(fx + 9, fy)
            p.moveTo(fx, fy - 9); p.lineTo(fx, fy + 9)
            c.drawPath(p, stroke(col, w).apply { strokeCap = Paint.Cap.BUTT })
        }
    }
}

private fun PageCanvas.drawLaser(c: Canvas) {
    val color = laserColor
    val alpha = laserAlpha
    for ((page, pts0) in laserTrails) {
        val i = index(page) ?: continue
        val pts = if (pts0.size == 1) listOf(pts0[0], doubleArrayOf(pts0[0][0] + 0.01, pts0[0][1])) else pts0
        val (p, _) = pagePath(i, pts, smooth = true)
        for ((width, paint) in listOf(13.0 to argb(color, 0.16 * alpha), 7.0 to argb(color, 0.45 * alpha), 3.6 to argb(color, alpha), 1.3 to argb(doubleArrayOf(1.0, 1.0, 1.0), 0.9 * alpha))) {
            c.drawPath(p, stroke(paint, width))
        }
    }
    val dot = laserDot ?: return
    val i = index(dot.first) ?: return
    val r = pageRect(i)
    val k = r[2] / dot.first.w
    val cx = (r[0] + dot.second * k).toFloat()
    val cy = (r[1] + dot.third * k).toFloat()
    for ((radius, paint) in listOf(15.0 to argb(color, 0.14), 10.0 to argb(color, 0.3), 6.0 to argb(color), 2.4 to argb(doubleArrayOf(1.0, 1.0, 1.0), 0.9))) {
        c.drawCircle(cx, cy, radius.toFloat(), fill(paint))
    }
}

private fun PageCanvas.drawRuler(c: Canvas) {
    val r = ruler ?: return
    val half = rulerLen()
    c.save()
    c.translate(r.x.toFloat(), r.y.toFloat())
    c.rotate(Math.toDegrees(r.a).toFloat())
    val body = fill(0xA8E6EBF2.toInt())
    val ink = fill(0xE6384052.toInt())
    c.drawRect((-half).toFloat(), (-RULER_W).toFloat(), half.toFloat(), RULER_W.toFloat(), body)
    for (y in listOf(-RULER_W, RULER_W - 1)) c.drawRect((-half).toFloat(), y.toFloat(), half.toFloat(), (y + 1).toFloat(), ink)
    // The scale is the page's own: a centimetre on the ruler is a centimetre on the printed page,
    // so it grows and shrinks with the zoom.
    val mm = 72 / 25.4 * shownZoom()
    val step = listOf(1, 5, 10, 50, 100, 500, 1000).firstOrNull { it * mm >= 3 } ?: 5000
    val every = listOf(10, 50, 100, 500, 1000, 5000).firstOrNull { it >= step && it * mm >= 26 } ?: 10000
    val start = -half + 12
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = 0xE6384052.toInt() }
    var n = 0
    while (start + n * mm <= half - 6) {
        val u = start + n * mm
        val long = n % (if (step < 10) step * 10 else every) == 0
        val size = if (long) 15.0 else if (n % (step * 5) == 0) 10.0 else 6.0
        c.drawRect((u - 0.5).toFloat(), (-RULER_W).toFloat(), (u + 0.5).toFloat(), (-RULER_W + size).toFloat(), ink)
        c.drawRect((u - 0.5).toFloat(), (RULER_W - size).toFloat(), (u + 0.5).toFloat(), RULER_W.toFloat(), ink)
        if (n % every == 0) c.drawText((n / 10).toString(), (u + 3).toFloat(), (-RULER_W + 13).toFloat(), text)
        n += step
    }
    val degrees = ((Math.round(Math.toDegrees(r.a)) % 180) + 180) % 180
    c.drawText("$degrees°  cm", -12f, (RULER_W - 22).toFloat(), text)
    c.restore()
}

private fun PageCanvas.drawPull(c: Canvas) {
    val done = min(1.0, abs(pull) / 90)
    val x = (if (pull > 0) W - 70 else 70.0).toFloat()
    val y = (H / 2).toFloat()
    c.drawCircle(x, y, 26f, fill(argb(doubleArrayOf(54 / 255.0, 133 / 255.0, 227 / 255.0), 0.12 + 0.5 * done)))
    c.drawCircle(x, y, 26f, stroke(ACCENT, 2.0))
    val mark = fill(if (done >= 1) android.graphics.Color.WHITE else ACCENT)
    c.drawRect(x - 9, y - 1, x + 9, y + 1, mark)
    c.drawRect(x - 1, y - 9, x + 1, y + 9, mark)
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; color = ACCENT; textAlign = Paint.Align.CENTER }
    c.drawText(if (done >= 1) tr("松手添加页面") else tr("继续拉动添加页面"), x, y + 46, text)
}

/** Selection box in page points, following an in-progress move or scale. */
private fun PageCanvas.selBox(): Box {
    val (x0, y0, x1, y1) = sel!!.bbox
    val o = op
    if (o != null && o.kind == "move") {
        val (ox, oy) = o.origin
        return doubleArrayOf(ox + (x0 - ox) * o.s + o.dx, oy + (y0 - oy) * o.s + o.dy, ox + (x1 - ox) * o.s + o.dx, oy + (y1 - oy) * o.s + o.dy)
    }
    return doubleArrayOf(x0, y0, x1, y1)
}

private fun PageCanvas.snapSelection(c: Canvas) {
    val s = sel ?: return
    val i = index(s.page) ?: return
    val (x0, y0, x1, y1) = selBox()
    val r = pageRect(i)
    val k = r[2] / s.page.w
    c.drawRect((r[0] + x0 * k - 4).toFloat(), (r[1] + y0 * k - 4).toFloat(), (r[0] + x1 * k + 4).toFloat(), (r[1] + y1 * k + 4).toFloat(),
        stroke(ACCENT, 1.5).apply { pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f); strokeJoin = Paint.Join.MITER })
    val hx = (r[0] + x1 * k + 4).toFloat()
    val hy = (r[1] + y1 * k + 4).toFloat()
    c.drawCircle(hx, hy, 6f, fill(android.graphics.Color.WHITE))
    c.drawCircle(hx, hy, 6f, stroke(ACCENT, 2.0))
}

// -- tools ----------------------------------------------------------------------------------------

/** A pen, finger or mouse came down. */
internal fun PageCanvas.down(x0: Double, y0: Double, pressure: Double, erase: Boolean) {
    var x = x0
    var y = y0
    op = null
    val t = if (erase) "eraser" else tool
    var rule: DoubleArray? = null
    val zone = if (t != "eraser") rulerZone(x, y) else null
    // A finger or a click on a tape lifts it or puts it back.
    if (src != "pen" && t != "eraser" && t != "lasso" && zone == null && toggleTape(x, y)) return
    if (zone == "body" && src != "pen") {
        // A finger or the mouse on the ruler moves it; only the pen draws across it.
        op = Op("ruler", null, -1).also { it.grab = Pair(x - ruler!!.x, y - ruler!!.y) }
        return
    }
    if (zone != null && (t == "pen" || t == "hl" || t == "tape")) {
        // A stroke begun at the ruler runs along its edge.
        rule = rulerEdge(x, y)
        val p = onto(rule, x, y)
        x = p.first
        y = p.second
    }
    if (t == "lasso") {
        if (src != "pen") press(x, y)   // the pen holds still too often while writing for this to be meant
        if (sel != null && grabSelection(x, y)) return
    }
    clearSelection()
    val loc = locate(x, y) ?: return
    val (i, px, py) = loc
    val page = doc!!.pages[i]
    when (t) {
        "pen", "hl", "tape" -> {
            val item = when (t) {
                "tape" -> Stroke("tape", tapeColor, tapeWidth, listOf(doubleArrayOf(px, py, 0.5)))
                "pen" -> Stroke(penType, penColor, penWidth, listOf(doubleArrayOf(px, py, pressure)), penSens, penDash)
                else -> Stroke("hl", hlColor, hlWidth, listOf(doubleArrayOf(px, py, 0.5)))
            }
            op = Op("stroke", page, i).also {
                it.item = item
                it.smooth = Pair(px, py)
                it.rule = rule
                it.anchor = Pair(x, y)
            }
            layer?.eraseColor(0)
        }
        "eraser" -> {
            val o = Op("erase", page, i).also {
                it.pos = Pair(x, y)
                it.last = Pair(px, py)
                it.before = ArrayList(page.items)
            }
            op = o
            erase(o, px, py)
        }
        "shape" -> op = Op("shape", page, i).also { it.item = Shape(shapeKind, px, py, px, py, penColor, penWidth) }
        "lasso" -> op = Op("lasso", page, i).also {
            it.pts.add(doubleArrayOf(px, py))
            it.rect = lassoRect
        }
        "text", "note" -> editText(i, page, x, y, px, py, t == "note")
        "laser" -> {
            laserReset()
            val o = Op("laser", page, i).also { it.trail = laserMode == "trail" }
            op = o
            if (o.trail) laserTrails.add(Pair(page, arrayListOf(doubleArrayOf(px, py)))) else laserDot = Triple(page, px, py)
        }
    }
    invalidate()
}

internal fun PageCanvas.move(x0: Double, y0: Double, pressure: Double) {
    val o = op ?: return
    var x = x0
    var y = y0
    if (pressPending != null && hypot(x - pressAt.first, y - pressAt.second) > 10) stopPress()
    if (o.kind == "move") {
        val i = index(o.page!!) ?: return
        if (!o.live) {
            // A finger does not hold still: the selection stays put until it clearly moves.
            if (hypot(x - o.at.first, y - o.at.second) < o.slack) return
            o.live = true
        }
        val (px, py) = toPage(i, x, y)
        if (o.scaling) {
            val (ox, oy) = o.origin
            val (ssx, ssy) = o.start
            o.s = max(0.1, hypot(px - ox, py - oy) / max(1.0, hypot(ssx - ox, ssy - oy)))
        } else {
            o.dx = px - o.start.first
            o.dy = py - o.start.second
        }
        invalidate()
        return
    }
    if (o.kind == "ruler") {
        ruler?.let {
            it.x = x - o.grab.first
            it.y = y - o.grab.second
        }
        invalidate()
        return
    }
    if (o.fixed) return
    o.rule?.let { val p = onto(it, x, y); x = p.first; y = p.second }
    var (px, py) = toPage(o.i, x, y)
    when (o.kind) {
        "stroke" -> {
            val item = o.item as Stroke
            val pts = item.pts
            if (holdShape && o.rule == null) {
                val (ax, ay) = o.anchor
                if (holdPending == null || (x - ax) * (x - ax) + (y - ay) * (y - ay) > 25) {
                    o.anchor = Pair(x, y)
                    holdPending?.let { handler.removeCallbacks(it) }
                    val r = Runnable { held() }
                    holdPending = r
                    handler.postDelayed(r, 600)
                }
            }
            if (penStab > 0 && item.tool != "hl" && item.tool != "tape" && o.rule == null) {
                // Stabiliser: the ink follows the pen through a low-pass filter.
                val k = 1.0 - 0.9 * penStab
                val (ssx, ssy) = o.smooth
                px = ssx + (px - ssx) * k
                py = ssy + (py - ssy) * k
                o.smooth = Pair(px, py)
            }
            val lx = pts.last()[0]
            val ly = pts.last()[1]
            if ((px - lx) * (px - lx) + (py - ly) * (py - ly) < (0.4 / zoom) * (0.4 / zoom)) return
            pts.add(doubleArrayOf(px, py, pressure))
            if (item.tool != "hl" && item.tool != "tape" && item.dash == 0 && pts.size - o.baked >= CHUNK) {
                // Freeze what is drawn so far; only the tail is redrawn per frame.
                val part = Stroke(item.tool, item.color, item.width, pts.subList(max(0, o.baked - 1), pts.size).toList(), item.sens)
                val l = layer ?: Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888).also { layer = it }
                val c = Canvas(l)
                c.save()
                if (!board) {
                    val r = pageRect(o.i)
                    c.clipRect((r[0] * dev).toFloat(), (r[1] * dev).toFloat(), ((r[0] + r[2]) * dev).toFloat(), ((r[1] + r[3]) * dev).toFloat())
                }
                drawStroke(c, pageEnv(c, o.i), part)
                c.restore()
                o.baked = pts.size - 1
            }
        }
        "erase" -> {
            o.pos = Pair(x, y)
            erase(o, px, py)
        }
        "laser" -> if (o.trail) laserTrails.last().second.add(doubleArrayOf(px, py)) else laserDot = Triple(o.page!!, px, py)
        "shape" -> {
            val s = o.item as Shape
            o.item = Shape(s.shape, s.x0, s.y0, px, py, s.color, s.width)
        }
        "lasso" -> if (o.rect) {
            while (o.pts.size > 1) o.pts.removeAt(o.pts.size - 1)
            o.pts.add(doubleArrayOf(px, py))
        } else o.pts.add(doubleArrayOf(px, py))
    }
    invalidate()
}

/** Lift the tape under a point, or put it back; true if there was one. */
internal fun PageCanvas.toggleTape(x: Double, y: Double): Boolean {
    val (i, px, py) = locate(x, y) ?: return false
    val page = doc!!.pages[i]
    for (k in page.items.indices.reversed()) {
        val it = page.items[k]
        if (it is Stroke && it.tool == "tape" && Geom.intersects(it.bbox, doubleArrayOf(px, py, px, py)) && it.hit(px, py, 2 / zoom)) {
            it.open = !it.open
            page.rev += 1
            doc!!.touch()
            repaintPage(page, it.bbox)
            invalidate()
            return true
        }
    }
    return false
}

/** The pen rested at the end of a stroke: snap the stroke to a clean shape. */
internal fun PageCanvas.held() {
    holdPending = null
    val o = op ?: return
    if (o.kind != "stroke") return
    val item = o.item as Stroke
    if (item.pts.size < 6) return
    val found = Geom.recognizeShape(item.pts) ?: return
    if (item.tool == "hl" || item.tool == "tape") {
        if (found.shape != "line") return
        item.pts.clear()
        item.pts.add(doubleArrayOf(found.x0, found.y0, 0.5))
        item.pts.add(doubleArrayOf(found.x1, found.y1, 0.5))
        item.updateBbox()
    } else {
        o.kind = "shape"
        o.item = Shape(found.shape, found.x0, found.y0, found.x1, found.y1, item.color, item.width)
    }
    o.fixed = true
    invalidate()
}

internal fun PageCanvas.stopHold() {
    holdPending?.let { handler.removeCallbacks(it) }
    holdPending = null
}

/** The select tool came down: if it stays where it is, a menu is wanted there. */
internal fun PageCanvas.press(x: Double, y: Double) {
    stopPress()
    pressAt = Pair(x, y)
    val r = Runnable {
        pressPending = null
        // Whatever the press had begun (a lasso, a move of the selection) is given up, and what is
        // left of the press does nothing more.
        cancel()
        tg?.let {
            it.grab = "held"
            it.draw = false
            it.moved = false
        }
        host.menuWanted(pressAt.first, pressAt.second)
    }
    pressPending = r
    handler.postDelayed(r, 550)
}

internal fun PageCanvas.stopPress() {
    pressPending?.let { handler.removeCallbacks(it) }
    pressPending = null
}

/** Select the topmost picture, shape or text box at a point of the view, or nothing. */
internal fun PageCanvas.pickAt(x: Double, y: Double): Boolean {
    val loc = locate(x, y)
    var picked: Item? = null
    var page: Page? = null
    if (loc != null) {
        val (i, px, py) = loc
        page = doc!!.pages[i]
        val r = 10 / max(1e-6, z(page))
        for (k in page.items.indices.reversed()) {
            val it = page.items[k]
            // Not handwriting: a tap among the lines of a page would always catch some of it.
            if (it is Stroke || lassoKind(it) !in lassoFilter || !Geom.intersects(it.bbox, doubleArrayOf(px - r, py - r, px + r, py + r))) continue
            // Pictures and text are picked anywhere inside; shapes on their lines.
            if (it is Image || it is Text || it.hit(px, py, r)) {
                picked = it
                break
            }
        }
    }
    if (picked != null && page != null) select(page, listOf(picked)) else clearSelection()
    return picked != null
}

/** The pen, finger or mouse went up. */
internal fun PageCanvas.up(x: Double, y: Double) {
    val o = op
    op = null
    stopHold()
    stopPress()
    if (o == null) return
    val page = o.page
    val d = doc!!
    when (o.kind) {
        "stroke" -> {
            val item = o.item as Stroke
            if (penStab > 0 && item.tool != "hl" && item.tool != "tape" && !o.fixed && o.rule == null) {
                // Catch the lagging stabilised line up with where the pen lifted.
                val (px, py) = toPage(o.i, x, y)
                item.pts.add(doubleArrayOf(px, py, item.pts.last()[2]))
            }
            item.updateBbox()
            if (item.tool != "hl" && item.tool != "tape" && !o.fixed && o.rule == null && scribbleErase && Geom.isScribble(item.pts, scribbleLoose)) {
                val targets = Geom.scribbleTargets(item.pts, page!!.items)
                if (targets.isNotEmpty()) {
                    // Scribble to erase, recorded as two steps: the scribble as ordinary ink, then the
                    // erasure. One undo brings everything back with the scribble still there (in case
                    // it was not meant as one), a second undo removes the scribble too.
                    d.addItems(page, listOf(item))
                    d.removeItems(page, targets + item)
                    host.scribbled(targets.size)
                    invalidate()
                    return
                }
            }
            d.addItems(page!!, listOf(item))
        }
        "erase" -> if (o.changed) {
            d.pushDiff(page!!, o.before)
            host.eraseDone()
        }
        "laser" -> {
            laserDot = null
            if (laserTrails.isNotEmpty()) {
                val r = Runnable { laserExpire() }
                laserTimer = r
                handler.postDelayed(r, LASER_HOLD_MS)
            }
        }
        "shape" -> {
            val item = o.item as Shape
            if (hypot(item.x1 - item.x0, item.y1 - item.y0) > 2) d.addItems(page!!, listOf(item))
        }
        "lasso" -> {
            val box = Geom.bboxOf(o.pts)
            val items = page!!.items.filter { lassoKind(it) in lassoFilter }
            if (max(box[2] - box[0], box[3] - box[1]) * z(page) < 8) {
                pickAt(x, y)   // a tap selects the topmost item under it
                invalidate()
                return
            }
            select(page, if (o.rect) Geom.rectPick(box, items) else Geom.lassoPick(o.pts, items))
        }
        "move" -> {
            hidden = null
            val s = sel ?: return
            val olds = s.items
            if (abs(o.s - 1) > 1e-3 || hypot(o.dx, o.dy) * zoom > 1) {
                val news = olds.map { it.transformed(o.s, o.origin.first, o.origin.second, o.dx, o.dy) }
                if (o.copy) d.addItems(page!!, news)   // the originals stay where they were
                else d.replaceItems(page!!, olds, news)
                select(page, news)
            } else {
                repaintPage(page!!, s.bbox)
            }
            host.selectionChanged()
        }
    }
    invalidate()
}

fun PageCanvas.cancel() {
    val o = op
    op = null
    penDown = false
    stopHold()
    stopPress()
    if (o == null) return
    when (o.kind) {
        "laser" -> {
            laserDot = null
            if (laserTrails.isNotEmpty()) laserTrails.removeAt(laserTrails.size - 1)
        }
        "erase" -> if (o.changed) doc!!.pushDiff(o.page!!, o.before)
        "move" -> {
            hidden = null
            sel?.let { repaintPage(o.page!!, it.bbox) }
            host.selectionChanged()
        }
    }
    invalidate()
}

private fun PageCanvas.erase(o: Op, px: Double, py: Double) {
    val page = o.page!!
    val r = eraserR
    val (lx, ly) = o.last
    o.last = Pair(px, py)
    val reach = doubleArrayOf(min(lx, px) - r, min(ly, py) - r, max(lx, px) + r, max(ly, py) + r)
    val whole = eraserMode == "stroke"
    val out = ArrayList<Item>()
    var rect: Box? = null
    for (it in page.items) {
        if (!Geom.intersects(it.bbox, reach) || eraseKind(it) !in eraseFilter) {
            out.add(it)
            continue
        }
        if (whole || (it is Shape && it.shape == "arrow")) {
            val steps = max(1, (hypot(px - lx, py - ly) / (r * 0.7)).toInt())
            var hit = false
            var k = 0
            while (k <= steps && !hit) {
                hit = it.hit(lx + (px - lx) * k / steps, ly + (py - ly) * k / steps, r)
                k++
            }
            if (hit) rect = Geom.union(rect, it.bbox) else out.add(it)
            continue
        }
        // Standard eraser: cut the touched part out and keep the rest as shorter strokes.
        val width = if (it is Stroke) it.width else (it as Shape).width
        val pts: List<DoubleArray> = if (it is Stroke) it.pts else (it.polyline() ?: emptyList()).map { p -> doubleArrayOf(p[0], p[1], 0.5) }
        val cut = Geom.eraseRuns(pts, lx, ly, px, py, r + width / 2)
        if (cut == null) {
            out.add(it)
            continue
        }
        val (runs, box) = cut
        val tool: String
        val sens: Double
        val dash: Int
        if (it is Stroke && it.dash == 0) {
            val pad = it.pad() + 2
            rect = Geom.union(rect, doubleArrayOf(box[0] - pad, box[1] - pad, box[2] + pad, box[3] + pad))
            tool = it.tool
            sens = it.sens
            dash = 0
        } else {
            rect = Geom.union(rect, it.bbox)   // a shape or dashed line is redrawn as a whole
            if (it is Stroke) {
                tool = it.tool
                sens = it.sens
                dash = it.dash
            } else {
                tool = "ball"
                sens = 1.0
                dash = 0
            }
        }
        val color = if (it is Stroke) it.color else (it as Shape).color
        for (run in runs) if (run.size > 1) out.add(Stroke(tool, color, width, run, sens, dash))
    }
    if (rect == null) return
    page.items = out
    o.changed = true
    repaintPage(page, rect)
}

// -- the laser ----------------------------------------------------------------------------------

/** Any laser activity keeps what is on screen and restarts the countdown. */
private fun PageCanvas.laserReset() {
    laserTimer?.let { handler.removeCallbacks(it) }
    laserTimer = null
    laserFading = false
    laserAlpha = 1.0
}

private fun PageCanvas.laserExpire() {
    laserTimer = null
    val t0 = android.os.SystemClock.uptimeMillis()
    laserFading = true
    val step = object : Runnable {
        override fun run() {
            if (!laserFading) return
            val t = min(1.0, (android.os.SystemClock.uptimeMillis() - t0) / LASER_FADE_MS.toDouble())
            laserAlpha = 1 - t
            invalidate()
            if (t < 1) {
                postOnAnimation(this)
                return
            }
            laserFading = false
            laserTrails.clear()
            laserAlpha = 1.0
            invalidate()
        }
    }
    postOnAnimation(step)
}

fun PageCanvas.clearLaser() {
    laserReset()
    laserTrails.clear()
    laserDot = null
    invalidate()
}

// -- the selection ------------------------------------------------------------------------------

fun PageCanvas.select(page: Page, items: List<Item>) {
    if (items.isEmpty()) {
        sel = null
    } else {
        var box: Box? = null
        for (it in items) box = Geom.union(box, it.bbox)
        sel = Selection(page, ArrayList(items), box!!)
        host.selectionMade()
    }
    host.selectionChanged()
    invalidate()
}

fun PageCanvas.clearSelection() {
    if (sel != null) {
        sel = null
        host.selectionChanged()
        invalidate()
    }
}

val PageCanvas.selection get() = sel

/** The selection box in view dp, or null (also while it is being dragged). */
fun PageCanvas.selectionRect(): DoubleArray? {
    val s = sel ?: return null
    if (op?.kind == "move") return null
    val i = index(s.page) ?: return null
    val r = pageRect(i)
    val k = r[2] / s.page.w
    val (x0, y0, x1, y1) = s.bbox
    return doubleArrayOf(r[0] + x0 * k, r[1] + y0 * k, (x1 - x0) * k, (y1 - y0) * k)
}

/** Take hold of the selection at a point of the view: inside it to move it, on its edge (within
 *  [reach]) or its handle to resize it. False if the point is elsewhere. */
internal fun PageCanvas.grabSelection(x: Double, y: Double, reach: Double = 7.0): Boolean {
    val s = sel ?: return false
    val i = index(s.page) ?: return false
    val (px, py) = toPage(i, x, y)
    val (x0, y0, x1, y1) = s.bbox
    val k = pageRect(i)[2] / s.page.w
    // The box as drawn is 4 dp larger all round.
    val bx0 = x0 - 4 / k
    val by0 = y0 - 4 / k
    val bx1 = x1 + 4 / k
    val by1 = y1 + 4 / k
    val r = reach / k
    var origin = Pair(x0, y0)
    var scaling = hypot((px - x1) * k - 4, (py - y1) * k - 4) <= 13
    if (!scaling) {
        if (!(bx0 - r <= px && px <= bx1 + r && by0 - r <= py && py <= by1 + r)) return false
        // The edge is a band about the outline; a small selection keeps a middle to move it by.
        val inner = minOf(r, (bx1 - bx0) / 4, (by1 - by0) / 4)
        if (!(bx0 + inner < px && px < bx1 - inner && by0 + inner < py && py < by1 - inner)) {
            // It grows and shrinks about the point opposite the one taken hold of.
            scaling = true
            origin = Pair(x0 + x1 - px.coerceIn(x0, x1), y0 + y1 - py.coerceIn(y0, y1))
        }
    }
    val items = s.items
    // Lift the items out of the page raster and carry them as one picture.
    val layerRaster = raster(i, s.bbox) { c, env -> for (it in items) drawItem(c, env, it) }
    hidden = identitySetOf(items)
    repaintPage(s.page, s.bbox)
    op = Op("move", s.page, i).also {
        it.start = Pair(px, py)
        it.origin = origin
        it.scaling = scaling
        it.raster = layerRaster
        it.at = Pair(x, y)
        it.slack = if (reach > 7) 8.0 else 0.0
    }
    host.selectionChanged()
    invalidate()
    return true
}

private fun <T> identitySetOf(of: Collection<T>): MutableSet<T> =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>()).apply { addAll(of) }

/** Start dragging a copy of the selection from a point of the view. */
internal fun PageCanvas.copySelectionFrom(x: Double, y: Double): Boolean {
    val s = sel ?: return false
    val i = index(s.page) ?: return false
    val layerRaster = raster(i, s.bbox) { c, env -> for (it in s.items) drawItem(c, env, it) }
    op = Op("move", s.page, i).also {
        it.start = toPage(i, x, y)
        it.origin = Pair(s.bbox[0], s.bbox[1])
        it.copy = true
        it.raster = layerRaster
        it.at = Pair(x, y)
        it.slack = 8.0
    }
    host.selectionChanged()
    invalidate()
    return true
}

fun PageCanvas.deleteSelection() {
    val s = sel ?: return
    sel = null
    doc!!.removeItems(s.page, s.items)
    host.selectionChanged()
}

fun PageCanvas.duplicateSelection() {
    val s = sel ?: return
    val items = s.items.map { it.transformed(1.0, 0.0, 0.0, 14.0, 14.0) }
    doc!!.addItems(s.page, items)
    select(s.page, items)
}

/** Give the selected ink, shapes and text a colour; pictures are left as they are. */
fun PageCanvas.recolorSelection(color: DoubleArray) {
    val s = sel ?: return
    val news = s.items.map {
        val n = it.transformed(1.0, 0.0, 0.0, 0.0, 0.0)
        when (n) {
            is Stroke -> n.color = color.copyOf()
            is Shape -> n.color = color.copyOf()
            is Text -> n.color = color.copyOf()
            else -> {}
        }
        n
    }
    doc!!.replaceItems(s.page, s.items, news)
    select(s.page, news)
}

/** Turn the selection k quarter turns clockwise about its own centre (text stays upright). */
fun PageCanvas.rotateSelection(k: Int) {
    val s = sel ?: return
    val page = s.page
    val (x0, y0, x1, y1) = s.bbox
    val turned = s.items.map { it.rotated(k % 4, page.w, page.h) }
    var box: Box? = null
    for (it in turned) box = Geom.union(box, it.bbox)
    val b = box!!
    val dx = (x0 + x1 - b[0] - b[2]) / 2
    val dy = (y0 + y1 - b[1] - b[3]) / 2
    val news = turned.map { it.transformed(1.0, 0.0, 0.0, dx, dy) }
    doc!!.replaceItems(page, s.items, news)
    select(page, news)
}

/** Move the selection above (or below) everything else on its page. */
fun PageCanvas.restackSelection(front: Boolean) {
    val s = sel ?: return
    val page = s.page
    val chosen = identitySetOf(s.items)
    val ordered = page.items.withIndex().filter { it.value in chosen }.map { Pair(it.index, it.value) }
    val base = if (front) page.items.size - ordered.size else 0
    doc!!.perform(io.github.inkpad.core.Action.Items(page, ordered, ordered.mapIndexed { j, (_, it) -> Pair(base + j, it) }))
    invalidate()
}

/** The selected area of the page, paper included, as a picture. */
fun PageCanvas.selectionImage(scale: Double = 2.0): Bitmap? {
    val s = sel ?: return null
    val page = s.page
    var (x0, y0, x1, y1) = s.bbox
    if (!page.board) {
        x0 = max(0.0, x0 - 6); y0 = max(0.0, y0 - 6); x1 = min(page.w, x1 + 6); y1 = min(page.h, y1 + 6)
    } else {
        x0 -= 6; y0 -= 6; x1 += 6; y1 += 6
    }
    val bmp = Bitmap.createBitmap(max(1, ((x1 - x0) * scale).roundToInt()), max(1, ((y1 - y0) * scale).roundToInt()), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.scale(scale.toFloat(), scale.toFloat())
    // Rendered the way a page is, from the selection's corner (the origin is subtracted in doubles).
    renderPage(c, page, x0, y0, 1 / scale, background = io.github.inkpad.pdf.PdfPages.background(page)?.let { bg -> { cv: Canvas ->
        cv.save()
        cv.translate((-x0).toFloat(), (-y0).toFloat())
        bg(cv, scale, scale)
        cv.restore()
    } })
    return bmp
}

fun PageCanvas.copySelection(): Boolean {
    val s = sel ?: return false
    clip = s.items.map { it.transformed(1.0, 0.0, 0.0, 0.0, 0.0) }
    return true
}

fun PageCanvas.hasClip() = clip.isNotEmpty()

/** Put the copied items on the page: a little beside where they were, or centred on a point of the view. */
fun PageCanvas.paste(at: Pair<Double, Double>? = null): Boolean {
    if (clip.isEmpty()) return false
    val d = doc!!
    val loc = at?.let { locate(it.first, it.second) }
    if (loc != null) {
        val page = d.pages[loc.first]
        var box: Box? = null
        for (it in clip) box = Geom.union(box, it.bbox)
        val b = box!!
        val items = clip.map { it.transformed(1.0, 0.0, 0.0, loc.second - (b[0] + b[2]) / 2, loc.third - (b[1] + b[3]) / 2) }
        d.addItems(page, items)
        select(page, items)
        return true
    }
    val page = d.pages[currentPageIndex()]
    val items = clip.map { it.transformed(1.0, 0.0, 0.0, 14.0, 14.0) }
    clip = items
    d.addItems(page, items)
    select(page, items)
    return true
}

/** Place a picture in the middle of the visible part of the current page, or centred on a point. */
fun PageCanvas.insertImage(png: ByteArray, width: Int, height: Int, at: Pair<Double, Double>? = null) {
    val d = doc ?: return
    val loc = at?.let { locate(it.first, it.second) }
    val i = loc?.first ?: currentPageIndex()
    val page = d.pages[i]
    val k = minOf(1.0, page.w * 0.6 / width, page.h * 0.6 / height)
    val w = width * k
    val hh = height * k
    val (cx, cy) = if (loc != null) Pair(loc.second, loc.third) else toPage(i, W / 2, H / 2)
    var x = cx - w / 2
    var y = cy - hh / 2
    if (!page.board) {
        x = x.coerceIn(0.0, max(0.0, page.w - w))
        y = y.coerceIn(0.0, max(0.0, page.h - hh))
    }
    val item = Image(x, y, w, hh, ImageData(io.github.inkpad.core.ByteSource(png)))
    d.addItems(page, listOf(item))
    setTool("lasso")
    select(page, listOf(item))
}

// -- text -----------------------------------------------------------------------------------------

/** A box to write in, at the place that was touched; a text box that is there is edited. */
internal fun PageCanvas.editText(i: Int, page: Page, x: Double, y: Double, px: Double, py: Double, note: Boolean) {
    val d = doc!!
    val existing = page.items.lastOrNull { it is Text && Geom.intersects(it.bbox, doubleArrayOf(px, py, px, py)) } as Text?
    val color = penColor.copyOf()
    val size = textSize
    val noteColor = this.noteColor.copyOf()
    host.editText(x, y, existing?.text) { typed ->
        val text = typed?.trim() ?: return@editText
        if (existing != null) {
            if (text.isEmpty()) d.removeItems(page, listOf(existing))
            else if (text != existing.text) d.replaceItems(page, listOf(existing), listOf(Text(existing.x, existing.y, text, existing.size, existing.color, existing.wrap, existing.bg)))
        } else if (text.isNotEmpty() && note) {
            d.addItems(page, listOf(Text(px, py, text, 13.0, doubleArrayOf(0.16, 0.15, 0.12), 170.0, noteColor)))
        } else if (text.isNotEmpty()) {
            d.addItems(page, listOf(Text(px, py, text, size, color, if (page.board) 400.0 else max(60.0, page.w - px - 12))))
        }
    }
}


/** A tap of a finger (A:fingerTap): tape lifts or goes back (not with the eraser or the select
 *  tool), the text tools write where it was, the select tool picks what is under it, and any
 *  other tool lets go of the selection. */
internal fun PageCanvas.fingerTap(x: Double, y: Double) {
    val t = tool
    if (t != "eraser" && t != "lasso" && toggleTape(x, y)) return
    when (t) {
        "text", "note" -> {
            clearSelection()
            locate(x, y)?.let { (i, px, py) -> editText(i, doc!!.pages[i], x, y, px, py, t == "note") }
        }
        "lasso" -> pickAt(x, y)
        else -> clearSelection()
    }
    invalidate()
}
