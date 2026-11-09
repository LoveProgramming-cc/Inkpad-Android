package io.github.inkpad.editor

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.inkpad.core.Geom
import io.github.inkpad.core.Page
import io.github.inkpad.core.pageBox
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.pdf.PdfPages
import io.github.inkpad.render.pageBitmap
import io.github.inkpad.ui.Icon
import io.github.inkpad.ui.Label
import io.github.inkpad.ui.MenuItem
import io.github.inkpad.ui.MenuList
import io.github.inkpad.ui.PillButton
import io.github.inkpad.ui.PopButton
import io.github.inkpad.ui.palette
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** Page pictures for the sidebar and the panels, made off the main thread and kept a while. */
object Thumbs {
    private val cache = LruCache<String, Bitmap>(80)
    val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "thumbs").apply { isDaemon = true } }.asCoroutineDispatcher()

    fun key(page: Page, w: Int, h: Int) = "${System.identityHashCode(page)}:${page.rev}:${page.paper}:${page.rot}:${page.w}x${page.h}:$w×$h"
    fun cached(key: String): Bitmap? = cache.get(key)

    fun make(page: Page, w: Int, h: Int): Bitmap {
        val key = key(page, w, h)
        cache.get(key)?.let { return it }
        val (x0, y0, x1, y1) = pageBox(page)
        val bmp = if (page.board) pageBitmap(page, 0.0, Pair(w, h))
        else pageBitmap(page, min(w / (x1 - x0), h / (y1 - y0)), background = PdfPages.background(page))
        cache.put(key, bmp)
        return bmp
    }
}

/** A page's picture, made in the background; white until then. */
@Composable
fun PageThumb(page: Page, w: Int, h: Int, rev: Int = page.rev, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val pw = (w * density).toInt()
    val ph = (h * density).toInt()
    var bmp by remember(page, rev, pw, ph) { mutableStateOf(Thumbs.cached(Thumbs.key(page, pw, ph))) }
    LaunchedEffect(page, rev, pw, ph) {
        if (bmp == null) bmp = withContext(Thumbs.dispatcher) { runCatching { Thumbs.make(page, pw, ph) }.getOrNull() }
    }
    val (x0, y0, x1, y1) = pageBox(page)
    val k = if (page.board) 1.0 else min(w / (x1 - x0), h / (y1 - y0))
    val tw = if (page.board) w else max(1, ((x1 - x0) * k).toInt())
    val th = if (page.board) h else max(1, ((y1 - y0) * k).toInt())
    Box(modifier.size(tw.dp, th.dp).shadow(2.dp, RoundedCornerShape(3.dp)).background(Color.White, RoundedCornerShape(3.dp))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(tw.dp, th.dp).clip(RoundedCornerShape(3.dp))) }
    }
}

/** Thumbnails in two columns, the outline and the bookmarks (B:sidebar.py). */
@Composable
fun Sidebar(ed: EditorState, modifier: Modifier) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val tick = ed.session.tick.intValue
    val p = palette
    var tab by remember { mutableIntStateOf(0) }
    val doc = ed.doc
    // A copy per change: the grid sees a new list, and redraws, when pages come and go.
    val pages = remember(tick, doc) { doc.pages.toList() }
    Column(modifier.background(p.sidebarBg)) {
        Row(Modifier.padding(8.dp).fillMaxWidth().background(p.ink(0.06f), RoundedCornerShape(10.dp)).padding(3.dp)) {
            for ((i, t) in listOf(tr("缩略图"), tr("大纲"), tr("书签")).withIndex()) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (tab == i) p.cardBg else Color.Transparent).clickable { tab = i }.padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center) { Label(t, 13f) }
            }
        }
        when (tab) {
            0 -> LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(pages, key = { _, page -> System.identityHashCode(page) }) { i, page ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(if (i == ed.page) 2.dp else 0.dp, if (i == ed.page) p.accent else Color.Transparent, RoundedCornerShape(4.dp))
                            .clickable { ed.canvas.scrollToPage(i) }
                            .padding(3.dp)) {
                            PageThumb(page, 124, 160, page.rev)
                            if (page.bookmark) Box(Modifier.align(Alignment.TopEnd).padding(end = 6.dp).size(10.dp, 16.dp).background(p.mark))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label("${i + 1}", 12f, color = p.ink(0.7f))
                            PopButton({ open -> Box(Modifier.clickable(onClick = open).padding(4.dp)) { Icon("chevron", p.ink(0.6f), 12.dp) } }) { close -> MenuList(pageMenu(ed, page), close) }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().clickable { ed.addPages(listOf(doc.pages.last().blankLike())) }.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon("plus", p.ink(0.6f))
                        Label(tr("添加页面"), 12f, color = p.ink(0.6f))
                    }
                }
            }
            else -> {
                val rows = pages.withIndex().filter { if (tab == 1) it.value.outline != null else it.value.bookmark }
                if (rows.isEmpty()) {
                    Label(if (tab == 1) tr("还没有大纲。在页面菜单里可以把页面加入大纲。") else tr("还没有书签。"), 14f, color = p.ink(0.6f), modifier = Modifier.padding(20.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    listItemsIndexed(rows) { _, (i, page) ->
                        Row(Modifier.fillMaxWidth().clickable { ed.canvas.scrollToPage(i) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Label(if (tab == 1) page.outline ?: "" else trf("第 %d 页", i + 1), modifier = Modifier.weight(1f))
                            Label("${i + 1}", 13f, color = p.ink(0.5f))
                        }
                    }
                }
            }
        }
    }
}

/** The menu of a page's thumbnail. */
fun pageMenu(ed: EditorState, page: Page): List<MenuItem?> {
    val rows = mutableListOf<MenuItem?>(
        MenuItem(if (page.bookmark) tr("移除书签") else tr("添加书签")) { ed.toggleBookmark(page) },
        MenuItem(if (page.outline != null) tr("从大纲移除") else tr("将页面添加到大纲…")) { ed.editOutline(page) },
        null,
        MenuItem(tr("拷贝页面")) { ed.copyPage(page) },
    )
    if (ed.pageClip != null) rows.add(MenuItem(tr("粘贴页面")) { ed.pastePage(page) })
    rows.addAll(listOf(
        MenuItem(tr("复制页面")) { ed.duplicatePage(page) },
        MenuItem(tr("在后面添加页面")) { ed.addAfter(page) },
        MenuItem(tr("顺时针旋转")) { ed.rotatePage(page, 1) },
        MenuItem(tr("逆时针旋转")) { ed.rotatePage(page, 3) },
        MenuItem(tr("旋转 180°")) { ed.rotatePage(page, 2) },
        MenuItem(tr("更改模板…")) { ed.changeTemplate(page) },
        null,
        MenuItem(tr("清除页面"), danger = true) { ed.clearPage(page) },
        MenuItem(tr("删除页面"), danger = true) { ed.deletePage(page) },
    ))
    return rows
}

/** The whiteboard navigator: where the board is written on, with the part on screen marked, the
 *  zoom level, and a button that brings all of it into view (B:minimap.py). */
@Composable
fun Minimap(ed: EditorState, modifier: Modifier) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    ed.session.tick.intValue
    val zoom = ed.zoom
    val p = palette
    val c = ed.canvas
    val page = ed.doc.pages[c.currentPageIndex().coerceIn(0, ed.doc.pages.size - 1)]
    // Which parts of the board are written on: small blocks, not the ink itself (illegible at this size).
    val (box, cells, cell) = remember(page, page.rev, page.items.size) {
        val b = pageBox(page)
        val cl = max(max((b[2] - b[0]) / 64, (b[3] - b[1]) / 44), 1e-6)
        val set = HashSet<Long>()
        for (it in page.items) {
            if (it is io.github.inkpad.core.Image || it is io.github.inkpad.core.Text) {
                val (bx0, by0, bx1, by1) = it.bbox
                for (cx in ((bx0 - b[0]) / cl).toInt()..((bx1 - b[0]) / cl).toInt()) for (cy in ((by0 - b[1]) / cl).toInt()..((by1 - b[1]) / cl).toInt()) set.add(cx.toLong() shl 32 or (cy.toLong() and 0xffffffff))
            } else {
                val line = it.polyline() ?: continue
                if (line.isEmpty()) continue
                for (pt in if (line.size > 1) Geom.densify(line, cl * 0.45) else line) set.add(((pt[0] - b[0]) / cl).toInt().toLong() shl 32 or (((pt[1] - b[1]) / cl).toInt().toLong() and 0xffffffff))
            }
        }
        Triple(b, set, cl)
    }
    var fit by remember { mutableStateOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0)) }
    Column(modifier.width(216.dp).shadow(10.dp, RoundedCornerShape(16.dp)).background(p.popoverBg, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))) {
        val ink = p.ink
        Canvas(Modifier.size(216.dp, 150.dp).pointerInput(page) {
            awaitPointerEventScopeLoop { x, y ->
                val (rx0, ry0, s, ox, oy) = fit
                c.centerAt(c.currentPageIndex(), rx0 + (x / density - ox) / s, ry0 + (y / density - oy) / s)
            }
        }) {
            val view = c.viewBox() ?: return@Canvas
            zoom.let { }
            val u = Geom.union(box, view)!!
            var (rx0, ry0, rx1, ry1) = u
            val pad = 0.04 * max(rx1 - rx0, ry1 - ry0)
            rx0 -= pad; ry0 -= pad; rx1 += pad; ry1 += pad
            val W = 216.0
            val H = 150.0
            val s = min(W / (rx1 - rx0), H / (ry1 - ry0))
            val ox = (W - (rx1 - rx0) * s) / 2
            val oy = (H - (ry1 - ry0) * s) / 2
            fit = doubleArrayOf(rx0, ry0, s, ox, oy)
            val d = density
            val side = max(cell * s + 0.6, 2.0).toFloat() * d
            for (key in cells) {
                val cx = (key shr 32).toInt()
                val cy = key.toInt()
                drawRect(ink.copy(alpha = 0.55f), Offset(((ox + (box[0] + cx * cell - rx0) * s) * d).toFloat(), ((oy + (box[1] + cy * cell - ry0) * s) * d).toFloat()), Size(side, side))
            }
            val x = (ox + (view[0] - rx0) * s) * d
            val y = (oy + (view[1] - ry0) * s) * d
            val w = (view[2] - view[0]) * s * d
            val h = (view[3] - view[1]) * s * d
            drawRect(Color(0x383685E3), Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()))
            drawRect(Color(0xE63685E3), Offset(x.toFloat(), y.toFloat()), Size(w.toFloat(), h.toFloat()), style = Stroke(1.2f * d))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.ink(0.1f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).clickable { c.setZoom(1.0) }.padding(10.dp), contentAlignment = Alignment.Center) { Label("$zoom%", 14f, FontWeight.Medium) }
            Box(Modifier.width(1.dp).height(30.dp).background(p.ink(0.1f)))
            Box(Modifier.width(46.dp).clickable { c.fitContent() }.padding(10.dp), contentAlignment = Alignment.Center) { Icon("fit_all") }
        }
    }
}

/** Every press and drag inside a pointerInput, as positions in pixels. */
suspend fun androidx.compose.ui.input.pointer.PointerInputScope.awaitPointerEventScopeLoop(onAt: (Float, Float) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        onAt(down.position.x, down.position.y)
        while (true) {
            val e = awaitPointerEvent()
            val ch = e.changes.firstOrNull() ?: break
            if (!ch.pressed) break
            onAt(ch.position.x, ch.position.y)
            ch.consume()
        }
    }
}
