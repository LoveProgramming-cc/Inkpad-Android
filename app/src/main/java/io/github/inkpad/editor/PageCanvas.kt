package io.github.inkpad.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import io.github.inkpad.core.Box
import io.github.inkpad.core.Document
import io.github.inkpad.core.Event
import io.github.inkpad.core.Geom
import io.github.inkpad.core.Item
import io.github.inkpad.core.Page
import io.github.inkpad.core.pageBox
import io.github.inkpad.pdf.PdfPages
import io.github.inkpad.render.Env
import io.github.inkpad.render.argb
import io.github.inkpad.render.drawItem
import io.github.inkpad.render.drawPaper
import io.github.inkpad.render.inkOn
import java.util.IdentityHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

// The scrolling page canvas (B:canvas.py Canvas; W:src/editor/canvas.js and input.js).
//
// Performance model: every visible page is rasterised once into a cache bitmap and drawn as one,
// so scrolling and zooming only move bitmaps around. While writing, only the growing tail of the
// stroke is rasterised per frame; finished ink is painted into the page cache once. Edits repaint
// just the affected rectangle.
//
// Everything here is measured in dp, as the web version measures in CSS pixels: the constants
// (gaps, dead zones, reach) keep the sizes they were tuned at. `dev` is pixels per dp.

const val TOP = 64.0
const val GAP = 14.0
const val SIDE = 24.0
const val BOTTOM = 48.0
const val MAX_PIXELS = 12_000_000   // pages bigger than this on screen are cached as a window around the viewport
const val STRIP = 256               // windows are aligned to this many pixels, so they need not move at every scroll
const val MIN_ZOOM = 0.2
const val BOARD_MIN_ZOOM = 0.04
const val BG_KEEP = 8               // PDF page backgrounds remembered after they scroll out of view
const val CHUNK = 48                // live stroke points rasterised per baked chunk
const val LASER_HOLD_MS = 1000L     // a laser trail stays this long after the last stroke
const val LASER_FADE_MS = 350L
const val RULER_W = 46.0            // half the ruler's width
const val RULER_SNAP = 18.0         // how near its edge a stroke has to start to follow it
const val CACHE_LIMIT = 40_000_000  // the most pixels all the page caches together may hold
const val ACCENT = 0xFF3685E3.toInt()
const val MARK = 0x59FFD91A
const val MARK_NOW = 0x80FF8000.toInt()

private fun clamp(v: Double, lo: Double, hi: Double) = max(lo, min(hi, v))
private fun mono() = SystemClock.uptimeMillis()

/** Index of the first element greater than x (Python's bisect_right). */
private fun bisectRight(arr: List<Double>, x: Double): Int {
    var lo = 0
    var hi = arr.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (x < arr[mid]) hi = mid else lo = mid + 1
    }
    return lo
}

/** The raster cache of one page: a window (ox, oy, pw, ph) of the page drawn at full pixels. */
class Entry(val bitmap: Bitmap, val full: Pair<Int, Int>, val ox: Int, val oy: Int, val pw: Int, val ph: Int, val sx: Double, val sy: Double, val bg: Bitmap?) {
    val canvas = Canvas(bitmap)
}

/** What the canvas tells the editor around it. */
interface CanvasHost {
    fun viewChanged() {}
    fun selectionChanged() {}
    fun selectionMade() {}
    fun menuWanted(x: Double, y: Double) {}
    fun scribbled(n: Int) {}
    fun eraseDone() {}
    fun pullAdd(direction: Int) {}
    /** Ask for text at a point of the view: [existing] is the text being edited, or null. */
    fun editText(x: Double, y: Double, existing: String?, done: (String?) -> Unit) {}
    fun log(line: String) {}
}

@SuppressLint("ViewConstructor")
class PageCanvas(context: Context, val host: CanvasHost) : View(context) {
    internal var dev = resources.displayMetrics.density.toDouble()
    internal var W = 0.0
    internal var H = 0.0

    var doc: Document? = null
        private set
    private var unlisten: (() -> Unit)? = null
    var zoom = 1.0
        internal set
    internal var renderZoom = 1.0
    internal var zoomInit = false
    internal var tops = ArrayList<Double>()
    internal var cw = 0.0
    internal var ch = 0.0
    internal val cache = IdentityHashMap<Page, Entry>()
    internal var shown = Pair(0, 0)
    internal var op: Op? = null
    internal var sel: Selection? = null
    internal var hidden: MutableSet<Item>? = null
    internal var clip: List<Item> = emptyList()
    internal val handler = Handler(Looper.getMainLooper())

    // Tool configuration, written by the editor.
    var tool = "pen"
        private set
    var penType = "fountain"
    var penColor = doubleArrayOf(0.0, 0.0, 0.0)
    var penWidth = 1.6
    var hlColor = doubleArrayOf(1.0, 0.88, 0.3)
    var hlWidth = 14.0
    var eraserR = 9.0
    var shapeKind = "line"
    var textSize = 18.0
    var scribbleErase = true
    var scribbleLoose = false
    var fingerDraw = false
    var penSens = 1.0              // pressure sensitivity factor
    var penDash = 0                // 0 solid, 1 dashed, 2 dotted
    var penStab = 0.0              // stroke stabilisation, 0..1
    var holdShape = true           // hold still at the end of a stroke to snap it to a shape
    var eraserMode = "standard"    // "standard" erases what is touched, "stroke" whole strokes
    var eraseFilter = setOf("pen", "hl", "shape")
    var lassoRect = false
    var lassoFilter = setOf("ink", "image", "shape", "text")
    var noteColor = doubleArrayOf(1.0, 0.93, 0.55)
    var tapeColor = doubleArrayOf(0.96, 0.82, 0.36)
    var tapeWidth = 16.0
    var mouseHand = true           // once a pen has been used, the mouse moves the page instead of drawing
    var showCross = true
    var laserMode = "trail"        // "dot" follows the pen, "trail" leaves a line that fades
    var laserColor = doubleArrayOf(1.0, 0.16, 0.2)
    var scrollbars = true
    var maxZoom = 6.0
    var palmMs = 80L               // touches this soon after a pen event are the resting hand
    var inputLog = false
    var penButtonErases = true     // the stylus button held down erases
    var canvasBg = 0xFFE1E1E2.toInt()

    internal var holdPending: Runnable? = null
    internal var pressPending: Runnable? = null
    internal var pressAt = Pair(0.0, 0.0)
    internal var hasPen = false
    internal var hover: Pair<Double, Double>? = null
    internal val marks = ArrayList<Pair<Page, Box>>()   // search hits: page, box in page points
    internal var markAt = -1
    internal val laserTrails = ArrayList<Pair<Page, MutableList<DoubleArray>>>()
    internal var laserDot: Triple<Page, Double, Double>? = null
    internal var laserAlpha = 1.0
    internal var laserTimer: Runnable? = null
    internal var laserFading = false
    internal var ruler: Ruler? = null
    internal var src = "pen"                     // what is drawing: "pen", "mouse" or "touch"
    var paging = "vertical"                      // pages run downwards, or "horizontal": side by side
        private set
    internal var lefts = ArrayList<Double>()
    internal var laidFor = 0.0
    internal var memo: List<Double>? = null      // a remembered view waiting for the window to have a size
    internal var barDrag: DoubleArray? = null
    internal var barAxis = ""
    internal val pf = IdentityHashMap<Page, Double>()   // side by side: each page's own scale beside the zoom
    internal var slots = ArrayList<Double>()
    internal var pull = 0.0
    internal var turning = 0
    internal var want = doubleArrayOf(0.0, 0.0)
    internal var board = false                   // showing a whiteboard: one page, no edges, free scrolling
    internal val bo = doubleArrayOf(0.0, 0.0)    // its scroll position; any value, either sign
    internal var bat = 0                         // which of the document's boards is shown
    internal var sx = 0.0                        // the scroll position of pages that are laid out
    internal var sy = 0.0
    internal var anim: Runnable? = null
    internal var settle: Runnable? = null

    // -- setup ---------------------------------------------------------------------------------

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Whether something is being written, erased or moved right now. */
    fun busy() = op != null

    fun log(text: String) {
        if (inputLog) host.log("%.3f %s".format(mono() / 1000.0, text))
    }

    fun setDocument(d: Document) {
        unlisten?.invoke()
        doc = d
        // On the main thread at once: a finished stroke goes into the page's picture in the same
        // frame as the live one goes away, or it would blink. (Sync merges arrive from elsewhere.)
        unlisten = d.listen { e -> if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) docEvent(e) else handler.post { docEvent(e) } }
        for (e in cache.values) e.bitmap.recycle()
        cache.clear()
        pending.clear()
        bgs.clear()
        op = null
        sel = null
        hidden = null
        bat = 0
        laserTrails.clear()
        laserDot = null
        marks.clear()
        markAt = -1
        relayout()
        memo = null
        if (zoomInit) {
            zoomInit = false
            initZoom()
            home()
        } else if (W > 200) {
            allocated()
        }
        invalidate()
        host.viewChanged()
    }

    fun release() {
        unlisten?.invoke()
        unlisten = null
        handler.removeCallbacksAndMessages(null)
        for (e in cache.values) e.bitmap.recycle()
        cache.clear()
    }

    /** Where the view is, in a form that outlasts the window: the zoom and the point of the
     *  document in the middle of the window. */
    fun viewMemo(): List<Double>? {
        if (doc == null || tops.isEmpty() || !zoomInit) return null
        val (i, fx, fy) = anchor(W / 2, H / 2)
        return listOf(zoom, i.toDouble(), fx, fy)
    }

    /** Go back to a place remembered with viewMemo(), now or once there is a window. */
    fun restoreMemo(m: List<Double>) {
        if (m.size < 4) return
        val z = m[0]
        val i = m[1].toInt()
        val fx = m[2]
        val fy = m[3]
        val d = doc ?: return
        if (i !in d.pages.indices || !(z > 0) || !fx.isFinite() || !fy.isFinite()) return
        if (!zoomInit) {
            memo = m
            return
        }
        if (board) bat = i
        place(clamp(z, minZoom(), maxZoomNow()), Triple(i, fx, fy), W / 2, H / 2)
        renderZoom = zoom
        rest()
        invalidate()
        host.viewChanged()
    }

    fun setTool(t: String) {
        tool = t
        if (t != "lasso") clearSelection()
        if (t != "laser") clearLaser()
        invalidate()
    }

    // -- layout and scrolling ---------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val (oldW, oldH) = Pair(W, H)
        dev = resources.displayMetrics.density.toDouble()
        W = w / dev
        H = h / dev
        layer = null
        allocated()
        // Turning the tablet: what was in the middle of a board stays in the middle.
        if (board && zoomInit && oldw > 0 && oldh > 0) configureAdj(bo[0] + (oldW - W) / 2, bo[1] + (oldH - H) / 2)
        invalidate()
    }

    private fun allocated() {
        if (!zoomInit && W > 200 && doc != null) {
            initZoom()
            home()
            memo?.let { m ->
                memo = null
                configureAdj()
                restoreMemo(m)
            }
        }
        if (across() && doc != null && slots.isNotEmpty() && W != laidFor) {
            // The pages are a window apart, so a new width moves them all.
            val i = currentPageIndex()
            relayout()
            configureAdj(slots[min(i, slots.size - 1)])
        }
        configureAdj()
    }

    private fun initZoom() {
        zoomInit = true
        val d = doc ?: return
        // Fitted to the usual page: one odd, huge page must not shrink all the others.
        val (rw, rh) = usual()
        var fit = (W - 2 * SIDE) / rw
        if (paging == "horizontal") fit = min(fit, (H - TOP - BOTTOM) / rh)   // a whole page at a time
        zoom = if (d.pages[0].board) 1.0 else clamp(fit, MIN_ZOOM, 1.35)
        renderZoom = zoom
        relayout()
        host.viewChanged()
    }

    /** The size of the notebook's usual page: the middle one of its widths and heights. */
    private fun usual(): Pair<Double, Double> {
        val pages = doc!!.pages
        val ws = pages.map { it.w }.sorted()
        val hs = pages.map { it.h }.sorted()
        return Pair(ws[pages.size / 2], hs[pages.size / 2])
    }

    /** The scale a page is shown at. Side by side every page is seen alone, so a page of another
     *  size is scaled to fill the same space as the usual one. */
    internal fun z(page: Page) = zoom * (pf[page] ?: 1.0)

    fun shownZoom(): Double {
        val d = doc ?: return zoom
        if (tops.isEmpty()) return zoom
        return z(d.pages[min(currentPageIndex(), d.pages.size - 1)])
    }

    /** Initial scroll position: the top, or the content of a whiteboard. */
    private fun home() {
        if (doc!!.pages[0].board) centerOn(bat) else configureAdj(0.0, 0.0)
    }

    /** Where a page's left edge is in the scrolled content: centred, or a board's origin. */
    private fun left(page: Page) = if (board) 0.0 else (max(W, cw) - page.w * zoom) / 2

    fun centerOn(i: Int) {
        val page = doc!!.pages[i]
        val (x0, y0, x1, y1) = pageBox(page)
        val (l, t) = origin(i)
        configureAdj(l + (x0 + x1) / 2 * zoom - W / 2, t + (y0 + y1) / 2 * zoom - H / 2)
    }

    internal fun relayout() {
        var y = TOP
        tops = ArrayList()
        var width = 0.0
        val d = doc
        board = d != null && d.pages[0].board
        pf.clear()
        if (board && d != null) {
            // Nothing to lay out: the one board on show starts at the origin and never ends.
            bat = min(bat, d.pages.size - 1)
            tops = ArrayList(d.pages.map { 0.0 })
            cw = 0.0
            ch = 0.0
            return
        }
        if (d != null && paging == "horizontal") {
            // One page at a time: each has a slot as wide as the window, wider when zoomed in.
            var x = 0.0
            lefts = ArrayList()
            slots = ArrayList()
            laidFor = W
            var height = 0.0
            val (rw, rh) = usual()
            for (page in d.pages) {
                pf[page] = min(rw / page.w, rh / page.h)
                val zz = z(page)
                val slot = max(W, page.w * zz + 2 * SIDE)
                slots.add(x)
                lefts.add(x + (slot - page.w * zz) / 2)
                tops.add(TOP)
                x += slot
                height = max(height, page.h * zz)
            }
            cw = x
            ch = height + TOP + BOTTOM
            return
        }
        if (d != null) {
            for (page in d.pages) {
                tops.add(y)
                y += page.h * zoom + GAP
                width = max(width, page.w)
            }
        }
        ch = y - GAP + BOTTOM
        cw = width * zoom + 2 * SIDE
    }

    internal fun across() = paging == "horizontal" && !board

    /** Where page i's top left corner is in the scrolled content. */
    internal fun origin(i: Int): Pair<Double, Double> {
        val page = doc!!.pages[i]
        if (across()) return Pair(lefts[i], max(TOP, (max(H, ch) - page.h * z(page)) / 2))
        return Pair(left(page), tops[i])
    }

    /** The page whose row or column holds a point of the scrolled content. */
    internal fun pageAt(x: Double, y: Double): Int {
        if (board) return bat
        if (across()) return max(0, bisectRight(slots, x) - 1)
        return max(0, bisectRight(tops, y) - 1)
    }

    fun setPaging(p: String) {
        if (p == paging) return
        var i = if (doc != null && tops.isNotEmpty()) currentPageIndex() else 0
        paging = p
        val d = doc ?: return
        initZoom()
        configureAdj()
        if (!board) {
            i = min(i, d.pages.size - 1)
            configureAdj(if (across()) slots[i] else 0.0, if (across()) 0.0 else tops[i] - TOP)
        }
        invalidate()
    }

    internal fun hUpper() = max(cw, W)
    internal fun vUpper() = max(ch, H)

    /** Set the scroll position, kept within what there is to scroll. */
    internal fun configureAdj(hval: Double? = null, vval: Double? = null) {
        if (board) {
            val bx = bo[0]
            val by = bo[1]
            if (hval != null) bo[0] = hval
            if (vval != null) bo[1] = vval
            if (bo[0] != bx || bo[1] != by) {
                invalidate()
                host.viewChanged()
            }
            return
        }
        val nx = max(0.0, min(hval ?: sx, hUpper() - W))
        val ny = max(0.0, min(vval ?: sy, vUpper() - H))
        val moved = nx != sx || ny != sy
        sx = nx
        sy = ny
        if (moved) {
            invalidate()
            host.viewChanged()
        }
    }

    internal fun setAdj(axis: Char, v: Double) = if (axis == 'h') configureAdj(v, null) else configureAdj(null, v)

    internal fun h() = if (board) bo[0] else sx
    internal fun v() = if (board) bo[1] else sy

    private fun snap(v: Double) = (v * dev).roundToInt() / dev

    /** Page rectangle in view coordinates (dp), aligned to device pixels. */
    internal fun pageRect(i: Int): DoubleArray {
        val page = doc!!.pages[i]
        if (board) {
            // Only the origin and the scale matter; the board itself has no rectangle.
            return doubleArrayOf(snap(-bo[0]), snap(-bo[1]), page.w * zoom, page.h * zoom)
        }
        val w = snap(page.w * z(page))
        val hh = snap(page.h * z(page))
        if (across()) {
            val (ox, oy) = origin(i)
            return doubleArrayOf(snap(ox - h()), snap(oy - v()), w, hh)
        }
        val x = (max(W, cw) - w) / 2 - h()
        return doubleArrayOf(snap(x), snap(tops[i] - v()), w, hh)
    }

    internal fun toPage(i: Int, x: Double, y: Double): Pair<Double, Double> {
        val r = pageRect(i)
        val page = doc!!.pages[i]
        return Pair((x - r[0]) * page.w / r[2], (y - r[1]) * page.h / r[3])
    }

    /** The page under a view point and the point on it, or null. */
    internal fun locate(x: Double, y: Double): Triple<Int, Double, Double>? {
        if (tops.isEmpty() || doc == null) return null
        if (board) {
            val (px, py) = toPage(bat, x, y)
            return Triple(bat, px, py)
        }
        val i = pageAt(x + h(), y + v())
        val r = pageRect(i)
        if (r[0] <= x && x <= r[0] + r[2] && r[1] <= y && y <= r[1] + r[3]) {
            val (px, py) = toPage(i, x, y)
            return Triple(i, px, py)
        }
        return null
    }

    internal fun index(page: Page): Int? = doc?.pages?.indexOfFirst { it === page }?.takeIf { it >= 0 }

    fun currentPageIndex(): Int {
        if (tops.isEmpty()) return 0
        if (board) return bat
        return pageAt(h() + W / 2, v() + H / 2)
    }

    fun scrollToPage(i: Int) {
        // Pages just added are laid out when their event comes round; going to one cannot wait.
        if (i !in tops.indices && doc?.pages?.size != tops.size) {
            relayout()
            configureAdj()
        }
        if (i !in tops.indices) return
        if (board) {
            bat = i
            centerOn(i)
            return
        }
        turning = i
        glide(if (across()) 'h' else 'v', if (across()) slots[i] else tops[i] - TOP)
    }

    /** Side by side: the scroll positions between which page i fills the window. They are one and
     *  the same when the whole page fits. */
    internal fun span(i: Int): Pair<Double, Double> {
        val page = doc!!.pages[i]
        val lo = slots[i]
        return Pair(lo, lo + max(0.0, page.w * z(page) + 2 * SIDE - W))
    }

    /** Scroll to a target with the 280 ms ease-out-cubic B uses. */
    internal fun glide(axis: Char, target0: Double) {
        val upper = if (axis == 'h') hUpper() - W else vUpper() - H
        val target = clamp(target0, 0.0, upper)
        anim?.let { removeCallbacks(it) }
        val from = if (axis == 'h') sx else sy
        val t0 = mono()
        val step = object : Runnable {
            override fun run() {
                val t = min(1.0, (mono() - t0) / 280.0)
                val e = 1 - (1 - t) * (1 - t) * (1 - t)
                setAdj(axis, from + (target - from) * e)
                if (t < 1) postOnAnimation(this) else anim = null
            }
        }
        anim = step
        postOnAnimation(step)
    }

    // -- zoom -----------------------------------------------------------------------------

    fun setZoom(z0: Double, ax: Double = W / 2, ay: Double = H / 2) {
        val zz = clamp(z0, minZoom(), maxZoomNow())
        if (doc == null || abs(zz - zoom) < 1e-4) return
        // Keep the page point under the anchor fixed.
        place(zz, anchor(ax, ay), ax, ay)
    }

    /** The limit is on what is seen, not on the number: a page shown smaller than others to fit
     *  beside them may be enlarged by as much more. */
    internal fun maxZoomNow(): Double {
        val d = doc ?: return maxZoom
        if (tops.isEmpty() || pf.isEmpty()) return maxZoom
        val page = d.pages[min(currentPageIndex(), d.pages.size - 1)]
        return maxZoom / (pf[page] ?: 1.0)
    }

    /** A whiteboard can be looked at from much further away than a page. */
    internal fun minZoom() = if (doc?.pages?.get(0)?.board == true) BOARD_MIN_ZOOM else MIN_ZOOM

    fun setMarks(m: List<Pair<Page, Box>>, at: Int = -1) {
        marks.clear()
        marks.addAll(m)
        markAt = at
        if (at in m.indices) {
            val (page, box) = m[at]
            index(page)?.let { centerAt(it, (box[0] + box[2]) / 2, (box[1] + box[3]) / 2) }
        }
        invalidate()
    }

    /** The part of the current page that is on screen, in page points (not clamped). */
    fun viewBox(): Box? {
        if (doc == null || tops.isEmpty()) return null
        val i = currentPageIndex()
        val (x0, y0) = toPage(i, 0.0, 0.0)
        val (x1, y1) = toPage(i, W, H)
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Scroll so that a page point is in the middle of the view. */
    fun centerAt(i: Int, x: Double, y: Double) {
        val page = doc!!.pages[i]
        place(zoom, Triple(i, x / page.w, y / page.h), W / 2, H / 2)
    }

    /** Zoom and scroll so that everything on the current whiteboard is visible. */
    fun fitContent() {
        val i = currentPageIndex()
        val page = doc!!.pages[i]
        val (x0, y0, x1, y1) = pageBox(page)
        val zz = clamp(min(W / (x1 - x0), H / (y1 - y0)) * 0.92, minZoom(), 1.5)
        place(zz, Triple(i, (x0 + x1) / 2 / page.w, (y0 + y1) / 2 / page.h), W / 2, H / 2)
    }

    /** The page point under a view point, as (index, fraction across, fraction down). */
    internal fun anchor(x: Double, y: Double): Triple<Int, Double, Double> {
        val i = pageAt(x + h(), y + v())
        val page = doc!!.pages[i]
        val (l, t) = origin(i)
        val zz = z(page)
        return Triple(i, (x - l + h()) / (page.w * zz), (y - t + v()) / (page.h * zz))
    }

    /** Show the document at [zz] with the anchored page point under view point (x, y). */
    internal fun place(zz: Double, a: Triple<Int, Double, Double>, x: Double, y: Double) {
        val (i, fx, fy) = a
        val d = doc!!
        if (i >= d.pages.size) return
        val page = d.pages[i]
        val changed = abs(zz - zoom) > 1e-6
        if (changed) {
            zoom = zz
            relayout()
        }
        val (l, t) = origin(i)
        val k = z(page)
        want = doubleArrayOf(l + fx * page.w * k - x, t + fy * page.h * k - y)
        if (across()) {
            // One page at a time: zooming about a point near the edge must not bring the next page in beside this one.
            val (lo, hi) = span(i)
            want[0] = clamp(want[0], lo, hi)
        }
        configureAdj(want[0], want[1])
        if (changed) zoomChanged()
    }

    internal fun zoomChanged() {
        settle?.let { handler.removeCallbacks(it) }
        // Images are stretched while zooming and rendered sharp again once it settles.
        val s = Runnable {
            settle = null
            renderZoom = zoom
            invalidate()
        }
        settle = s
        handler.postDelayed(s, 160)
        invalidate()
        host.viewChanged()
    }

    fun zoomBy(factor: Double) = setZoom(zoom * factor)

    fun fitWidth() {
        val d = doc ?: return
        val page = d.pages[currentPageIndex()]
        setZoom((W - 2 * SIDE) / (page.w * (pf[page] ?: 1.0)))
    }

    // -- ruler ----------------------------------------------------------------------------

    class Ruler(var x: Double, var y: Double, var a: Double)

    fun setRuler(on: Boolean) {
        ruler = if (on) Ruler(W / 2, H / 2, 0.0) else null
        invalidate()
    }

    val hasRuler get() = ruler != null

    internal fun rulerLen() = max(260.0, 0.42 * max(W, H))

    /** A view point as (distance along the ruler, distance across it) from its centre. */
    private fun rulerLocal(x: Double, y: Double): Pair<Double, Double> {
        val r = ruler!!
        val c = cos(r.a)
        val s = sin(r.a)
        val dx = x - r.x
        val dy = y - r.y
        return Pair(dx * c + dy * s, -dx * s + dy * c)
    }

    /** "edge" near one of the long edges, "body" on the ruler itself, null elsewhere. */
    internal fun rulerZone(x: Double, y: Double): String? {
        if (ruler == null) return null
        val (u, vv) = rulerLocal(x, y)
        if (abs(u) > rulerLen() + RULER_SNAP) return null
        if (abs(abs(vv) - RULER_W) <= RULER_SNAP) return "edge"
        return if (abs(vv) < RULER_W) "body" else null
    }

    /** The long edge nearest a point: a point on it and its direction. */
    internal fun rulerEdge(x: Double, y: Double): DoubleArray {
        val r = ruler!!
        val c = cos(r.a)
        val s = sin(r.a)
        val side = if (rulerLocal(x, y).second >= 0) 1.0 else -1.0
        return doubleArrayOf(r.x - s * RULER_W * side, r.y + c * RULER_W * side, c, s)
    }

    internal fun onto(line: DoubleArray, x: Double, y: Double): Pair<Double, Double> {
        val t = (x - line[0]) * line[2] + (y - line[1]) * line[3]
        return Pair(line[0] + t * line[2], line[1] + t * line[3])
    }

    internal fun turnRuler(angle: Double) {
        // Settle on the nearest 45 degrees when turned to within a degree and a half of it.
        val near = Math.round(angle / (Math.PI / 4)) * (Math.PI / 4)
        ruler?.a = if (abs(angle - near) < 1.5 * Math.PI / 180) near else angle
        invalidate()
    }

    // -- page cache ---------------------------------------------------------------------------

    internal fun fullSize(page: Page): Pair<Int, Int> {
        if (zoom < renderZoom / 1.5) {
            // Zooming far out: stretching the old raster would mean caching a huge area of it, so
            // step the raster down along the way instead of waiting for the zoom to settle.
            renderZoom = zoom
        }
        val s = renderZoom * (pf[page] ?: 1.0) * dev
        return Pair(max(1, (page.w * s).roundToInt()), max(1, (page.h * s).roundToInt()))
    }

    /** The part of page i inside the viewport, in page points. */
    internal fun visible(i: Int): Box {
        val page = doc!!.pages[i]
        val r = pageRect(i)
        val k = r[2] / page.w
        if (board) return doubleArrayOf(-r[0] / k, -r[1] / k, (W - r[0]) / k, (H - r[1]) / k)
        return doubleArrayOf(max(0.0, -r[0] / k), max(0.0, -r[1] / k), min(page.w, (W - r[0]) / k), min(page.h, (H - r[1]) / k))
    }

    /** Pixel window of the page raster to cache: all of it when it is small enough. */
    internal fun window(page: Page, i: Int, full: Pair<Int, Int>): IntArray {
        if (full.first.toLong() * full.second <= MAX_PIXELS && !board) return intArrayOf(0, 0, full.first, full.second)
        val ssx = full.first / page.w
        val ssy = full.second / page.h
        val (x0, y0, x1, y1) = visible(i)
        val mx = (x1 - x0) * 0.4
        val my = (y1 - y0) * 0.4
        if (board) {   // no edges to stop at; the window may lie anywhere
            val wx0 = floor((x0 - mx) * ssx / STRIP) * STRIP
            val wy0 = floor((y0 - my) * ssy / STRIP) * STRIP
            val wx1 = (floor((x1 + mx) * ssx / STRIP) + 1) * STRIP
            val wy1 = (floor((y1 + my) * ssy / STRIP) + 1) * STRIP
            return intArrayOf(wx0.toInt(), wy0.toInt(), (wx1 - wx0).toInt(), (wy1 - wy0).toInt())
        }
        val wx0 = max(0.0, floor(((x0 - mx) * ssx).toLong() / STRIP.toDouble()) * STRIP)
        val wy0 = max(0.0, floor(((y0 - my) * ssy).toLong() / STRIP.toDouble()) * STRIP)
        val wx1 = min(full.first.toDouble(), (floor(((x1 + mx) * ssx).toLong() / STRIP.toDouble()) + 1) * STRIP)
        val wy1 = min(full.second.toDouble(), (floor(((y1 + my) * ssy).toLong() / STRIP.toDouble()) + 1) * STRIP)
        return intArrayOf(wx0.toInt(), wy0.toInt(), max(1, (wx1 - wx0).toInt()), max(1, (wy1 - wy0).toInt()))
    }

    internal fun covers(win: IntArray, page: Page, i: Int, full: Pair<Int, Int>): Boolean {
        if (win[2] == full.first && win[3] == full.second) return true
        val ssx = full.first / page.w
        val ssy = full.second / page.h
        val (x0, y0, x1, y1) = visible(i)
        return win[0] <= x0 * ssx + 1 && win[1] <= y0 * ssy + 1 && win[0] + win[2] >= x1 * ssx - 1 && win[1] + win[3] >= y1 * ssy - 1
    }

    private fun ensure(page: Page, i: Int): Entry? {
        val full = fullSize(page)
        val entry = cache[page]
        if (entry != null && entry.full == full && covers(intArrayOf(entry.ox, entry.oy, entry.pw, entry.ph), page, i, full)) return entry
        if (page.pdf == null) return build(page, full, window(page, i, full), null)
        requestBg(page, i, full, 0)
        return cache[page] ?: entry   // stale or missing until the renderer delivers
    }

    private fun build(page: Page, full: Pair<Int, Int>, win: IntArray, bg: Bitmap?): Entry? {
        val old = cache[page]
        val bmp = try {
            Bitmap.createBitmap(max(1, win[2]), max(1, win[3]), Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return old
        }
        val entry = Entry(bmp, full, win[0], win[1], win[2], win[3], full.first / page.w, full.second / page.h, bg)
        cache[page] = entry
        repaint(entry, page, null)
        if (old != null && old !== entry) old.bitmap.recycle()
        return entry
    }

    /** Redraw the cached raster inside rect (page points), or all of it. */
    internal fun repaint(entry: Entry, page: Page, rect: Box?) {
        val ssx = entry.sx
        val ssy = entry.sy
        var x0 = 0
        var y0 = 0
        var x1 = entry.pw
        var y1 = entry.ph
        if (rect != null) {
            // Whole device pixels, so no antialiased seam is left behind.
            x0 = max(x0, floor(rect[0] * ssx).toInt() - 2 - entry.ox)
            y0 = max(y0, floor(rect[1] * ssy).toInt() - 2 - entry.oy)
            x1 = min(x1, ceil(rect[2] * ssx).toInt() + 2 - entry.ox)
            y1 = min(y1, ceil(rect[3] * ssy).toInt() + 2 - entry.oy)
            if (x1 <= x0 || y1 <= y0) return
        }
        val cv = entry.canvas
        cv.save()
        cv.clipRect(x0, y0, x1, y1)
        cv.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        entry.bg?.let { cv.drawBitmap(it, 0f, 0f, null) }
        // The shift to the window is done on page coordinates (the origin), not in the matrix.
        val env = Env(entry.ox / ssx, entry.oy / ssy, 1 / ssx, inkOn(page))
        cv.scale(ssx.toFloat(), ssy.toFloat())
        val box = doubleArrayOf((x0 + entry.ox) / ssx, (y0 + entry.oy) / ssy, (x1 + entry.ox) / ssx, (y1 + entry.oy) / ssy)
        if (entry.bg == null) {
            if (page.pdf != null) cv.drawRect((box[0] - env.ox).toFloat(), (box[1] - env.oy).toFloat(), (box[2] - env.ox).toFloat(), (box[3] - env.oy).toFloat(), Paint().apply { color = android.graphics.Color.WHITE })
            else drawPaper(cv, env, page, box)
        }
        val hid = hidden
        for (it in page.items) {
            if (hid != null && it in hid) continue
            if (Geom.intersects(it.bbox, box)) drawItem(cv, env, it)
        }
        cv.restore()
    }

    internal fun repaintPage(page: Page, rect: Box?) {
        cache[page]?.let { repaint(it, page, rect) }
    }

    private fun docEvent(e: Event) {
        val d = doc ?: return
        when (e) {
            is Event.Added -> cache[e.page]?.let { entry ->
                val cv = entry.canvas
                cv.save()
                cv.scale(entry.sx.toFloat(), entry.sy.toFloat())
                val env = Env(entry.ox / entry.sx, entry.oy / entry.sy, 1 / entry.sx, inkOn(e.page))
                for (it in e.items) drawItem(cv, env, it)
                cv.restore()
            }
            is Event.Dirty -> repaintPage(e.page, e.rect)
            is Event.Pages -> {
                val alive = identity(d.pages)
                for (page in cache.keys.toList()) if (page !in alive) cache.remove(page)?.bitmap?.recycle()
                for (page in pending.keys.toList()) if (page !in alive) pending.remove(page)
                op = null
                sel = null
                hidden = null
                relayout()
                configureAdj()
                host.viewChanged()
                host.selectionChanged()
            }
            else -> {}
        }
        invalidate()
    }

    private fun <T> identity(of: Collection<T>): MutableSet<T> = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(of) }

    // -- PDF backgrounds: rendered off the main thread, the visible page first ----------------

    private class BgMark(val full: Pair<Int, Int>, val win: IntArray, val key: String)

    private val pending = IdentityHashMap<Page, BgMark>()
    private val bgs = LinkedHashMap<String, Bitmap>()   // the last BG_KEEP rendered backgrounds, by page and window
    private val bgOwners = IdentityHashMap<Page, String>()
    private val jobs = java.util.PriorityQueue<Job>(compareBy<Job> { it.priority }.thenByDescending { it.seq })
    private var jobSeq = 0L
    private var rendering = false

    private class Job(val priority: Int, val seq: Long, val valid: () -> Boolean, val run: () -> Bitmap?, val done: (Bitmap) -> Unit)

    private fun bgKey(page: Page, full: Pair<Int, Int>, win: IntArray) = "${System.identityHashCode(page)}:${full.first}x${full.second}@${win.joinToString(",")}"

    private fun requestBg(page: Page, i: Int, full: Pair<Int, Int>, priority: Int) {
        val p = pending[page]
        if (p != null && p.full == full && (priority > 0 || covers(p.win, page, i, full))) return
        val win = window(page, i, full)
        val key = bgKey(page, full, win)
        bgs.remove(key)?.let { ready ->
            // Rendered before and still remembered: no waiting, no white page.
            bgs[key] = ready
            pending.remove(page)
            build(page, full, win, ready)
            return
        }
        val mark = BgMark(full, win, key)
        pending[page] = mark
        jobs.add(Job(priority, jobSeq++, valid = {
            if (pending[page] !== mark) false
            else if (!near(page)) { pending.remove(page); false }   // scrolled far away while this waited: it can be asked for again
            else true
        }, run = {
            val bmp = Bitmap.createBitmap(max(1, win[2]), max(1, win[3]), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            PdfPages.render(page, bmp, win[0] / (full.first / page.w), win[1] / (full.second / page.h), full.first / page.w, full.second / page.h)
            bmp
        }, done = { bg -> bgReady(page, mark, bg) }))
        pump()
    }

    private fun pump() {
        if (rendering) return
        while (true) {
            val job = jobs.poll() ?: return
            if (!job.valid()) continue
            rendering = true
            RENDERER.execute {
                val bmp = runCatching { job.run() }.getOrNull()
                handler.post {
                    rendering = false
                    if (bmp != null) job.done(bmp)
                    pump()
                }
            }
            return
        }
    }

    private fun near(page: Page): Boolean {
        val i = index(page) ?: return false
        return shown.first - 3 <= i && i <= shown.second + 3
    }

    private fun bgReady(page: Page, mark: BgMark, bg: Bitmap) {
        bgs[mark.key] = bg
        while (bgs.size > BG_KEEP) {
            val first = bgs.keys.first()
            bgs.remove(first)
        }
        if (pending[page] !== mark) return
        pending.remove(page)
        if (index(page) != null) {
            build(page, mark.full, mark.win, bg)
            invalidate()
        }
    }

    /** Render the pages just outside the view ahead of time, so scrolling meets no blanks. */
    private fun prefetch(first: Int, last: Int) {
        val pages = doc!!.pages
        listOf(last + 1, first - 1, last + 2).forEachIndexed { step, i ->
            if (i in pages.indices && pages[i].pdf != null) {
                val page = pages[i]
                val full = fullSize(page)
                val entry = cache[page]
                if (full.first.toLong() * full.second <= MAX_PIXELS && (entry == null || entry.full != full)) requestBg(page, i, full, 1 + step)
            }
        }
    }

    // -- drawing ------------------------------------------------------------------------------

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    internal var layer: Bitmap? = null   // the frozen part of the stroke being written (view size)

    override fun onDraw(canvas: Canvas) {
        val d = doc
        canvas.drawColor(canvasBg)
        if (d == null || W <= 0 || tops.isEmpty()) return
        canvas.save()
        canvas.scale(dev.toFloat(), dev.toFloat())
        val first = pageAt(h(), v())
        var i = first
        while (i < d.pages.size) {
            val (x, y, w, hh) = pageRect(i)
            if ((if (across()) x > W else y > H) && !board) break
            val page = d.pages[i]
            if (board) {
                canvas.drawColor(argb(page.color))   // it fills the view
            } else {
                shadowPaint.color = if (page.pdf != null) android.graphics.Color.WHITE else argb(page.color)
                shadowPaint.setShadowLayer(10f, 0f, 2f, 0x29000000)
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + hh).toFloat(), shadowPaint)
            }
            val entry = ensure(page, i)
            if (entry != null && !entry.bitmap.isRecycled) {
                // The window of the page that is cached, at the page's place on the screen.
                val k = w / page.w / entry.sx
                val dst = RectF((x + entry.ox * k).toFloat(), (y + entry.oy * k).toFloat(), (x + (entry.ox + entry.pw) * k).toFloat(), (y + (entry.oy + entry.ph) * k).toFloat())
                canvas.drawBitmap(entry.bitmap, null, dst, bitmapPaint)
            }
            i++
            if (board) break
        }
        shown = Pair(first, i - 1)
        if (!board) prefetch(first, i - 1)
        trimCache(first, i)
        canvas.restore()

        drawOverlay(canvas)
        if (marks.isNotEmpty()) {
            canvas.save()
            canvas.scale(dev.toFloat(), dev.toFloat())
            val paint = Paint()
            marks.forEachIndexed { n, (page, box) ->
                val idx = index(page) ?: return@forEachIndexed
                val r = pageRect(idx)
                val k = r[2] / page.w
                val rx = r[0] + box[0] * k - 2
                val ry = r[1] + box[1] * k - 2
                paint.color = if (n == markAt) MARK_NOW else MARK
                canvas.drawRect(rx.toFloat(), ry.toFloat(), (rx + (box[2] - box[0]) * k + 4).toFloat(), (ry + (box[3] - box[1]) * k + 4).toFloat(), paint)
            }
            canvas.restore()
        }
        drawBars(canvas)
    }

    /** Pages away from the view give their memory back; so does anything over the budget. */
    private fun trimCache(first: Int, i: Int) {
        val pages = doc!!.pages
        if (cache.size > (i - first) + 5) {
            val keep = identity(pages.subList(max(0, first - 2), min(pages.size, i + 2)))
            for (page in cache.keys.toList()) if (page !in keep) cache.remove(page)?.bitmap?.recycle()
        }
        var total = cache.values.sumOf { it.pw.toLong() * it.ph }
        if (total > CACHE_LIMIT) {
            val here = identity(pages.subList(first, min(pages.size, i)))
            for (page in cache.keys.toList()) {
                if (total <= CACHE_LIMIT) break
                if (page !in here) {
                    val e = cache.remove(page)!!
                    total -= e.pw.toLong() * e.ph
                    e.bitmap.recycle()
                }
            }
        }
    }

    // -- scrollbars: they show the place, the mouse can drag their handles, fingers and the pen pass through

    /** The bars on show as [axis(0 v, 1 h), lowest value, highest value, edge, track, handle start, handle length]. */
    private fun bars(): List<DoubleArray> {
        if (!scrollbars || board || doc == null || tops.isEmpty()) return emptyList()
        val out = ArrayList<DoubleArray>()
        for ((axis, upper, view, acrossV) in listOf(listOf(0.0, vUpper(), H, W), listOf(1.0, hUpper(), W, H))) {
            var lo = 0.0
            var extent = upper
            if (axis == 1.0 && across() && slots.isNotEmpty()) {
                val i = min(currentPageIndex(), slots.size - 1)
                val page = doc!!.pages[i]
                lo = slots[i]
                extent = max(view, page.w * z(page) + 2 * SIDE)
            }
            if (extent <= view + 1) continue
            val hi = lo + extent - view
            val track = view - 16
            val handle = max(40.0, track * view / extent)
            val value = if (axis == 0.0) sy else sx
            val at = 8 + (track - handle) * clamp((value - lo) / (hi - lo), 0.0, 1.0)
            out.add(doubleArrayOf(axis, lo, hi, acrossV - 9, track, at, handle))
        }
        return out
    }

    private fun drawBars(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xBF8C8C94.toInt() }
        canvas.save()
        canvas.scale(dev.toFloat(), dev.toFloat())
        for (b in bars()) {
            val thick = if (barDrag != null && barAxis == (if (b[0] == 0.0) "v" else "h")) 8.0 else 5.0
            val edge = b[3]
            val r = if (b[0] == 0.0) RectF((edge + 5 - thick).toFloat(), b[5].toFloat(), (edge + 5).toFloat(), (b[5] + b[6]).toFloat())
            else RectF(b[5].toFloat(), (edge + 5 - thick).toFloat(), (b[5] + b[6]).toFloat(), (edge + 5).toFloat())
            canvas.drawRoundRect(r, (thick / 2).toFloat(), (thick / 2).toFloat(), paint)
        }
        canvas.restore()
    }

    /** The bar whose handle is under a point, ready to be dragged: [lo, hi, value per dp, value]. */
    private fun barAt(x: Double, y: Double): Pair<String, DoubleArray>? {
        for (b in bars()) {
            val (along, acrossV) = if (b[0] == 0.0) Pair(y, x) else Pair(x, y)
            if (acrossV >= b[3] - 8 && b[5] <= along && along <= b[5] + b[6]) {
                return Pair(if (b[0] == 0.0) "v" else "h", doubleArrayOf(b[1], b[2], (b[2] - b[1]) / max(1.0, b[4] - b[6]), if (b[0] == 0.0) sy else sx))
            }
        }
        return null
    }

    // -- input: pen, fingers and mouse ------------------------------------------------------------

    internal var penDown = false
    private var penId = -1
    private var penDownAt = 0L
    internal var penAt = Pair(0.0, 0.0)
    private var penSeen = -1_000_000L
    private var pst = PenState()
    internal var mouseP = 0.5
    private var mouseDown = false
    private var mouseLast = Pair(0.0, 0.0)
    private var hand: DoubleArray? = null       // the mouse moving the page: h0, v0, x0, y0, moved
    private var pan: DoubleArray? = null        // the middle button moving the page
    private var barStart = Pair(0.0, 0.0)
    internal val touches = LinkedHashMap<Int, Pair<Double, Double>>()
    private val rejected = HashSet<Int>()
    internal var tg: TouchGesture? = null
    private var fling: Runnable? = null
    private var penForce = true                 // the pen in use tells how hard it is pressed

    private class PenState(var n: Int = 0, var first: Double = 0.0, var varying: Boolean = false, var t: Long = 0, var at: Pair<Double, Double> = Pair(0.0, 0.0))

    class TouchGesture(
        var n: Int, var cx: Double, var cy: Double, var span: Double, var zoom: Double, var draw: Boolean, var moved: Boolean,
        var t0: Long, val track: ArrayList<DoubleArray>, val origin: DoubleArray, val anchor: Triple<Int, Double, Double>,
        var over: Double, val seen: ArrayList<Pair<Long, DoubleArray>>, val page: Int,
    ) {
        var ruler: Ruler? = null
        var turn: Double? = null
        var grab: String? = null   // "move", "copy" or "held"
    }

    private fun kindOf(e: MotionEvent, i: Int): String = when (e.getToolType(i)) {
        MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> "pen"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "touch"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e, i)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e, i)
                if (tg != null) touchUpdate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                // A touch the system took for the palm comes up marked as cancelled.
                val cancelled = Build.VERSION.SDK_INT >= 33 && (e.flags and MotionEvent.FLAG_CANCELED) != 0
                if (cancelled) pointerCancel(e, i) else pointerUp(e, i)
            }
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) pointerCancel(e, i)
        }
        return true
    }

    private fun px(e: MotionEvent, i: Int) = Pair(e.getX(i) / dev, e.getY(i) / dev)

    private fun pointerDown(e: MotionEvent, i: Int) {
        val (x, y) = px(e, i)
        when (kindOf(e, i)) {
            "pen" -> penDownEvent(e, i, x, y)
            "touch" -> touchDown(e.getPointerId(i), x, y)
            else -> mouseDownEvent(e, i, x, y)
        }
    }

    private fun pointerMove(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val (x, y) = px(e, i)
        when (kindOf(e, i)) {
            "pen" -> {
                hasPen = true
                penSeen = mono()
                if (penDown && id == penId) penMove(e, i)
            }
            "touch" -> if (id in touches) touches[id] = Pair(x, y)
            else -> mouseMove(e, i, x, y)
        }
    }

    private fun pointerUp(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val (x, y) = px(e, i)
        when (kindOf(e, i)) {
            "pen" -> if (penDown && id == penId) {
                // The lift carries the last samples (and its own place): the line goes all the way.
                // Its pressure is often zero, so the tail keeps the pressure it had.
                for (k in 0 until e.historySize) {
                    penAt = Pair(e.getHistoricalX(i, k) / dev, e.getHistoricalY(i, k) / dev)
                    move(penAt.first, penAt.second, penPressure(e.getHistoricalPressure(i, k).toDouble(), e.getHistoricalEventTime(k)))
                }
                penAt = Pair(x, y)
                move(x, y, lastPressure())
                penDown = false
                log("pen up, was drawing: ${op?.kind}")
                up(x, y)
            }
            "touch" -> {
                rejected.remove(id)
                if (touches.remove(id) != null) touchReset(x, y, true)
            }
            else -> mouseUp(x, y)
        }
    }

    private fun pointerCancel(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val (x, y) = px(e, i)
        when (kindOf(e, i)) {
            "pen" -> {
                log("pen cancelled, was drawing: ${op?.kind}")
                if (penDown && id == penId) {
                    // The system took the pen away mid-stroke. What was written stays: ink that
                    // vanishes is worse than a stroke cut short. Anything else under way is given up.
                    penDown = false
                    if (op?.kind == "stroke") up(penAt.first, penAt.second) else drop("the pen was cancelled")
                }
            }
            "touch" -> {
                rejected.remove(id)
                if (touches.remove(id) != null) touchReset(x, y, false)
            }
            else -> drop("the mouse was cancelled")
        }
    }

    /** Something other than the pen wants the stroke in progress abandoned. While the pen is
     *  writing nothing else may do that; otherwise it is cancelled as asked. */
    internal fun drop(why: String) {
        if (penDown) {
            log("kept the pen stroke although: $why")
            return
        }
        op?.let { log("cancelled ${it.kind}: $why") }
        cancel()
    }

    /** How hard the pen writes, 0..1 (A:CanvasTools.swift pressure, W input.js _penPressure). */
    /** The pressure the line being drawn has now. */
    private fun lastPressure(): Double = (op?.item as? io.github.inkpad.core.Stroke)?.pts?.lastOrNull()?.getOrNull(2) ?: 0.5

    private fun penPressure(pressure: Double, t: Long): Double {
        val st = pst
        // The line thins with speed. The time is the sample's own: the samples of one frame
        // arrive together, and the clock would call them simultaneous.
        val dt = (t - st.t) / 1000.0
        if (dt > 0) {
            val speed = hypot(penAt.first - st.at.first, penAt.second - st.at.second) / dt   // dp a second
            val aim = clamp(0.95 - speed / 1700, 0.16, 0.92)
            mouseP += (aim - mouseP) * (1 - exp(-dt / 0.045))
            st.at = penAt
            st.t = t
        }
        // A pen without a pressure sensor reports the same force all the time. It is known by
        // that, and its line is then shaped by speed alone, as a mouse's is.
        st.n++
        if (st.n == 1) st.first = pressure
        else if (!st.varying && abs(pressure - st.first) > 0.02) {
            st.varying = true
            penForce = true
        } else if (!st.varying && st.n == 12) penForce = false
        if (!penForce || pressure <= 0) return clamp(0.5 + (mouseP - 0.5) * 2, 0.0, 1.0)
        // A tablet's pen is taken at its word (B:canvas.py).
        return clamp(pressure, 0.02, 1.0)
    }

    private fun penDownEvent(e: MotionEvent, i: Int, x: Double, y: Double) {
        hasPen = true
        penSeen = mono()
        palm()
        log("pen press: id ${e.getPointerId(i)}, buttons ${e.buttonState}, pressure ${"%.2f".format(e.getPressure(i))}, touches ${rejected.size}")
        if (penDown) {
            // The lift before this never arrived: finish that stroke where it stopped.
            log("pen press while still down: ending the earlier stroke")
            penDown = false
            up(penAt.first, penAt.second)
        }
        // The eraser end of the pen, or the barrel button held while writing, erases.
        val erase = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER ||
            (penButtonErases && (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0)
        penDown = true
        penDownAt = mono()
        penId = e.getPointerId(i)
        src = "pen"
        penAt = Pair(x, y)
        pst = PenState(t = e.eventTime, at = Pair(x, y))
        mouseP = 0.5
        hover = null
        down(x, y, penPressure(e.getPressure(i).toDouble(), e.eventTime), erase)
        log("pen down at ${x.roundToInt()},${y.roundToInt()} -> ${op?.kind ?: "nothing (text, a selection grabbed, or outside every page)"}")
    }

    private fun penMove(e: MotionEvent, i: Int) {
        // The samples of a frame arrive together; the history keeps fast handwriting smooth.
        for (k in 0 until e.historySize) {
            val x = e.getHistoricalX(i, k) / dev
            val y = e.getHistoricalY(i, k) / dev
            penAt = Pair(x, y)
            move(x, y, penPressure(e.getHistoricalPressure(i, k).toDouble(), e.getHistoricalEventTime(k)))
        }
        val (x, y) = px(e, i)
        penAt = Pair(x, y)
        move(x, y, penPressure(e.getPressure(i).toDouble(), e.eventTime))
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val kind = kindOf(e, 0)
        val was = hover
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                if (kind == "pen") {
                    hasPen = true
                    penSeen = mono()
                }
                hover = if (kind == "pen" || (hasPen && kind == "mouse")) Pair(e.x / dev, e.y / dev) else null
            }
            MotionEvent.ACTION_HOVER_EXIT -> hover = null
        }
        if (hover != null || was != null) invalidate()
        return true
    }

    // -- the mouse ------------------------------------------------------------------------------

    private fun mouseDownEvent(e: MotionEvent, i: Int, x: Double, y: Double) {
        requestFocus()
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) {   // middle button: moves the page
            pan = doubleArrayOf(h(), v(), x, y)
            return
        }
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) {
            if (sel != null) host.menuWanted(x, y)
            return
        }
        val b = barAt(x, y)
        barStart = Pair(x, y)
        if (b != null) {
            barAxis = b.first
            barDrag = b.second
            invalidate()
            return
        }
        if (mouseHand && hasPen && rulerZone(x, y) == null) {
            // With a pen in use the mouse is a hand: it moves the page, and a click lifts tape.
            hand = doubleArrayOf(h(), v(), x, y, 0.0)
            return
        }
        mouseDown = true
        mouseP = 0.45
        mouseLast = Pair(x, y)
        src = "mouse"
        down(x, y, mouseP, false)
    }

    private fun mouseMove(e: MotionEvent, i: Int, x: Double, y: Double) {
        pan?.let { p ->
            configureAdj(p[0] - (x - p[2]), p[1] - (y - p[3]))
            return
        }
        barDrag?.let { b ->
            val d = if (barAxis == "v") y - barStart.second else x - barStart.first
            setAdj(if (barAxis == "v") 'v' else 'h', clamp(b[3] + d * b[2], b[0], b[1]))
            return
        }
        hand?.let { hd ->
            if (hd[4] != 0.0 || hypot(x - hd[2], y - hd[3]) > 4) {
                hd[4] = 1.0
                scrollBy2(hd[0] - (x - hd[2]) - h(), hd[1] - (y - hd[3]) - v())
            }
            return
        }
        if (!mouseDown) return
        // No pressure axis: thin the line with speed for a calligraphic feel.
        fun sample(ex: Double, ey: Double) {
            val speed = hypot(ex - mouseLast.first, ey - mouseLast.second)
            mouseP = mouseP * 0.75 + clamp(0.95 - speed / 45, 0.15, 0.85) * 0.25
            mouseLast = Pair(ex, ey)
            move(ex, ey, mouseP)
        }
        for (k in 0 until e.historySize) sample(e.getHistoricalX(i, k) / dev, e.getHistoricalY(i, k) / dev)
        sample(x, y)
    }

    private fun mouseUp(x: Double, y: Double) {
        if (pan != null) {
            pan = null
            rest(doubleArrayOf(zoom, h(), v()))
            return
        }
        if (barDrag != null) {
            barDrag = null
            invalidate()
            return
        }
        hand?.let { hd ->
            hand = null
            if (hd[4] == 0.0) toggleTape(hd[2], hd[3]) else rest(doubleArrayOf(zoom, hd[0], hd[1]))
            return
        }
        if (mouseDown) {
            mouseDown = false
            up(x, y)
        }
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && e.actionMasked == MotionEvent.ACTION_SCROLL) {
            wheel(e.x / dev, e.y / dev, e.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(), -e.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(),
                (e.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON)) != 0, (e.metaState and KeyEvent.META_SHIFT_ON) != 0)
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && sel != null) {
            deleteSelection()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            cancel()
            clearSelection()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // -- touch: pan, pinch and palm rejection ----------------------------------------------------

    /** The pen is at the screen: whatever touches it now is the hand, so undo what it did. */
    private fun palm() {
        stopFling()
        if (touches.isEmpty()) return
        rejected.addAll(touches.keys)
        touches.clear()
        val g = tg
        tg = null
        if (g == null) return
        if (g.grab != null) {
            if (g.grab != "held") cancel()   // the hand had hold of the selection: it goes back
        } else if (g.draw) {
            drop("the hand that was drawing is a palm")
        } else if (g.moved) {
            // What the hand did as the pen came down is undone: the view goes back to where it was
            // a little before. A hand that has long been lying still changes nothing, and neither
            // does a pan that was finished well before the pen arrived.
            val now = mono()
            if (now - g.track.last()[0].toLong() > 350) return   // it last moved a while ago: that was meant
            var state = g.seen[0].second
            for (k in g.seen.indices.reversed()) if (now - g.seen[k].first > 350) {
                state = g.seen[k].second
                break
            }
            if (state[0] == zoom && state[1] == h() && state[2] == v()) return
            zoom = state[0]
            relayout()
            configureAdj(state[1], state[2])
            zoomChanged()
        }
    }

    private fun penActive() = penDown || mono() - penSeen < palmMs

    private fun touchDown(id: Int, x: Double, y: Double) {
        requestFocus()
        if (penActive()) {
            rejected.add(id)
            log("touch ignored: the pen is at work")
            return
        }
        log("touch begins, ${touches.size + 1} on the screen")
        touches[id] = Pair(x, y)
        touchReset(x, y)
    }

    private fun touchShape(): Triple<Double, Double, Double> {
        val pts = touches.values.toList()
        val n = pts.size
        val cx = pts.sumOf { it.first } / n
        val cy = pts.sumOf { it.second } / n
        return Triple(cx, cy, pts.sumOf { hypot(it.first - cx, it.second - cy) } / n)
    }

    /** A finger came or went: restart the gesture from where the view is now. */
    private fun touchReset(x: Double, y: Double, lifted: Boolean = false) {
        var old = tg
        val n = touches.size
        stopFling()
        stopPress()
        if (old != null && old.grab != null) {
            if (grabTouch(old, n, x, y, lifted)) return
            old = null
        }
        if (old != null && old.draw) {
            // A second finger turns a finger stroke into a pinch (unless the stroke has only just
            // begun or is very short: then it was the first finger of the pinch); lifting ends it.
            if (penDown) { /* the pen has taken over; the finger's stroke is long gone */ }
            else if (n == 0 && lifted) up(x, y)
            else if (strokeInterrupted(old)) drop("a finger stroke turned into a gesture")
            else up(old.cx, old.cy)
        }
        val d = doc ?: return
        if (n == 0) {
            tg = null
            val pages = d.pages.size
            if (pull != 0.0) pullRelease()
            if (old != null && across() && old.over != 0.0 && old.page < slots.size) {
                // Let go past the edge of a zoomed page: far enough turns it, less springs back.
                val i = old.page
                val over = old.over
                val j = i + if (over > 0) 1 else -1
                if (abs(over) > max(110.0, 0.22 * W) && j >= 0 && j < slots.size) {
                    turning = j
                    glide('h', if (j > i) span(j).first else span(j).second)
                } else {
                    turning = i
                    glide('h', if (over > 0) span(i).second else span(i).first)
                }
                return
            }
            if (old != null && old.moved && !old.draw && d.pages.size == pages && rest(old.origin, old.track)) {
                // side by side, the page in view comes to rest in the middle
            } else if (old != null && old.moved && !old.draw && old.n == 1 && lifted) {
                startFling(old.track)
            } else if (old != null && lifted && !old.moved && !old.draw && old.ruler == null && old.n == 1) {
                if (tool == "lasso") pickAt(x, y)   // with the select tool a tap picks what is under it
                else toggleTape(x, y)               // a tap
            }
            return
        }
        val (cx, cy, sp) = touchShape()
        val now = mono()
        val draw = n == 1 && old == null && (fingerDraw || tool == "laser")
        val g = TouchGesture(
            n, cx, cy, sp, zoom, draw, old != null && old.moved && !old.draw,
            old?.t0 ?: now, arrayListOf(doubleArrayOf(now.toDouble(), cx, cy)),
            old?.origin ?: doubleArrayOf(zoom, h(), v()), anchor(cx, cy), 0.0,
            old?.seen ?: arrayListOf(Pair(now, doubleArrayOf(zoom, h(), v()))),
            old?.page ?: (if (tops.isNotEmpty() && !board) currentPageIndex() else 0),
        )
        tg = g
        if (rulerZone(cx, cy) != null) {
            // Fingers on the ruler handle the ruler: one moves it, two also turn it.
            val pts = touches.values.toList()
            g.draw = false
            g.ruler = ruler?.let { Ruler(it.x, it.y, it.a) }
            g.turn = if (n > 1) atan2(pts[1].second - pts[0].second, pts[1].first - pts[0].first) else null
        } else if (tool == "lasso" && sel != null && old == null && n == 1 && !penDown && grabSelection(cx, cy, 22.0)) {
            // A finger on the selection carries it; at its edge, resizes it.
            src = "touch"
            g.draw = true
            g.grab = "move"
            press(cx, cy)
        } else if (draw) {
            src = "touch"
            down(cx, cy, 0.5, false)
        } else if (n == 1 && old == null && tool == "lasso") {
            press(cx, cy)
        }
    }

    /** A second finger turns a finger stroke into a pinch, but not if the stroke is only just
     *  begun or short (A:strokeInterrupted): that finger was the first of a pinch. */
    private fun strokeInterrupted(g: TouchGesture): Boolean {
        val o = op
        val pts = if (o != null && o.kind == "stroke") (o.item as io.github.inkpad.core.Stroke).pts.size else 0
        return !(mono() - g.t0 > 300 && pts >= 8)
    }

    /** Fingers that have hold of the selection: one moves or resizes it, a second one beside it
     *  takes a copy along instead. True when that is all the fingers' coming or going meant. */
    private fun grabTouch(old: TouchGesture, n: Int, x: Double, y: Double, lifted: Boolean): Boolean {
        val grab = old.grab
        if (n == 0) {
            tg = null
            if (grab == "move" || grab == "copy") {
                if (lifted) up(x, y) else cancel()
            }
            return true
        }
        if (grab == "move" && n == 2) {
            cancel()
            val (cx, cy) = touchShape()
            if (copySelectionFrom(cx, cy)) {
                old.grab = "copy"
                old.n = 2
                return true
            }
            return false   // the selection is gone: the fingers move the page
        }
        if (grab == "move" || grab == "copy") {
            // A finger more or less than the gesture needs ends it where it is.
            if (grab == "copy") up(old.cx, old.cy) else cancel()
        }
        old.grab = "held"   // nothing more until every finger is off the screen
        return true
    }

    private fun touchUpdate() {
        val g = tg ?: return
        if (touches.isEmpty()) return
        val (cx, cy, sp) = touchShape()
        val was = g.ruler
        val r = ruler
        if (was != null && r != null) {
            var turned = 0.0
            val t = g.turn
            if (t != null && touches.size > 1) {
                val pts = touches.values.toList()
                turned = atan2(pts[1].second - pts[0].second, pts[1].first - pts[0].first) - t
            }
            // It turns about the point between the fingers and follows them.
            val c = cos(turned)
            val s = sin(turned)
            val vx = was.x - g.cx
            val vy = was.y - g.cy
            r.x = cx + vx * c - vy * s
            r.y = cy + vx * s + vy * c
            turnRuler(was.a + turned)
            g.moved = false   // lifting the fingers must not fling the page
            return
        }
        if (g.grab != null) {
            if (g.grab == "move" || g.grab == "copy") {
                g.cx = cx
                g.cy = cy
                move(cx, cy, 0.5)
            }
            return
        }
        if (g.draw) {
            g.cx = cx
            g.cy = cy
            move(cx, cy, 0.5)
            return
        }
        if (!g.moved) {
            // A small dead zone, so that a brush of the hand does not move the page.
            if (hypot(cx - g.cx, cy - g.cy) < 8 && abs(sp - g.span) < 8) return
            g.moved = true
            stopPress()
        }
        var zz = g.zoom
        if (g.n > 1 && g.span > 12) zz = clamp(zz * sp / g.span, minZoom(), maxZoomNow())
        // The page point that was between the fingers stays between them.
        place(zz, g.anchor, cx, cy)
        g.over = 0.0
        if (across() && g.n == 1 && g.page < slots.size && span(g.page).second > span(g.page).first + 1) {
            // Zoomed in, a finger moves about inside its page. Past the edge the page gives only a
            // little: it takes a long, deliberate pull to turn to the next one.
            val (lo, hi) = span(g.page)
            val w0 = want[0]
            g.over = if (w0 > hi) w0 - hi else if (w0 < lo) w0 - lo else 0.0
            setAdj('h', clamp(w0, lo, hi) + 0.4 * g.over)
        } else if (across() && g.n == 1 && abs(g.origin[1] - h()) < 1 && abs(g.origin[0] - zoom) < 1e-6) {
            // Only a drag that began with the first or last page at rest pulls a new one out.
            val over = want[0] - h()
            pulled(if (abs(over) > 1) over else 0.0)
        }
        val now = mono()
        g.track.add(doubleArrayOf(now.toDouble(), cx, cy))
        while (g.track.size > 6) g.track.removeAt(0)
        if (now - g.seen.last().first > 40) {   // the view at intervals, for the palm to be undone to
            g.seen.add(Pair(now, doubleArrayOf(zoom, h(), v())))
            while (g.seen.size > 2 && now - g.seen[1].first > 350) g.seen.removeAt(0)
        }
    }

    /** Note that the view is being pushed past an end of a side-by-side notebook. */
    private fun pulled(over: Double) {
        if (!across()) return
        if (over != pull) {
            pull = over
            invalidate()
        }
    }

    /** Side by side, bring one whole page to rest in the window. [origin] is the (zoom, x, y) a
     *  drag started from: a drag far or fast enough turns to the next page. False when there is
     *  nothing to settle, as inside a page that is zoomed in past the window. */
    internal fun rest(origin: DoubleArray? = null, track: List<DoubleArray>? = null): Boolean {
        if (!across() || slots.isEmpty()) return false
        val view = W
        val hh = h()
        var i = pageAt(hh + view / 2, 0.0)
        val (lo, hi) = span(i)
        if (hi > lo + 1) {
            // Zoomed in: the view belongs to one page and never rests across two.
            if (lo - 0.5 <= hh && hh <= hi + 0.5) return false
            turning = i
            glide('h', clamp(hh, lo, hi))
            return true
        }
        if (origin != null && abs(origin[0] - zoom) < 1e-6) {
            val start = pageAt(origin[1] + view / 2, 0.0)
            val moved = hh - origin[1]
            var speed = 0.0
            if (track != null && track.size > 1 && track.last()[0] > track[0][0] && mono() - track.last()[0] < 60) {   // still moving when let go
                speed = (track[0][1] - track.last()[1]) / (track.last()[0] - track[0][0]) * 1000
            }
            i = if (abs(moved) > 0.18 * view || (abs(speed) > 400 && speed * moved > 0)) (start + if (moved > 0) 1 else -1).coerceIn(0, slots.size - 1)
            else start
        }
        if (abs(slots[i] - hh) > 0.5) scrollToPage(i)
        return true
    }

    /** The pull was let go: far enough makes a page, otherwise it just springs back. */
    private fun pullRelease() {
        val p = pull
        pull = 0.0
        if (p != 0.0) {
            invalidate()
            if (abs(p) >= 90) host.pullAdd(if (p > 0) 1 else -1)
        }
    }

    internal fun scrollBy2(dx: Double, dy: Double) {
        if (board) {
            configureAdj(bo[0] + dx, bo[1] + dy)
            return
        }
        var x = sx + dx
        if (across() && slots.isNotEmpty()) {
            // Inside a zoomed page, gliding and dragging stop at its edge.
            val (lo, hi) = span(currentPageIndex())
            if (hi > lo + 1 && lo - 0.5 <= sx && sx <= hi + 0.5) x = clamp(x, lo, hi)
        }
        configureAdj(x, sy + dy)
    }

    private fun startFling(track: List<DoubleArray>) {
        val last = track.last()
        val t1 = last[0]
        val first = track.firstOrNull { t1 - it[0] < 90 } ?: track[0]
        if (mono() - t1 > 60 || t1 - first[0] < 8) return
        var vx = (first[1] - last[1]) / (t1 - first[0]) * 1000
        var vy = (first[2] - last[2]) / (t1 - first[0]) * 1000
        if (hypot(vx, vy) < 250) return
        var prev = 0L
        val tick = object : Runnable {
            override fun run() {
                val now = mono()
                val dt = if (prev != 0L) (now - prev) / 1000.0 else 0.0
                prev = now
                scrollBy2(vx * dt, vy * dt)
                val k = exp(-4.0 * dt)
                vx *= k
                vy *= k
                if (hypot(vx, vy) < 30) {
                    fling = null
                    return
                }
                postOnAnimation(this)
            }
        }
        fling = tick
        postOnAnimation(tick)
    }

    private fun stopFling() {
        fling?.let { removeCallbacks(it) }
        fling = null
    }

    // -- the wheel and the touchpad ------------------------------------------------------------

    private var turnAt = 0L
    private var turnSum = 0.0
    private var turnedAt = 0L
    private var turnDone = false

    private fun wheel(x: Double, y: Double, dx: Double, dy: Double, ctrl: Boolean, shift: Boolean) {
        if (rulerZone(x, y) != null) {
            // The wheel over the ruler turns it: a degree a notch, fifteen with Shift.
            val d = if (dy != 0.0) dy else dx
            turnRuler(ruler!!.a + d * (if (shift) 15 else 1) * Math.PI / 180)
            return
        }
        if (ctrl) {
            setZoom(zoom * Math.pow(1.1, -dy), x, y)
            return
        }
        if (across() && (dx != 0.0 || dy != 0.0)) {
            turnScroll(dx, dy)
            return
        }
        var sdx = dx
        var sdy = dy
        if (shift && sdx == 0.0) {
            sdx = sdy
            sdy = 0.0
        }
        val step = if (board) 48.0 else 60.0
        scrollBy2(sdx * step, sdy * step)
    }

    /** Scrolling when the pages are side by side: it moves about inside a page that is larger than
     *  the window; where there is nothing left to move, it turns the page, once per notch. */
    private fun turnScroll(dx: Double, dy: Double) {
        val upright = abs(dy) >= abs(dx)
        val d = if (upright) dy else dx
        val step = 60.0
        if (upright) {
            val upper = vUpper() - H
            val room = if (d > 0) upper - sy else sy
            if (room > 0.5 && upper > 12) {
                setAdj('v', sy + d * step)
                return
            }
        }
        val i = currentPageIndex()
        val page = doc!!.pages[i]
        val slot = page.w * z(page) + 2 * SIDE
        if (slot > W + 1) {
            val lo = slots[i]
            val hi = slots[i] + slot - W
            val room = if (d > 0) hi - sx else sx - lo
            if (room > 0.5) {
                setAdj('h', clamp(sx + d * step, lo, hi))
                return
            }
        }
        val now = mono()
        val quiet = now - turnAt > 180
        turnAt = now
        if (quiet || turnSum * d < 0) turnSum = 0.0
        turnSum += d
        // A whole notch turns a page, and not more often than the eye can follow.
        if (abs(turnSum) < 0.99 || now - turnedAt < 220) return
        turnSum = 0.0
        turnedAt = now
        val j = (if (anim != null) turning else i) + if (d > 0) 1 else -1
        if (j in slots.indices) scrollToPage(j)
        turnDone = true
    }

    companion object {
        /** One page of a PDF is rendered at a time, off the main thread, for every canvas. */
        private val RENDERER = Executors.newSingleThreadExecutor { Thread(it, "pdf").apply { isDaemon = true } }
    }
}
