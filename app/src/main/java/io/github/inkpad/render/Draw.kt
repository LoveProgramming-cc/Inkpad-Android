package io.github.inkpad.render

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.inkpad.core.BoxItem
import io.github.inkpad.core.Color
import io.github.inkpad.core.HL_ALPHA
import io.github.inkpad.core.Image
import io.github.inkpad.core.Item
import io.github.inkpad.core.Page
import io.github.inkpad.core.Ptext
import io.github.inkpad.core.Shape
import io.github.inkpad.core.Stroke
import io.github.inkpad.core.Text
import io.github.inkpad.core.dashRuns
import io.github.inkpad.core.pageBox
import io.github.inkpad.core.tr
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// Drawing the things on a page: the draw methods of B:model.py, through W:src/core/draw.js.
//
// Every function takes an Env and draws page point (x, y) at (x - ox, y - oy) on the canvas. The
// origin is subtracted here, in doubles, and never left to the canvas's matrix, which is single
// precision: a whiteboard's coordinates are around 5e7 and would shake and slip if translated by
// the matrix (W PORTING.md R3).

class Env(
    val ox: Double = 0.0,           // page coordinates of the canvas's (0, 0)
    val oy: Double = 0.0,
    val perPixel: Double = 1.0,     // page points per device pixel, for the far-away simplification
    var onDark: Boolean = false,    // highlighters do not multiply on dark paper
    val printing: Boolean = false,  // writing a PDF: nothing is simplified
)

fun argb(c: Color, a: Double = 1.0): Int {
    fun ch(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    return (ch(a) shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
}

fun isDark(c: Color) = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2] < 0.45

/** Highlighters multiply on light paper only, and not on a PDF page. */
fun inkOn(page: Page) = page.pdf == null && isDark(page.color)

private fun strokePaint(color: Int, width: Double) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
    strokeWidth = width.toFloat()
    this.color = color
}

private fun fillPaint(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
    this.color = color
}

private fun Path.m(x: Double, y: Double, e: Env) = moveTo((x - e.ox).toFloat(), (y - e.oy).toFloat())
private fun Path.l(x: Double, y: Double, e: Env) = lineTo((x - e.ox).toFloat(), (y - e.oy).toFloat())
private fun Path.c(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double, e: Env) =
    cubicTo((x1 - e.ox).toFloat(), (y1 - e.oy).toFloat(), (x2 - e.ox).toFloat(), (y2 - e.oy).toFloat(), (x3 - e.ox).toFloat(), (y3 - e.oy).toFloat())

/** Quadratic midpoint smoothing through the sampled points. */
private fun smoothPath(path: Path, e: Env, pts: List<DoubleArray>) {
    var px = pts[0][0]
    var py = pts[0][1]
    path.m(px, py, e)
    val n = pts.size
    for (i in 1 until n - 1) {
        val cx = pts[i][0]
        val cy = pts[i][1]
        val mx = (cx + pts[i + 1][0]) / 2
        val my = (cy + pts[i + 1][1]) / 2
        path.c(px + (cx - px) * 2 / 3, py + (cy - py) * 2 / 3, mx + (cx - mx) * 2 / 3, my + (cy - my) * 2 / 3, mx, my, e)
        px = mx
        py = my
    }
    path.l(pts[n - 1][0], pts[n - 1][1], e)
}

private fun drawRun(cv: Canvas, e: Env, s: Stroke, pts: List<DoubleArray>, paint: Paint) {
    val n = pts.size
    if (n == 1) {
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        cv.drawCircle((pts[0][0] - e.ox).toFloat(), (pts[0][1] - e.oy).toFloat(), (s.wAt(pts[0][2]) / 2).toFloat(), fill)
    } else if (s.tool == "hl" || s.tool == "ball") {
        paint.strokeWidth = s.width.toFloat()
        val path = Path()
        smoothPath(path, e, pts)
        cv.drawPath(path, paint)
    } else {
        // Pressure-sensitive: one short segment per sample, each its own width.
        var px = pts[0][0]
        var py = pts[0][1]
        var pp = pts[0][2]
        val path = Path()
        for (i in 1 until n) {
            val cx = pts[i][0]
            val cy = pts[i][1]
            val cp = pts[i][2]
            path.rewind()
            path.m(px, py, e)
            val mx: Double
            val my: Double
            if (i < n - 1) {
                mx = (cx + pts[i + 1][0]) / 2
                my = (cy + pts[i + 1][1]) / 2
                path.c(px + (cx - px) * 2 / 3, py + (cy - py) * 2 / 3, mx + (cx - mx) * 2 / 3, my + (cy - my) * 2 / 3, mx, my, e)
            } else {
                mx = cx
                my = cy
                path.l(cx, cy, e)
            }
            paint.strokeWidth = ((s.wAt(pp) + s.wAt(cp)) / 2).toFloat()
            cv.drawPath(path, paint)
            px = mx
            py = my
            pp = cp
        }
    }
}

fun drawStroke(cv: Canvas, e: Env, s: Stroke) {
    val pts = s.pts
    if (pts.isEmpty()) return
    if (s.tool == "tape") {
        // Tape covers what is under it; lifted, only a trace of it is left.
        val path = Path()
        smoothPath(path, e, if (pts.size > 1) pts else listOf(pts[0], pts[0]))
        cv.drawPath(path, strokePaint(argb(s.color, if (s.open) 0.16 else 1.0), s.width))
        return
    }
    val hl = s.tool == "hl"
    val paint = strokePaint(argb(s.color, if (hl) HL_ALPHA else 1.0), s.width)
    // Multiplying darkens, which is what a marker does on light paper but would make it invisible
    // on a dark one.
    if (hl && !e.onDark) paint.blendMode = BlendMode.MULTIPLY
    val perPixel = e.perPixel
    if (perPixel > 1.5 && !e.printing) {
        // Seen from far away: no pressure detail and no points closer together than a pixel, and
        // never thinner than a hairline, so the stroke stays visible and cheap to draw.
        val step2 = perPixel * perPixel
        var lx = pts[0][0]
        var ly = pts[0][1]
        val path = Path()
        path.m(lx, ly, e)
        for (i in 1 until pts.size - 1) {
            val x = pts[i][0]
            val y = pts[i][1]
            if ((x - lx) * (x - lx) + (y - ly) * (y - ly) >= step2) {
                path.l(x, y, e)
                lx = x
                ly = y
            }
        }
        path.l(pts[pts.size - 1][0] + 0.001, pts[pts.size - 1][1], e)
        paint.strokeWidth = max(s.width, perPixel * 0.9).toFloat()
        cv.drawPath(path, paint)
    } else if (s.dash != 0 && pts.size > 1) {
        val w = s.width
        val (on, off) = if (s.dash == 1) Pair(max(3.0 * w, 2.5), max(2.6 * w, 3.0)) else Pair(0.0, max(2.4 * w, 2.5))
        for (run in dashRuns(pts, on, off)) drawRun(cv, e, s, run, paint)
    } else {
        drawRun(cv, e, s, pts, paint)
    }
}

fun drawShape(cv: Canvas, e: Env, s: Shape) {
    val paint = strokePaint(argb(s.color), s.width)
    val x0 = s.x0
    val y0 = s.y0
    val x1 = s.x1
    val y1 = s.y1
    when (s.shape) {
        "rect" -> cv.drawRect((min(x0, x1) - e.ox).toFloat(), (min(y0, y1) - e.oy).toFloat(), (max(x0, x1) - e.ox).toFloat(), (max(y0, y1) - e.oy).toFloat(), paint)
        "ellipse" -> {
            if (kotlin.math.abs(x1 - x0) > 0.02 && kotlin.math.abs(y1 - y0) > 0.02) {
                cv.drawOval(RectF((min(x0, x1) - e.ox).toFloat(), (min(y0, y1) - e.oy).toFloat(), (max(x0, x1) - e.ox).toFloat(), (max(y0, y1) - e.oy).toFloat()), paint)
            }
        }
        else -> {
            val path = Path()
            path.m(x0, y0, e)
            path.l(x1, y1, e)
            if (s.shape == "arrow") {
                val a = atan2(y1 - y0, x1 - x0)
                val head = max(8.0, s.width * 4)
                for (da in listOf(2.6, -2.6)) {
                    path.m(x1, y1, e)
                    path.l(x1 + head * cos(a + da), y1 + head * sin(a + da), e)
                }
            }
            cv.drawPath(path, paint)
        }
    }
}

// -- text ----------------------------------------------------------------------------------

val textTypeface: Typeface = Typeface.DEFAULT

fun textPaint(size: Double, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
    typeface = textTypeface
    textSize = size.toFloat()
    this.color = color
}

/** Draw text with its top left corner at (x, y), which are already relative to the origin. */
fun showText(cv: Canvas, x: Double, y: Double, text: String, size: Double, wrap: Double, color: Int) {
    val paint = textPaint(size, color)
    val m = AndroidMetrics
    val lh = m.lineHeight(size)
    val ascent = m.ascent(size)
    Ptext.layoutLines(text, size, wrap).forEachIndexed { i, line ->
        if (line.isNotEmpty()) cv.drawText(line, x.toFloat(), (y + ascent + i * lh).toFloat(), paint)
    }
}

fun drawText(cv: Canvas, e: Env, t: Text) {
    val bg = t.bg
    if (bg != null) {
        val (x0, y0, x1, y1) = t.bbox
        val fold = minOf(t.size * 0.9, (x1 - x0) / 4, (y1 - y0) / 4)
        val slip = Path()
        slip.m(x0, y0, e)
        slip.l(x1, y0, e)
        slip.l(x1, y1 - fold, e)
        slip.l(x1 - fold, y1, e)
        slip.l(x0, y1, e)
        slip.close()
        cv.drawPath(slip, fillPaint(argb(bg)))
        val corner = Path()
        corner.m(x1, y1 - fold, e)
        corner.l(x1 - fold, y1 - fold, e)
        corner.l(x1 - fold, y1, e)
        corner.close()
        cv.drawPath(corner, fillPaint(argb(doubleArrayOf(bg[0] * 0.86, bg[1] * 0.86, bg[2] * 0.86))))
    }
    showText(cv, t.x - e.ox, t.y - e.oy, t.text, t.size, t.wrap, argb(t.color))
}

fun drawImage(cv: Canvas, e: Env, im: Image) {
    val bmp = Pictures.bitmap(im.data) ?: return
    val dst = RectF((im.x - e.ox).toFloat(), (im.y - e.oy).toFloat(), (im.x + im.w - e.ox).toFloat(), (im.y + im.h - e.oy).toFloat())
    cv.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
}

fun drawItem(cv: Canvas, e: Env, it: Item) {
    when (it) {
        is Stroke -> drawStroke(cv, e, it)
        is Shape -> drawShape(cv, e, it)
        is Text -> drawText(cv, e, it)
        is Image -> drawImage(cv, e, it)
        is BoxItem -> {}
    }
}

// -- paper ---------------------------------------------------------------------------------

/** Rule colour that suits a paper colour. */
private fun rule(c: Color, strong: Boolean = false): Color {
    if ((c[0] + c[1] + c[2]) / 3 > 0.5) {
        val k = if (strong) 0.72 else 1.0
        return doubleArrayOf(c[0] * 0.80 * k, c[1] * 0.84 * k, c[2] * 0.90 * k)
    }
    val lift = if (strong) 0.38 else 0.2
    return DoubleArray(3) { min(1.0, c[it] + lift) }
}

private fun hlines(path: Path, e: Env, x0: Double, x1: Double, y0: Double, y1: Double, step: Double, top: Double? = null) {
    var y = y0
    if (top != null && top > y0) y = y0 + ceil((top - y0) / step) * step
    while (y <= y1) {
        path.m(x0, y, e)
        path.l(x1, y, e)
        y += step
    }
}

private fun rectPath(path: Path, e: Env, x: Double, y: Double, w: Double, h: Double) {
    path.addRect((x - e.ox).toFloat(), (y - e.oy).toFloat(), (x + w - e.ox).toFloat(), (y + h - e.oy).toFloat(), Path.Direction.CW)
}

/** A whiteboard's background over box. The board has no edges, so the pattern simply continues
 *  wherever it is looked at, negative coordinates included. */
private fun boardPaper(cv: Canvas, e: Env, page: Page, box: DoubleArray) {
    val (x0, y0, x1, y1) = box
    cv.drawRect((x0 - e.ox).toFloat(), (y0 - e.oy).toFloat(), (x1 - e.ox).toFloat(), (y1 - e.oy).toFloat(), fillPaint(argb(page.color)))
    val kind = page.paper
    if (kind == "blank") return
    var pitch = when (kind) { "dots", "grid" -> 20.0; "wide" -> 32.0; else -> 22.0 }
    // Seen from far away the pattern would be millions of marks closer together than a pixel: thin
    // the dots out, and leave rulings off altogether.
    val perPixel = e.perPixel.takeIf { it > 0 } ?: 1.0
    var scale = 1.0
    if (pitch / perPixel < 6) {
        if (kind != "dots") return
        scale = 2.0.pow(ceil(ln(6 * perPixel / pitch) / ln(2.0)))
        pitch *= scale
    }
    cv.save()
    cv.clipRect((x0 - e.ox).toFloat(), (y0 - e.oy).toFloat(), (x1 - e.ox).toFloat(), (y1 - e.oy).toFloat())
    val color = argb(rule(page.color))
    val fx = floor(x0 / pitch) * pitch
    val fy = floor(y0 / pitch) * pitch
    val path = Path()
    if (kind == "dots") {
        val size = 1.6 * scale
        var y = fy
        while (y < y1 + 1) {
            var x = fx
            while (x < x1 + 1) {
                rectPath(path, e, x - size / 2, y - size / 2, size, size)
                x += pitch
            }
            y += pitch
        }
        cv.drawPath(path, fillPaint(color))
    } else {
        var y = fy
        while (y < y1 + 1) {
            path.m(x0, y, e)
            path.l(x1, y, e)
            y += pitch
        }
        if (kind == "grid") {
            var x = fx
            while (x < x1 + 1) {
                path.m(x, y0, e)
                path.l(x, y1, e)
                x += pitch
            }
        }
        cv.drawPath(path, strokePaint(color, 0.5).apply { strokeCap = Paint.Cap.BUTT })
    }
    cv.restore()
}

/** Paint the paper; [box] limits the work to one area of the page. */
fun drawPaper(cv: Canvas, e: Env, page: Page, box: DoubleArray? = null) {
    if (page.board) {
        boardPaper(cv, e, page, box ?: pageBox(page))
        return
    }
    val w = page.w
    val h = page.h
    val kind = page.paper
    val b = box ?: doubleArrayOf(0.0, 0.0, w, h)
    val bx0 = max(0.0, b[0])
    val by0 = max(0.0, b[1])
    val bx1 = min(w, b[2])
    val by1 = min(h, b[3])
    cv.drawRect((bx0 - e.ox).toFloat(), (by0 - e.oy).toFloat(), (bx1 - e.ox).toFloat(), (by1 - e.oy).toFloat(), fillPaint(argb(page.color)))
    if (kind == "blank") return
    val pitch = 20.0
    cv.save()
    cv.clipRect((bx0 - e.ox).toFloat(), (by0 - e.oy).toFloat(), (bx1 - e.ox).toFloat(), (by1 - e.oy).toFloat())
    val ruleColor = argb(rule(page.color))
    val strong = argb(rule(page.color, true))
    val thin = strokePaint(ruleColor, 0.5).apply { strokeCap = Paint.Cap.BUTT }
    fun line(path: Path, ax: Double, ay: Double, bx: Double, by: Double) {
        path.m(ax, ay, e)
        path.l(bx, by, e)
    }
    val path = Path()
    when (kind) {
        "dots" -> {
            val size = 1.2
            var y = max(pitch, floor(by0 / pitch) * pitch)
            while (y < by1 + 1) {
                var x = max(pitch, floor(bx0 / pitch) * pitch)
                while (x < bx1 + 1) {
                    rectPath(path, e, x - size / 2, y - size / 2, size, size)
                    x += pitch
                }
                y += pitch
            }
            cv.drawPath(path, fillPaint(ruleColor))
        }
        "grid" -> {
            hlines(path, e, bx0, bx1, 20.0, by1, 20.0, by0)
            var x = max(20.0, floor(bx0 / 20) * 20)
            while (x < bx1 + 1) {
                line(path, x, by0, x, by1)
                x += 20
            }
            cv.drawPath(path, thin)
        }
        "cornell" -> {
            val top = h * 0.09
            val cue = w * 0.28
            val bottom = h * 0.80
            hlines(path, e, 0.0, w, top + 22, bottom - 4, 22.0)
            cv.drawPath(path, thin)
            val s = Path()
            hlines(s, e, 0.0, w, top, top, 1.0)
            hlines(s, e, 0.0, w, bottom, bottom, 1.0)
            line(s, cue, top, cue, bottom)
            cv.drawPath(s, strokePaint(strong, 0.9).apply { strokeCap = Paint.Cap.BUTT })
        }
        "todo" -> {
            var y = 92.0
            while (y < h - 36) {
                rectPath(path, e, 36.0, y - 11, 10.0, 10.0)
                line(path, 56.0, y, w - 36, y)
                y += 28
            }
            cv.drawPath(path, thin)
            val s = Path()
            hlines(s, e, 36.0, w - 36, 60.0, 60.0, 1.0)
            cv.drawPath(s, strokePaint(strong, 0.9).apply { strokeCap = Paint.Cap.BUTT })
        }
        "weekly" -> {
            val m = 30.0
            val top = 56.0
            val cw = (w - 2 * m) / 2
            val ch = (h - top - m) / 4
            val p = strokePaint(strong, 0.8).apply { strokeCap = Paint.Cap.BUTT }
            listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日", "备注").forEachIndexed { n, name ->
                val x = m + (n % 2) * cw
                val y = top + (n / 2) * ch
                val r = Path()
                rectPath(r, e, x, y, cw, ch)
                cv.drawPath(r, p)
                showText(cv, x + 6 - e.ox, y + 5 - e.oy, tr(name), 9.0, 0.0, strong)
            }
        }
        "staff" -> {
            // Music manuscript: staves of five lines.
            val gap = 7.0
            val step = 7.0 * 4 + 38
            var y = 78.0
            while (y + gap * 4 < h - 44) {
                for (i in 0 until 5) line(path, 42.0, y + i * gap, w - 42, y + i * gap)
                line(path, 42.0, y, 42.0, y + gap * 4)
                line(path, w - 42, y, w - 42, y + gap * 4)
                y += step
            }
            cv.drawPath(path, strokePaint(strong, 0.6).apply { strokeCap = Paint.Cap.BUTT })
        }
        "go" -> {
            // A 19 x 19 goban with star points, and lines underneath for commentary.
            val side = min(w - 80, (h - 130) * 0.74)
            val cell = side / 18
            val x0 = (w - side) / 2
            val y0 = 64.0
            for (i in 0 until 19) {
                line(path, x0 + i * cell, y0, x0 + i * cell, y0 + side)
                line(path, x0, y0 + i * cell, x0 + side, y0 + i * cell)
            }
            cv.drawPath(path, strokePaint(strong, 0.55).apply { strokeCap = Paint.Cap.BUTT })
            val frame = Path()
            rectPath(frame, e, x0, y0, side, side)
            cv.drawPath(frame, strokePaint(strong, 1.3).apply { strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.MITER })
            val dot = fillPaint(strong)
            for (i in listOf(3, 9, 15)) for (j in listOf(3, 9, 15)) {
                cv.drawCircle((x0 + i * cell - e.ox).toFloat(), (y0 + j * cell - e.oy).toFloat(), max(1.3, cell * 0.11).toFloat(), dot)
            }
            val notes = Path()
            hlines(notes, e, x0, x0 + side, y0 + side + 34, h - 30, 22.0)
            cv.drawPath(notes, thin)
        }
        else -> {
            val wide = kind == "wide"
            val first = if (kind == "legal") 70.0 else 50.0
            hlines(path, e, bx0, bx1, first, min(h - 8, by1), if (wide) 32.0 else 22.0, by0)
            cv.drawPath(path, thin)
            if (kind == "legal") {
                val margin = Path()
                for (x in listOf(w * 0.13, w * 0.13 + 3)) line(margin, x, 0.0, x, h)
                cv.drawPath(margin, strokePaint(argb(doubleArrayOf(0.86, 0.42, 0.42)), 0.7).apply { strokeCap = Paint.Cap.BUTT })
            } else if (kind == "col2" || kind == "col3") {
                val n = kind[3].digitToInt()
                val cols = Path()
                for (i in 1 until n) line(cols, w * i / n, 36.0, w * i / n, h - 24)
                cv.drawPath(cols, strokePaint(strong, 0.8).apply { strokeCap = Paint.Cap.BUTT })
            }
        }
    }
    cv.restore()
}

/**
 * Draw background and items. The canvas is in page points (its matrix has the zoom and at most a
 * small shift; the origin is subtracted in the drawing functions). [background] draws the PDF page
 * under the ink: it is rendered elsewhere, and cached.
 */
fun renderPage(cv: Canvas, page: Page, ox: Double = 0.0, oy: Double = 0.0, perPixel: Double = 1.0, printing: Boolean = false,
               paper: Boolean = true, ink: Boolean = true, items: List<Item>? = null, background: ((Canvas) -> Unit)? = null) {
    val box = pageBox(page)
    val e = Env(ox, oy, perPixel, inkOn(page), printing)
    if (paper) {
        if (page.pdf == null) {
            drawPaper(cv, e, page, box)
        } else {
            if (!printing) cv.drawRect((-ox).toFloat(), (-oy).toFloat(), (page.w - ox).toFloat(), (page.h - oy).toFloat(), fillPaint(android.graphics.Color.WHITE))
            background?.invoke(cv)
        }
    }
    cv.save()
    cv.clipRect((box[0] - ox).toFloat(), (box[1] - oy).toFloat(), (box[2] - ox).toFloat(), (box[3] - oy).toFloat())
    if (ink) {
        if (!paper) e.onDark = true   // nothing underneath to multiply with
        for (it in items ?: page.items) drawItem(cv, e, it)
    }
    cv.restore()
}

/** Raster of a page (a whiteboard's content); [size] letterboxes it into fixed pixels. */
fun pageBitmap(page: Page, scaleIn: Double, size: Pair<Int, Int>? = null, paper: Boolean = true, ink: Boolean = true,
               background: ((Canvas, Double, Double) -> Unit)? = null): Bitmap {
    val (x0, y0, x1, y1) = pageBox(page)
    val w: Int
    val h: Int
    val sx: Double
    val sy: Double
    val dx: Double
    val dy: Double
    if (size == null) {
        // An image has to stay a size that fits in memory, however far a whiteboard sprawls.
        val scale = min(scaleIn, 12000 / max(x1 - x0, y1 - y0))
        w = max(1, ((x1 - x0) * scale).roundToInt())
        h = max(1, ((y1 - y0) * scale).roundToInt())
        sx = w / (x1 - x0)
        sy = h / (y1 - y0)
        dx = 0.0
        dy = 0.0
    } else {
        w = size.first
        h = size.second
        sx = min(w / (x1 - x0), h / (y1 - y0))
        sy = sx
        dx = (w - (x1 - x0) * sx) / 2
        dy = (h - (y1 - y0) * sy) / 2
    }
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    if (size != null) cv.drawColor(argb(page.color))
    // The shift into the content's box is done on page coordinates (the origin), never as a translate of the matrix.
    cv.translate(dx.toFloat(), dy.toFloat())
    cv.scale(sx.toFloat(), sy.toFloat())
    renderPage(cv, page, x0, y0, 1 / min(sx, sy), paper = paper, ink = ink, background = background?.let { bg -> { c: Canvas -> bg(c, sx, sy) } })
    return bmp
}

// -- covers --------------------------------------------------------------------------------

private fun coverText(cv: Canvas, x: Double, y: Double, title: String, size: Double, width: Double, ink: Color, alpha: Double = 0.92) {
    showText(cv, x, y, title, size, width, argb(ink, alpha))
}

private fun roundRect(x: Double, y: Double, w: Double, h: Double, r: Double) =
    Path().apply { addRoundRect(RectF(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat()), r.toFloat(), r.toFloat(), Path.Direction.CW) }

/** A notebook cover in one of COVER_STYLES, carrying the title. */
fun renderCover(style: String, color: Color, title: String, w: Int = 225, h: Int = 300): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = Canvas(bmp)
    val W = w.toDouble()
    val H = h.toDouble()
    val light = 0.299 * color[0] + 0.587 * color[1] + 0.114 * color[2] > 0.6
    val ink = if (light) doubleArrayOf(0.12, 0.12, 0.14) else doubleArrayOf(1.0, 1.0, 1.0)
    val dark = DoubleArray(3) { color[it] * 0.78 }
    val black = doubleArrayOf(0.12, 0.12, 0.14)
    val white = doubleArrayOf(1.0, 1.0, 1.0)
    cv.drawColor(argb(color))
    fun rect(x: Double, y: Double, rw: Double, rh: Double, c: Int) = cv.drawRect(x.toFloat(), y.toFloat(), (x + rw).toFloat(), (y + rh).toFloat(), fillPaint(c))
    when (style) {
        "grid" -> {
            val step = W / 12
            val p = Path()
            for (i in 1 until 12) { p.moveTo((i * step).toFloat(), 0f); p.lineTo((i * step).toFloat(), h.toFloat()) }
            for (i in 1..(H / step).toInt()) { p.moveTo(0f, (i * step).toFloat()); p.lineTo(w.toFloat(), (i * step).toFloat()) }
            cv.drawPath(p, strokePaint(argb(ink, 0.10), 0.6))
            rect(W * 0.14, H * 0.12, W * 0.72, H * 0.20, argb(if (light) white else dark))
            coverText(cv, W * 0.19, H * 0.17, title, W * 0.075, W * 0.62, if (light) black else white)
        }
        "band" -> {
            rect(0.0, H * 0.66, W, H * 0.34, argb(dark))
            rect(W * 0.14, H * 0.33, W * 0.16, 2.2, argb(ink, 0.5))
            coverText(cv, W * 0.14, H * 0.37, title, W * 0.09, W * 0.76, ink)
        }
        "ribbon" -> {
            // A loose ribbon looping across the cover, with a label for the title.
            val accent = if (!light) doubleArrayOf(0.22, 0.70, 0.84) else DoubleArray(3) { color[it] * 0.55 }
            val p = Path()
            p.moveTo((W * 0.20).toFloat(), (-H * 0.05).toFloat())
            p.cubicTo((-W * 0.10).toFloat(), (H * 0.30).toFloat(), (W * 0.45).toFloat(), (H * 0.52).toFloat(), (W * 0.52).toFloat(), (H * 0.36).toFloat())
            p.cubicTo((W * 0.58).toFloat(), (H * 0.22).toFloat(), (W * 0.30).toFloat(), (H * 0.24).toFloat(), (W * 0.42).toFloat(), (H * 0.52).toFloat())
            p.cubicTo((W * 0.60).toFloat(), (H * 0.92).toFloat(), (W * 0.95).toFloat(), (H * 0.70).toFloat(), (W * 1.08).toFloat(), (H * 0.98).toFloat())
            cv.drawPath(p, strokePaint(argb(accent), W * 0.085))
            cv.drawPath(roundRect(W * 0.16, H * 0.39, W * 0.68, H * 0.21, 4.0), fillPaint(argb(white)))
            coverText(cv, W * 0.21, H * 0.425, title, W * 0.07, W * 0.58, black)
        }
        "label" -> {
            cv.drawRect((W * 0.09).toFloat(), (H * 0.05).toFloat(), (W * 0.93).toFloat(), (H * 0.95).toFloat(), strokePaint(argb(ink, 0.28), 1.0).apply { strokeJoin = Paint.Join.MITER })
            cv.drawPath(roundRect(W * 0.20, H * 0.26, W * 0.62, H * 0.24, 3.0), fillPaint(argb(doubleArrayOf(0.98, 0.97, 0.94))))
            cv.drawPath(roundRect(W * 0.225, H * 0.275, W * 0.57, H * 0.21, 2.0), strokePaint(argb(doubleArrayOf(0.0, 0.0, 0.0), 0.25), 0.8))
            coverText(cv, W * 0.26, H * 0.32, title, W * 0.072, W * 0.50, black)
        }
        else -> {
            val left = W * 0.15
            coverText(cv, left, H * 0.13, title, W * 0.075, W * 0.75, ink)
            val p = Path()
            p.moveTo(left.toFloat(), (H * 0.30).toFloat())
            p.lineTo((W * 0.9).toFloat(), (H * 0.30).toFloat())
            for (i in 0 until 3) {
                p.moveTo((W * 0.72).toFloat(), (H * (0.84 + i * 0.03)).toFloat())
                p.lineTo((W * 0.9).toFloat(), (H * (0.84 + i * 0.03)).toFloat())
            }
            cv.drawPath(p, strokePaint(argb(ink, 0.35), 1.0).apply { strokeCap = Paint.Cap.BUTT })
        }
    }
    // Shared finish: a soft sheen and the pale spine.
    val sheen = Paint().apply {
        shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0x1AFFFFFF, 0x1A000000, Shader.TileMode.CLAMP)
    }
    cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sheen)
    rect(0.0, 0.0, W * 0.045, H, argb(white, 0.92))
    rect(W * 0.045, 0.0, 1.5, H, argb(doubleArrayOf(0.0, 0.0, 0.0), 0.16))
    return bmp
}
