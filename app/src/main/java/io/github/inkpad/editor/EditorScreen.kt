package io.github.inkpad.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.inkpad.AppModel
import io.github.inkpad.EditorSession
import io.github.inkpad.core.Settings
import io.github.inkpad.core.Stroke
import io.github.inkpad.core.colors
import io.github.inkpad.core.deepCopyJson
import io.github.inkpad.core.int
import io.github.inkpad.core.str
import io.github.inkpad.core.strings
import io.github.inkpad.core.sub
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.render.Env
import io.github.inkpad.render.argb
import io.github.inkpad.render.drawStroke
import io.github.inkpad.ui.Confirm
import io.github.inkpad.ui.Field
import io.github.inkpad.ui.Icon
import io.github.inkpad.ui.Label
import io.github.inkpad.ui.MenuItem
import io.github.inkpad.ui.MenuList
import io.github.inkpad.ui.Pill
import io.github.inkpad.ui.PillButton
import io.github.inkpad.ui.PillSeparator
import io.github.inkpad.ui.PopAt
import io.github.inkpad.ui.PopButton
import io.github.inkpad.ui.Prompt
import io.github.inkpad.ui.StatusPill
import io.github.inkpad.ui.Switch
import io.github.inkpad.ui.palette
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// The editor around the canvas (B:window.py; W:src/editor/editorview.js; A:Editor/EditorView.swift):
// the blue header with the tools, the bars floating over the canvas, the selection bar, the find bar,
// the page panels and the sidebar.

val TOOLS = listOf(
    Triple("lasso", "lasso", "套索"), Triple("pen", "pen", "笔"), Triple("eraser", "eraser", "橡皮擦"),
    Triple("hl", "highlighter", "荧光笔"), Triple("shape", "shapes", "形状"), Triple("text", "text", "文本"),
    Triple("note", "note", "便签"), Triple("tape", "tape", "胶带"), Triple("laser", "laser", "激光笔"),
)
val PENS = listOf(Triple("fountain", "fountain", "钢笔"), Triple("ball", "ball", "圆珠笔"), Triple("brush", "brush", "画笔"))
val LASER_COLORS = listOf(doubleArrayOf(1.0, 0.16, 0.2), doubleArrayOf(0.1, 0.8, 0.3), doubleArrayOf(0.16, 0.5, 1.0))
val ERASER_SIZES = listOf(4.0, 9.0, 18.0)
val TEXT_SIZES = listOf(12.0, 18.0, 28.0)
val TAPE_COLORS = listOf(doubleArrayOf(0.96, 0.82, 0.36), doubleArrayOf(0.55, 0.8, 0.95), doubleArrayOf(0.95, 0.6, 0.65), doubleArrayOf(0.6, 0.85, 0.6), doubleArrayOf(0.72, 0.72, 0.76))
val TAPE_WIDTHS = listOf(10.0, 16.0, 26.0)
val NOTE_COLORS = listOf(doubleArrayOf(1.0, 0.93, 0.55), doubleArrayOf(1.0, 0.8, 0.86), doubleArrayOf(0.75, 0.9, 1.0), doubleArrayOf(0.8, 0.95, 0.75))
val PALETTE = listOf(
    doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.37, 0.37, 0.37), doubleArrayOf(0.6, 0.6, 0.6), doubleArrayOf(0.82, 0.82, 0.82), doubleArrayOf(1.0, 1.0, 1.0),
    doubleArrayOf(1.0, 0.1, 0.1), doubleArrayOf(0.53, 0.18, 0.57), doubleArrayOf(0.95, 0.33, 0.33), doubleArrayOf(1.0, 0.6, 0.6), doubleArrayOf(0.97, 0.58, 0.11),
    doubleArrayOf(0.04, 0.47, 1.0), doubleArrayOf(0.05, 0.29, 0.56), doubleArrayOf(0.03, 0.62, 0.36), doubleArrayOf(0.49, 0.76, 0.22), doubleArrayOf(1.0, 1.0, 0.5),
)
const val PEN_SENS_DIVISOR = 50.0   // B: sens / 50

fun pctToWidth(pct: Int) = 0.3 + pct * 0.047
fun widthToPct(w: Double) = ((w - 0.3) / 0.047).roundToInt().coerceIn(0, 100)

fun colorOf(c: DoubleArray) = Color(argb(c))

/** What the editor screen keeps besides the document. */
class EditorState(val app: AppModel, val session: EditorSession) : CanvasHost {
    val st get() = app.settings
    var tool by mutableStateOf("pen")
    var previousTool = "pen"
    var showOptions by mutableStateOf(true)
    var sideOpen by mutableStateOf(false)
    var rulerOn by mutableStateOf(false)
    var finding by mutableStateOf(false)
    var query by mutableStateOf("")
    var findAt by mutableIntStateOf(-1)
    var findCount by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var zoom by mutableIntStateOf(100)
    val selTick = mutableIntStateOf(0)
    var menuAt by mutableStateOf<Pair<Double, Double>?>(null)
    var textAsk by mutableStateOf<Triple<Pair<Double, Double>, String?, (String?) -> Unit>?>(null)
    var prompt by mutableStateOf<Triple<String, String, (String?) -> Unit>?>(null)
    var confirm by mutableStateOf<Triple<String, String, () -> Unit>?>(null)
    var pageClip: io.github.inkpad.core.Page? = null
    var paperFor by mutableStateOf<PaperRequest?>(null)
    var exportAsk by mutableStateOf<ExportRequest?>(null)
    var coverAsk by mutableStateOf(false)
    val optionsTick = mutableIntStateOf(0)
    lateinit var canvas: PageCanvas
    val hasCanvas get() = ::canvas.isInitialized

    val doc get() = session.doc
    fun currentPage() = doc.pages[page.coerceIn(0, doc.pages.size - 1)]

    // -- the canvas tells -------------------------------------------------------------------------

    override fun viewChanged() {
        page = canvas.currentPageIndex()
        zoom = (canvas.shownZoom() * 100).roundToInt()
        selTick.intValue++
    }

    override fun selectionChanged() {
        selTick.intValue++
    }

    override fun menuWanted(x: Double, y: Double) {
        menuAt = Pair(x, y)
    }

    override fun scribbled(n: Int) {
        app.show(trf("已擦除 %d 项", n), tr("撤销")) {
            canvas.cancel()
            canvas.clearSelection()
            doc.undo()
        }
    }

    override fun eraseDone() {
        if (st["eraser_auto"] == true && previousTool != "eraser") selectTool(previousTool)
    }

    override fun pullAdd(direction: Int) {
        val pages = doc.pages
        val i = if (direction > 0) pages.size else 0
        doc.addPages(i, listOf(pages[if (direction > 0) pages.size - 1 else 0].blankLike()))
        canvas.scrollToPage(i)
    }

    override fun editText(x: Double, y: Double, existing: String?, done: (String?) -> Unit) {
        textAsk = Triple(Pair(x, y), existing, done)
    }

    override fun log(line: String) = app.logInput(line)

    // -- settings to the canvas (B: _sync) ---------------------------------------------------------

    fun pen(): MutableMap<String, Any?> = st.sub("pens").sub(st.str("pen_type"))

    fun syncCanvas() {
        val c = canvas
        val pen = pen()
        val widths = pen["widths"] as List<*>
        c.penType = st.str("pen_type")
        c.penColor = st.colors("pen_colors").getOrElse(st.int("pen_color")) { doubleArrayOf(0.0, 0.0, 0.0) }.copyOf()
        c.penWidth = (widths[(pen["slot"] as Number).toInt()] as Number).toDouble()
        c.penSens = (pen["sens"] as Number).toDouble() / PEN_SENS_DIVISOR
        c.penStab = (pen["stab"] as Number).toDouble() / 100
        c.penDash = ((pen["dash"] as? Number) ?: 0).toInt()
        c.holdShape = st["hold_shape"] == true
        c.hlColor = st.colors("hl_colors").getOrElse(st.int("hl_color")) { doubleArrayOf(1.0, 0.88, 0.3) }.copyOf()
        c.hlWidth = ((st["hl_widths"] as List<*>)[st.int("hl_w")] as Number).toDouble()
        c.eraserR = ERASER_SIZES[st.int("eraser_size").coerceIn(0, 2)]
        c.eraserMode = st.str("eraser_mode")
        c.eraseFilter = st.strings("erase_filter").toSet()
        c.lassoRect = st["lasso_rect"] == true
        c.lassoFilter = st.strings("lasso_filter").toSet()
        c.shapeKind = st.str("shape_kind")
        c.textSize = TEXT_SIZES[st.int("text_size").coerceIn(0, 2)]
        c.scribbleErase = st["scribble_erase"] == true
        c.scribbleLoose = st["scribble_loose"] == true
        c.fingerDraw = st["finger_draw"] == true
        c.palmMs = st.int("palm_ms").toLong()
        c.inputLog = st["input_log"] == true
        c.penButtonErases = st["pen_button_erases"] != false
        c.noteColor = NOTE_COLORS[st.int("note_color") % NOTE_COLORS.size]
        c.tapeColor = TAPE_COLORS[st.int("tape_color") % TAPE_COLORS.size]
        c.tapeWidth = TAPE_WIDTHS[st.int("tape_w") % TAPE_WIDTHS.size]
        c.mouseHand = st["mouse_hand"] != false
        c.setPaging(st.str("paging"))
        c.scrollbars = st["scrollbars"] != false
        c.maxZoom = maxOf(2.0, (st["max_zoom"] as? Number)?.toDouble() ?: 6.0)
        c.showCross = st["crosshair"] != false
        c.laserMode = st.str("laser_mode")
        c.laserColor = LASER_COLORS[st.int("laser_color") % LASER_COLORS.size]
        if (c.tool != tool) c.setTool(tool)
        val hidden = st.strings("hidden_tools").toSet() - "pen"
        if (tool in hidden) selectTool("pen")
        if ("ruler" in hidden && rulerOn) {
            rulerOn = false
            c.setRuler(false)
        }
        c.invalidate()
    }

    fun save() {
        app.saveSettings()
        syncCanvas()
        optionsTick.intValue++
    }

    /** Tapping the tool that is already selected hides or shows its options bar. */
    fun toolClicked(name: String) {
        if (name == tool) {
            showOptions = !showOptions
            return
        }
        selectTool(name)
    }

    fun selectTool(name: String) {
        if (tool != "eraser") previousTool = tool
        tool = name
        showOptions = true
        canvas.setTool(name)
    }

    fun undo() {
        canvas.cancel()
        canvas.clearSelection()
        doc.undo()
    }

    fun redo() {
        canvas.cancel()
        canvas.clearSelection()
        doc.redo()
    }

    // -- find in notebook -------------------------------------------------------------------------

    fun find() {
        val q = query
        val marks = ArrayList<Pair<io.github.inkpad.core.Page, io.github.inkpad.core.Box>>()
        if (q.isNotBlank()) {
            for (page in doc.pages) {
                for (it in page.items) if (it is io.github.inkpad.core.Text && it.text.contains(q, ignoreCase = true)) marks.add(Pair(page, it.bbox))
                for (box in io.github.inkpad.pdf.PdfPages.find(page, q)) marks.add(Pair(page, box))
            }
        }
        canvas.setMarks(marks, if (marks.isEmpty()) -1 else 0)
        findAt = canvas.markAt
        findCount = marks.size
    }

    fun findStep(step: Int) {
        val n = canvas.marks.size
        if (n == 0) return
        canvas.setMarks(ArrayList(canvas.marks), (canvas.markAt + step + n) % n)
        findAt = canvas.markAt
    }

    fun hideFind() {
        finding = false
        canvas.setMarks(emptyList())
    }

    // -- page operations (B:window.py; W:src/editor/pageops.js) -------------------------------------

    fun index(page: io.github.inkpad.core.Page) = doc.pages.indexOfFirst { it === page }

    fun toggleBookmark(page: io.github.inkpad.core.Page) {
        page.bookmark = !page.bookmark
        doc.touch()
    }

    fun editOutline(page: io.github.inkpad.core.Page) {
        if (page.outline != null) {
            page.outline = null
            doc.touch()
            return
        }
        prompt = Triple(tr("添加到大纲"), trf("第 %d 页", index(page) + 1)) { text ->
            if (!text.isNullOrBlank()) {
                page.outline = text.trim()
                doc.touch()
            }
        }
    }

    fun copyPage(page: io.github.inkpad.core.Page) {
        pageClip = page.clone()
        app.show(tr("已拷贝页面"))
    }

    fun insertPage(after: io.github.inkpad.core.Page, page: io.github.inkpad.core.Page) {
        val i = index(after) + 1
        doc.addPages(i, listOf(page))
        canvas.scrollToPage(i)
    }

    fun pastePage(page: io.github.inkpad.core.Page) {
        pageClip?.let { insertPage(page, it.clone()) }
    }

    fun duplicatePage(page: io.github.inkpad.core.Page) = insertPage(page, page.clone())
    fun addAfter(page: io.github.inkpad.core.Page) = insertPage(page, page.blankLike())

    /** Turn a page k quarter turns clockwise, its content with it. */
    fun rotatePage(page: io.github.inkpad.core.Page, k: Int) {
        val i = index(page)
        if (i < 0) return
        canvas.clearSelection()
        doc.perform(io.github.inkpad.core.Action.Pages(listOf(Pair(i, page)), listOf(Pair(i, page.rotated(k)))))
    }

    fun deletePage(page: io.github.inkpad.core.Page) {
        val i = index(page)
        val added = if (doc.pages.size == 1) listOf(Pair(0, page.blankLike())) else emptyList()
        doc.perform(io.github.inkpad.core.Action.Pages(listOf(Pair(i, page)), added))
    }

    fun clearPage(page: io.github.inkpad.core.Page) {
        if (page.items.isNotEmpty()) {
            canvas.clearSelection()
            doc.removeItems(page, ArrayList(page.items))
        }
    }

    fun changeTemplate(page: io.github.inkpad.core.Page) {
        if (page.pdf != null) {
            app.show(tr("PDF 页面不能更换纸张模板"))
            return
        }
        paperFor = PaperRequest(page.template(), page.board, tr("纸张样式"), tr("应用")) { t ->
            if (!page.board) {
                st["template"] = linkedMapOf("w" to t.w, "h" to t.h, "paper" to t.paper, "color" to t.color.toList())
                app.saveSettings()
            }
            val old = page.template()
            if (old.w != t.w || old.h != t.h || old.paper != t.paper || !old.color.contentEquals(t.color)) {
                doc.perform(io.github.inkpad.core.Action.Retemplate(page, old, t))
            }
        }
    }

    fun addIndex(): Int = when (st.str("add_pos")) {
        "last" -> doc.pages.size
        "before" -> index(currentPage())
        else -> index(currentPage()) + 1
    }

    fun addPages(pages: List<io.github.inkpad.core.Page>) {
        val i = addIndex()
        doc.addPages(i, pages)
        canvas.scrollToPage(i)
    }

    fun gotoPage() {
        val total = doc.pages.size
        prompt = Triple(trf("共 %d 页", total), (canvas.currentPageIndex() + 1).toString()) { text ->
            val n = text?.trim()?.toIntOrNull()
            if (n != null && n in 1..total) canvas.scrollToPage(n - 1)
        }
    }

    fun rename() {
        prompt = Triple(tr("重命名"), session.title) { name ->
            if (name.isNullOrBlank()) return@Triple
            session.save(wait = true)
            try {
                val next = app.store.rename(session.file, name)
                session.moved(next)
                doc.touch()   // the cover shows the name
                session.tick.intValue++
            } catch (e: Exception) {
                app.alert = e.message
            }
        }
    }
}

class PaperRequest(val template: io.github.inkpad.core.Template, val board: Boolean, val heading: String, val apply: String, val done: (io.github.inkpad.core.Template) -> Unit)
class ExportRequest(val heading: String, val range: Boolean, val done: (List<Int>?, Boolean, Boolean) -> Unit)

/** "1-3, 5" -> [0, 1, 2, 4]; empty means every page (null). Throws when unreadable. */
fun pageRange(text0: String, total: Int): List<Int>? {
    val text = text0.replace('，', ',').replace('、', ',').replace('–', '-').trim()
    if (text.isEmpty()) return null
    val out = ArrayList<Int>()
    for (part in text.split(',')) {
        val (a, b) = part.trim().split('-', limit = 2).let { Pair(it[0].trim(), it.getOrNull(1)?.trim()) }
        val first = a.toInt()
        val last = (b?.takeIf { it.isNotEmpty() } ?: a).toInt()
        require(first in 1..last && last <= total) { part }
        for (n in first..last) if (n - 1 !in out) out.add(n - 1)
    }
    return out
}

@Composable
fun EditorScreen(app: AppModel, session: EditorSession) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ed = remember(session) { EditorState(app, session) }
    val p = palette
    session.tick.intValue
    app.settingsTick.intValue
    val canvas = remember(session) {
        PageCanvas(context, ed).also { c ->
            ed.canvas = c
            session.canvas = c
            c.canvasBg = argb(doubleArrayOf(p.canvasBg.red.toDouble(), p.canvasBg.green.toDouble(), p.canvasBg.blue.toDouble()))
            ed.syncCanvas()
            c.setDocument(session.doc)
            app.viewOf(session.doc.id)?.let { c.restoreMemo(it) }
            session.onReplaced = { ed.syncCanvas() }
        }
    }
    LaunchedEffect(p.dark) {
        canvas.canvasBg = argb(doubleArrayOf(p.canvasBg.red.toDouble(), p.canvasBg.green.toDouble(), p.canvasBg.blue.toDouble()))
        canvas.invalidate()
    }
    LaunchedEffect(app.settingsTick.intValue) { ed.syncCanvas() }
    androidx.activity.compose.BackHandler { app.closeEditor() }
    BoxWithConstraints(Modifier.fillMaxSize().background(p.canvasBg)) {
        val compact = maxWidth < 700.dp
        Column(Modifier.fillMaxSize()) {
            Header(ed, compact)
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(ed.sideOpen, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                    Row {
                        Sidebar(ed, Modifier.width(if (compact) 250.dp else 300.dp).fillMaxHeight())
                        Box(Modifier.width(1.dp).fillMaxHeight().background(p.ink(0.1f)))
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView({ canvas }, Modifier.fillMaxSize())
                    Floating(ed, compact)
                    SelectionBar(ed)
                    if (ed.finding) FindBar(ed, Modifier.align(Alignment.TopEnd).padding(top = 62.dp, end = 12.dp))
                    if (session.doc.pages[0].board) Minimap(ed, Modifier.align(Alignment.BottomStart).padding(14.dp))
                    ed.menuAt?.let { (x, y) -> CanvasMenu(ed, x, y) }
                }
            }
        }
    }
    ed.textAsk?.let { (_, existing, done) ->
        Prompt(if (existing == null) tr("文本") else tr("编辑文本"), existing ?: "", tr("完成"), multiline = true) { text ->
            ed.textAsk = null
            done(text)
        }
    }
    ed.prompt?.let { (title, initial, done) ->
        Prompt(title, initial) { text ->
            ed.prompt = null
            done(text)
        }
    }
    ed.confirm?.let { (title, message, done) ->
        Confirm(title, message, tr("确定"), danger = true) { ok ->
            ed.confirm = null
            if (ok) done()
        }
    }
    ed.paperFor?.let { req ->
        io.github.inkpad.ui.PaperDialog(req.template, req.board, req.heading, req.apply, cover = null) { t, _, _ ->
            ed.paperFor = null
            if (t != null) req.done(t)
        }
    }
    ed.exportAsk?.let { req -> ExportDialog(req, ed.doc.pages.size) { ed.exportAsk = null } }
    if (ed.coverAsk) {
        io.github.inkpad.ui.CoverDialog(ed.doc.coverSpec, session.title) { chosen, ok ->
            ed.coverAsk = false
            if (ok) ed.doc.setCover(chosen)
        }
    }
    DisposableEffect(session) { onDispose { } }
}

// -- header ----------------------------------------------------------------------------------------

@Composable
private fun HeaderButton(checked: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    val p = palette
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (checked) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .height(34.dp)
            .widthIn(min = 38.dp)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Header(ed: EditorState, compact: Boolean) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val app = ed.app
    Column(Modifier.background(p.header).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            HeaderButton(onClick = { app.closeEditor() }) { Icon("home", Color.White) }
            PopButton({ open ->
                HeaderButton(onClick = open) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(ed.session.title, 16.5f, FontWeight.SemiBold, Color.White, maxLines = 1, modifier = Modifier.widthIn(max = if (compact) 130.dp else 220.dp))
                        Icon("chevron", Color.White, 14.dp)
                    }
                }
            }) { close ->
                MenuList(listOf(
                    MenuItem(tr("重命名…"), "rename") { ed.rename() },
                    MenuItem(tr("更改封面…"), "cover") { ed.coverAsk = true },
                    MenuItem(tr("导入 PDF 页面…"), "share") { io.github.inkpad.io.Importer.importPdfPages(ed) },
                    null,
                    MenuItem(tr("立即保存"), "check_circle") {
                        ed.session.save(wait = true)
                        app.show(tr("已保存"))
                    },
                ), close)
            }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(22.dp).background(Color.White.copy(alpha = 0.28f)))
            HeaderButton(ed.sideOpen, { ed.sideOpen = !ed.sideOpen }) { Icon("sidebar", if (ed.sideOpen) p.headerInk else Color.White) }
            HeaderButton(onClick = {
                ed.finding = true
                ed.find()
            }) { Icon("search", Color.White) }
            Spacer(Modifier.weight(1f))
            if (!compact) {
                Box(Modifier.horizontalScroll(rememberScrollState())) { ToolGroup(ed) }
                Spacer(Modifier.weight(1f))
            }
            PopButton({ open -> HeaderButton(onClick = open) { Icon("page_add", Color.White) } }) { close -> AddPagePanel(ed, close) }
            PopButton({ open -> HeaderButton(onClick = open) { Icon("share", Color.White) } }) { close ->
                MenuList(listOf(
                    MenuItem(tr("导出为矢量 PDF…"), "share") { io.github.inkpad.io.Exporter.exportPdf(ed) },
                    MenuItem(tr("导出当前页为图片…"), "image") { io.github.inkpad.io.Exporter.exportPng(ed) },
                    MenuItem(tr("导出全部页面为图片…"), "duplicate") { io.github.inkpad.io.Exporter.exportPngAll(ed) },
                    null,
                    MenuItem(tr("导出为 Goodnotes（实验性）…"), "cover") { io.github.inkpad.io.Exporter.exportGoodnotes(ed) },
                    null,
                    MenuItem(tr("分享笔记本文件"), "cover") { io.github.inkpad.io.Exporter.shareInkpad(ed) },
                ), close)
            }
            PopButton({ open -> HeaderButton(onClick = open) { Icon("more", Color.White) } }) { close -> PagePanel(ed, close) }
        }
        if (compact) {
            Box(Modifier.fillMaxWidth().height(44.dp).padding(bottom = 4.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) { ToolGroup(ed) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black.copy(alpha = 0.18f)))
    }
}

@Composable
private fun ToolGroup(ed: EditorState) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val hidden = ed.st.strings("hidden_tools").toSet()
    Row(Modifier.background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(13.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((id, icon, _) in TOOLS) {
            if (id != "pen" && id in hidden) continue
            val on = ed.tool == id
            HeaderButton(on, { ed.toolClicked(id) }) { Icon(icon, if (on) p.headerInk else Color.White) }
        }
        if ("image" !in hidden) HeaderButton(onClick = { io.github.inkpad.io.Importer.insertPicture(ed) }) { Icon("image", Color.White) }
        if ("ruler" !in hidden) HeaderButton(ed.rulerOn, {
            ed.rulerOn = !ed.rulerOn
            ed.canvas.setRuler(ed.rulerOn)
        }) { Icon("ruler", if (ed.rulerOn) p.headerInk else Color.White) }
    }
}

// -- bars floating over the canvas -------------------------------------------------------------------

@Composable
private fun Floating(ed: EditorState, compact: Boolean) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    ed.session.tick.intValue
    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.Top) {
            Pill {
                PillButton(enabled = ed.doc.canUndo, onClick = { ed.undo() }) { Icon("undo", palette.ink(if (ed.doc.canUndo) 1f else 0.35f)) }
                PillButton(enabled = ed.doc.canRedo, onClick = { ed.redo() }) { Icon("redo", palette.ink(if (ed.doc.canRedo) 1f else 0.35f)) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                if (ed.showOptions) OptionsPill(ed)
            }
            // Balances the history pill, so that the options sit in the middle.
            if (!compact) Spacer(Modifier.width(90.dp))
        }
        if (!ed.doc.pages[0].board) {
            StatusPill("${ed.page + 1} / ${ed.doc.pages.size} · ${ed.zoom}%", Modifier.align(Alignment.BottomStart).padding(14.dp))
        }
    }
}

// -- programmatic little icons (B:icons.py) ------------------------------------------------------------

@Composable
fun WeightGlyph(w: Double) {
    val ink = palette.ink
    Canvas(Modifier.size(20.dp)) {
        val k = size.width / 20f
        drawLine(ink, Offset(4 * k, 14 * k), Offset(16 * k, 6 * k), strokeWidth = (w * k).toFloat(), cap = StrokeCap.Round)
    }
}

@Composable
fun DotGlyph(r: Double) {
    val ink = palette.ink
    Canvas(Modifier.size(20.dp)) { drawCircle(ink, (r * size.width / 20).toFloat()) }
}

@Composable
fun LetterGlyph(scale: Double) {
    Label("A", (14 * scale).toFloat() + 2, FontWeight.SemiBold)
}

@Composable
fun LineStyleGlyph(style: Int) {
    val ink = palette.ink
    Canvas(Modifier.size(width = 28.dp, height = 20.dp)) {
        val y = size.height / 2
        when (style) {
            0 -> drawLine(ink, Offset(3f, y), Offset(size.width - 3, y), strokeWidth = 2.5f * density, cap = StrokeCap.Round)
            1 -> {
                var x = 3f
                while (x < size.width - 3) {
                    drawLine(ink, Offset(x, y), Offset(minOf(x + 6 * density, size.width - 3), y), strokeWidth = 2.5f * density, cap = StrokeCap.Round)
                    x += 10 * density
                }
            }
            else -> {
                var x = 4f
                while (x < size.width - 3) {
                    drawCircle(ink, 1.5f * density, Offset(x, y))
                    x += 6 * density
                }
            }
        }
    }
}

/** A colour dot; the selected one gets a ring (and a small arrow: tap it again to change it). */
@Composable
fun Swatch(c: DoubleArray, selected: Boolean = false, size: Int = 22) {
    val p = palette
    Box(
        Modifier
            .size(size.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) p.accent else p.ink(0.2f), CircleShape)
            .padding(if (selected) 3.dp else 1.dp)
            .background(colorOf(c), CircleShape),
    )
}

@Composable
fun WheelGlyph(size: Int = 26) {
    Canvas(Modifier.size(size.dp)) {
        val colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
        drawCircle(androidx.compose.ui.graphics.Brush.sweepGradient(colors))
        drawCircle(Color.White, this.size.minDimension / 5)
    }
}

// -- the options pill: what the tool in hand can be set to ---------------------------------------------

@Composable
private fun OptionsPill(ed: EditorState) {
    ed.optionsTick.intValue
    val st = ed.st
    Pill {
        when (ed.tool) {
            "pen" -> {
                PopButton({ open ->
                    PillButton(tint = palette.accent, wide = true, onClick = open) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(PENS.firstOrNull { it.first == st.str("pen_type") }?.second ?: "pen")
                            Icon("chevron", size = 14.dp)
                        }
                    }
                }) { close -> PenPanel(ed) }
                PillSeparator()
                PenWeights(ed)
                PillSeparator()
                ColorSlots(ed, "pen_colors", "pen_color")
            }
            "tape" -> {
                Group(ed, "tape_w", listOf(0L to { WeightGlyph(3.5) }, 1L to { WeightGlyph(5.5) }, 2L to { WeightGlyph(7.5) }))
                PillSeparator()
                Group(ed, "tape_color", TAPE_COLORS.mapIndexed { i, c -> i.toLong() to @Composable { Swatch(c) } })
                PillSeparator()
                Note(tr("画过要遮住的内容；用手指或鼠标点一下胶带可掀开或盖回"))
            }
            "note" -> {
                Group(ed, "note_color", NOTE_COLORS.mapIndexed { i, c -> i.toLong() to @Composable { Swatch(c) } })
                PillSeparator()
                Note(tr("点击页面放置便签，再点便签可修改"))
            }
            "hl" -> {
                Weights(ed, st, "hl_w", st["hl_widths"] as MutableList<Any?>, 3.0, 40.0, 0.34, hl = true)
                PillSeparator()
                ColorSlots(ed, "hl_colors", "hl_color")
            }
            "eraser" -> {
                PopButton({ open ->
                    PillButton(tint = palette.accent, wide = true, onClick = open) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon("eraser")
                            Label(tr(if (st.str("eraser_mode") == "stroke") "笔画" else "标准"))
                            Icon("chevron", size = 14.dp)
                        }
                    }
                }) { close -> EraserPanel(ed, close) }
                PillSeparator()
                Group(ed, "eraser_size", listOf(0L to { DotGlyph(2.5) }, 1L to { DotGlyph(4.5) }, 2L to { DotGlyph(7.0) }))
            }
            "shape" -> {
                GroupStr(ed, "shape_kind", listOf("line" to "shape_line", "arrow" to "shape_arrow", "rect" to "shape_rect", "ellipse" to "shape_ellipse"))
                PillSeparator()
                PenWeights(ed)
                PillSeparator()
                ColorSlots(ed, "pen_colors", "pen_color")
            }
            "laser" -> {
                GroupStr(ed, "laser_mode", listOf("dot" to "laser_dot", "trail" to "laser_trail"))
                PillSeparator()
                Group(ed, "laser_color", LASER_COLORS.mapIndexed { i, c -> i.toLong() to @Composable { Swatch(c) } })
            }
            "text" -> {
                Group(ed, "text_size", listOf(0L to { LetterGlyph(0.6) }, 1L to { LetterGlyph(0.85) }, 2L to { LetterGlyph(1.15) }))
                PillSeparator()
                ColorSlots(ed, "pen_colors", "pen_color")
            }
            else -> {
                val rect = st["lasso_rect"] == true
                PopButton({ open ->
                    PillButton(tint = palette.accent, wide = true, onClick = open) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(if (rect) "lasso_rect" else "lasso")
                            Label(if (rect) tr("长方形") else tr("自由"))
                            Icon("chevron", size = 14.dp)
                        }
                    }
                }) { LassoPanel(ed) }
                PillSeparator()
                Note(tr("拖动移动 · 右下角手柄缩放 · Delete 删除"))
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Label(text, 13f, color = palette.ink(0.7f), maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 380.dp))
}

@Composable
private fun Group(ed: EditorState, key: String, entries: List<Pair<Long, @Composable () -> Unit>>) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val current = ed.st.int(key).toLong()
    for ((value, look) in entries) {
        PillButton(checked = value == current, onClick = {
            ed.st[key] = value
            ed.save()
        }) { look() }
    }
}

@Composable
private fun GroupStr(ed: EditorState, key: String, entries: List<Pair<String, String>>) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val current = ed.st.str(key)
    for ((value, icon) in entries) {
        PillButton(checked = value == current, onClick = {
            ed.st[key] = value
            ed.save()
        }) { Icon(icon) }
    }
}

@Composable
private fun PenWeights(ed: EditorState) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val pen = ed.pen()
    @Suppress("UNCHECKED_CAST")
    Weights(ed, pen, "slot", pen["widths"] as MutableList<Any?>, 0.3, 8.0, 1.3, hl = false)
}

/** Thickness presets: tap to select, tap the selected one to set its width. */
@Composable
private fun Weights(ed: EditorState, target: MutableMap<String, Any?>, key: String, widths: MutableList<Any?>, low: Double, high: Double, look: Double, hl: Boolean) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val current = (target[key] as Number).toInt()
    widths.forEachIndexed { i, w0 ->
        val w = (w0 as Number).toDouble()
        if (i == current) {
            PopButton({ open -> PillButton(checked = true, onClick = open) { WeightGlyph((w * look).coerceIn(0.8, 7.5)) } }) {
                WidthPanel(ed, widths, i, low, high, hl)
            }
        } else {
            PillButton(onClick = {
                target[key] = i.toLong()
                ed.save()
            }) { WeightGlyph((w * look).coerceIn(0.8, 7.5)) }
        }
    }
}

/** Colour slots: tap to select, tap the selected one to change or remove it, + adds one. */
@Composable
private fun ColorSlots(ed: EditorState, colorsKey: String, indexKey: String) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val colors = ed.st.colors(colorsKey)
    val current = ed.st.int(indexKey)
    colors.forEachIndexed { i, c ->
        if (i == current) {
            PopButton({ open -> PillButton(round = true, checked = true, onClick = open) { Swatch(c, selected = true) } }) { close ->
                PalettePanel(ed, colorsKey, indexKey, add = false, close)
            }
        } else {
            PillButton(round = true, onClick = {
                ed.st[indexKey] = i.toLong()
                ed.save()
            }) { Swatch(c) }
        }
    }
    if (colors.size < 9) {
        PopButton({ open -> PillButton(round = true, onClick = open) { Icon("plus", size = 16.dp) } }) { close ->
            PalettePanel(ed, colorsKey, indexKey, add = true, close)
        }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Label(text, 15f, FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
}

@Composable
private fun Caption(text: String) {
    Label(text, 13f, color = palette.ink(0.6f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
}

@Composable
private fun SwitchLine(text: String, on: Boolean, icon: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (icon != null) Icon(icon, size = 18.dp)
        Label(text, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

/** Big side-by-side choice buttons with an icon above a label. */
@Composable
private fun <T> ChoiceRow(current: T, entries: List<Triple<T, @Composable () -> Unit, String>>, onPick: (T) -> Unit) {
    val p = palette
    Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((value, look, text) in entries) {
            val on = value == current
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) p.cardBg else Color.Transparent)
                    .border(1.dp, if (on) p.ink(0.12f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { onPick(value) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                look()
                Label(text, 13f, align = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Slider(value: Double, low: Double, high: Double, onChange: (Double) -> Unit) {
    val p = palette
    var width by remember { mutableIntStateOf(1) }
    val frac = ((value - low) / (high - low)).coerceIn(0.0, 1.0).toFloat()
    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onGloballyPositioned { width = it.size.width }
            .pointerInputDrag { x -> onChange(low + (high - low) * (x / width).coerceIn(0f, 1f)) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(p.ink(0.15f), CircleShape))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).background(p.accent, CircleShape))
        val px = with(LocalDensity.current) { (width * frac).toDp() - 12.dp }
        Box(Modifier.offset(x = px).size(24.dp).background(Color.White, CircleShape).border(1.dp, p.ink(0.2f), CircleShape))
    }
}

/** Drags and taps across a control, reported as x in pixels. */
fun Modifier.pointerInputDrag(onX: (Float) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            onX(down.position.x)
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull() ?: break
                if (!c.pressed) break
                onX(c.position.x)
                c.consume()
            }
        }
    },
)

@Composable
private fun StrokePreview(ed: EditorState, width: Double, hl: Boolean = false, wave: Boolean = true) {
    ed.optionsTick.intValue
    val st = ed.st
    val pen = ed.pen()
    val ink = palette.ink
    Canvas(Modifier.size(280.dp, if (hl) 44.dp else 84.dp)) {
        val k = density
        val pts = if (hl || !wave) (0..40).map { i -> doubleArrayOf(20.0 + 240 * (i / 40.0), 22 + 7 * sin(i / 40.0 * 2 * PI), 0.6) }
        else (0..60).map { i -> val t = i / 60.0; doubleArrayOf(24 + 232 * t, 42 + 22 * sin(t * 2 * PI + PI), 0.12 + 0.85 * sin(t * PI)) }
        val s = if (hl) Stroke("hl", ed.canvas.hlColor, width, pts)
        else Stroke(if (wave) st.str("pen_type") else "ball", ed.canvas.penColor, width * (if (wave) 1.5 else 1.0), pts, ((pen["sens"] as Number).toDouble()) / PEN_SENS_DIVISOR, ((pen["dash"] as? Number) ?: 0).toInt())
        val c = drawContext.canvas.nativeCanvas
        c.save()
        c.scale(k, k)
        drawStroke(c, Env(0.0, 0.0, 0.5, true), s)
        c.restore()
        if (ink.alpha < 0) drawCircle(ink, 0f)
    }
}

@Composable
private fun PenPanel(ed: EditorState) {
    ed.optionsTick.intValue
    val st = ed.st
    val pen = ed.pen()
    val widths = pen["widths"] as MutableList<Any?>
    val slot = (pen["slot"] as Number).toInt()
    Column(Modifier.width(300.dp)) {
        PanelTitle(tr(PENS.first { it.first == st.str("pen_type") }.third))
        Box(Modifier.align(Alignment.CenterHorizontally)) { StrokePreview(ed, (widths[slot] as Number).toDouble()) }
        ChoiceRow(st.str("pen_type"), PENS.map { (n, ic, t) -> Triple(n, @Composable { Icon(ic) }, tr(t)) }) {
            st["pen_type"] = it
            ed.save()
        }
        Caption(tr("笔画样式"))
        ChoiceRow(((pen["dash"] as? Number) ?: 0).toInt(), listOf(Triple(0, @Composable { LineStyleGlyph(0) }, tr("实线")), Triple(1, @Composable { LineStyleGlyph(1) }, tr("虚线")), Triple(2, @Composable { LineStyleGlyph(2) }, tr("点线")))) {
            pen["dash"] = it.toLong()
            ed.save()
        }
        val pct = widthToPct((widths[slot] as Number).toDouble())
        Row(Modifier.padding(horizontal = 8.dp)) {
            Label(tr("笔尖"), 13f, modifier = Modifier.weight(1f))
            Label("$pct% · ${"%.2f".format((widths[slot] as Number).toDouble() * 0.3528)} mm", 13f, color = palette.ink(0.6f))
        }
        Box(Modifier.padding(horizontal = 8.dp)) {
            Slider(pct.toDouble(), 0.0, 100.0) {
                widths[slot] = (pctToWidth(it.roundToInt()) * 100).roundToInt() / 100.0
                ed.save()
            }
        }
        for ((key, label) in listOf("sens" to "压力灵敏度", "stab" to "画笔稳定性")) {
            val v = (pen[key] as Number).toInt()
            Row(Modifier.padding(horizontal = 8.dp)) {
                Label(tr(label), 13f, modifier = Modifier.weight(1f))
                Label("$v%", 13f, color = palette.ink(0.6f))
            }
            Box(Modifier.padding(horizontal = 8.dp)) {
                Slider(v.toDouble(), 0.0, 100.0) {
                    pen[key] = it.roundToInt().toLong()
                    ed.save()
                }
            }
        }
        Caption(tr("设置"))
        SwitchLine(tr("长按绘制规则形状"), st["hold_shape"] == true) {
            st["hold_shape"] = it
            ed.save()
        }
        SwitchLine(tr("涂画以擦除"), st["scribble_erase"] == true) {
            st["scribble_erase"] = it
            ed.save()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PillButton(onClick = {
                st.sub("pens")[st.str("pen_type")] = deepCopyJson(Settings.DEFAULTS.sub("pens")[st.str("pen_type")])
                ed.save()
            }) { Icon("reset", size = 18.dp) }
        }
    }
}

@Composable
private fun WidthPanel(ed: EditorState, widths: MutableList<Any?>, slot: Int, low: Double, high: Double, hl: Boolean) {
    ed.optionsTick.intValue
    val w = (widths[slot] as Number).toDouble()
    Column(Modifier.width(300.dp)) {
        PanelTitle(tr("粗细"))
        Box(Modifier.align(Alignment.CenterHorizontally)) { StrokePreview(ed, w, hl = hl, wave = false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            PillButton(onClick = {
                widths[slot] = ((w - 0.1).coerceIn(low, high) * 10).roundToInt() / 10.0
                ed.save()
            }) { Icon("minus", size = 16.dp) }
            Box(Modifier.weight(1f)) {
                Slider(w, low, high) {
                    widths[slot] = (it * 10).roundToInt() / 10.0
                    ed.save()
                }
            }
            PillButton(onClick = {
                widths[slot] = ((w + 0.1).coerceIn(low, high) * 10).roundToInt() / 10.0
                ed.save()
            }) { Icon("plus", size = 16.dp) }
        }
        Label("%.1f pt".format(w), 13f, color = palette.ink(0.6f), modifier = Modifier.align(Alignment.End).padding(8.dp))
    }
}

@Composable
private fun PalettePanel(ed: EditorState, colorsKey: String, indexKey: String, add: Boolean, close: () -> Unit) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val st = ed.st
    @Suppress("UNCHECKED_CAST")
    val colors = st[colorsKey] as MutableList<Any?>
    val current = if (add) null else st.colors(colorsKey).getOrNull(st.int(indexKey))
    var custom by remember { mutableStateOf(false) }
    fun choose(c: DoubleArray) {
        if (add) {
            colors.add(c.toList())
            st[indexKey] = (colors.size - 1).toLong()
        } else {
            colors[st.int(indexKey)] = c.toList()
        }
        ed.save()
        close()
    }
    Column(Modifier.width(240.dp)) {
        PanelTitle(if (add) tr("添加颜色") else if (colorsKey == "pen_colors") tr("笔颜色") else tr("荧光笔颜色"))
        val all = PALETTE.map { Pair(it, false) } + listOf(Pair(doubleArrayOf(), true))
        for (row in all.chunked(5)) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((c, wheel) in row) {
                    val near = current != null && !wheel && (0..2).all { kotlin.math.abs(c[it] - current[it]) < 0.02 }
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (near) palette.ink(0.12f) else Color.Transparent).clickable {
                        if (wheel) custom = true else choose(c)
                    }, contentAlignment = Alignment.Center) { if (wheel) WheelGlyph() else Swatch(c, size = 26) }
                }
            }
        }
        if (custom) CustomColor(current ?: doubleArrayOf(0.0, 0.0, 0.0)) { choose(it) }
        if (!add && colors.size > 1) {
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(palette.ink(0.1f)))
            MenuList(listOf(MenuItem(tr("删除此颜色"), danger = true) {
                colors.removeAt(st.int(indexKey))
                st[indexKey] = minOf(st.int(indexKey), colors.size - 1).toLong()
                ed.save()
            }), close)
        }
    }
}

/** A colour of one's own: hue, saturation and brightness sliders. */
@Composable
private fun CustomColor(start: DoubleArray, done: (DoubleArray) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb(start), it) } }
    var tick by remember { mutableIntStateOf(0) }
    tick.let { }
    val now = run {
        val c = android.graphics.Color.HSVToColor(hsv)
        doubleArrayOf(android.graphics.Color.red(c) / 255.0, android.graphics.Color.green(c) / 255.0, android.graphics.Color.blue(c) / 255.0)
    }
    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Swatch(now, size = 30)
            Label(tr("自定义颜色"), 13f)
        }
        for ((i, max) in listOf(0 to 360.0, 1 to 1.0, 2 to 1.0)) {
            Slider(hsv[i].toDouble(), 0.0, max) {
                hsv[i] = it.toFloat()
                tick++
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            io.github.inkpad.ui.DialogButton(tr("确定"), suggested = true) { done(now) }
        }
    }
}

@Composable
private fun EraserPanel(ed: EditorState, close: () -> Unit) {
    ed.optionsTick.intValue
    val st = ed.st
    fun toggle(key: String, name: String, on: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val v = st[key] as MutableList<Any?>
        if (on && name !in v) v.add(name) else if (!on) v.remove(name)
        ed.save()
    }
    Column(Modifier.width(300.dp)) {
        PanelTitle(tr("橡皮擦"))
        ChoiceRow(st.str("eraser_mode"), listOf(Triple("standard", @Composable { Icon("eraser") }, tr("标准")), Triple("stroke", @Composable { Icon("ball") }, tr("笔画")))) {
            st["eraser_mode"] = it
            ed.save()
        }
        Caption(tr("标准橡皮擦只擦掉碰到的部分；笔画橡皮擦一次擦除整条笔画。"))
        Caption(tr("橡皮擦筛选"))
        for ((n, ic, t) in listOf(Triple("pen", "pen", "笔"), Triple("hl", "highlighter", "荧光笔"), Triple("shape", "shapes", "形状"))) {
            SwitchLine(tr(t), n in st.strings("erase_filter"), ic) { toggle("erase_filter", n, it) }
        }
        Caption(tr("如工具已关闭，则橡皮擦不会擦除其留下的痕迹。"))
        SwitchLine(tr("自动取消选择"), st["eraser_auto"] == true) {
            st["eraser_auto"] = it
            ed.save()
        }
        MenuList(listOf(MenuItem(tr("清除页面"), danger = true) { ed.clearPage(ed.currentPage()) }), close)
    }
}

@Composable
private fun LassoPanel(ed: EditorState) {
    ed.optionsTick.intValue
    val st = ed.st
    Column(Modifier.width(300.dp)) {
        PanelTitle(tr("套索选取"))
        Caption(tr("套索类型"))
        ChoiceRow(st["lasso_rect"] == true, listOf(Triple(false, @Composable { Icon("lasso") }, tr("自由")), Triple(true, @Composable { Icon("lasso_rect") }, tr("长方形")))) {
            st["lasso_rect"] = it
            ed.save()
        }
        Caption(tr("包含在选取区域中的内容"))
        for ((n, t) in listOf("ink" to "手写内容", "image" to "图像", "shape" to "形状与箭头", "text" to "文本框")) {
            SwitchLine(tr(t), n in st.strings("lasso_filter")) { on ->
                @Suppress("UNCHECKED_CAST")
                val v = st["lasso_filter"] as MutableList<Any?>
                if (on && n !in v) v.add(n) else if (!on) v.remove(n)
                ed.save()
            }
        }
    }
}

// -- the selection bar, and the menu of a long press --------------------------------------------------

@Composable
private fun SelectionBar(ed: EditorState) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    ed.selTick.intValue
    val rect = if (ed.hasCanvas) ed.canvas.selectionRect() else null
    if (rect == null) return
    val density = LocalDensity.current
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize(0, 0)) }
    val W = ed.canvas.W
    val H = ed.canvas.H
    val (x, y, w, h) = rect
    val bw = with(density) { size.width.toDp().value.toDouble() }
    val bh = with(density) { size.height.toDp().value.toDouble() }
    val left = (x + w / 2 - bw / 2).coerceIn(8.0, maxOf(8.0, W - bw - 8))
    var top = y - bh - 14
    // No room above (the undo and options bars sit there): go below.
    if (top < 64) top = y + h + 18
    top = top.coerceIn(8.0, maxOf(8.0, H - bh - 8))
    val c = ed.canvas
    Pill(Modifier.offset { IntOffset((left * density.density).roundToInt(), (top * density.density).roundToInt()) }.onGloballyPositioned { size = it.size }) {
        PopButton({ open -> PillButton(onClick = open) { Icon("palette") } }) { close ->
            for (row in PALETTE.chunked(5)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in row) Box(Modifier.size(36.dp).clickable {
                        close()
                        c.recolorSelection(col)
                    }, contentAlignment = Alignment.Center) { Swatch(col, size = 24) }
                }
            }
        }
        PillSeparator()
        PillButton(onClick = { if (c.copySelection()) c.deleteSelection() }) { Icon("scissors") }
        PillButton(onClick = { if (c.copySelection()) ed.app.show(tr("已拷贝")) }) { Icon("copy") }
        PillButton(onClick = { c.duplicateSelection() }) { Icon("duplicate") }
        PillButton(onClick = { c.deleteSelection() }) { Icon("trash", palette.danger) }
        PopButton({ open -> PillButton(onClick = open) { Icon("dots") } }) { close ->
            MenuList(listOf(
                MenuItem(tr("向左旋转"), "reset") { c.rotateSelection(3) },
                MenuItem(tr("向右旋转"), "rotate_cw") { c.rotateSelection(1) },
                null,
                MenuItem(tr("移到最前"), "to_front") { c.restackSelection(true) },
                MenuItem(tr("移到最后"), "to_back") { c.restackSelection(false) },
                null,
                MenuItem(tr("粘贴"), "paste", enabled = c.hasClip()) { c.paste() },
                MenuItem(tr("截图到剪贴板"), "camera") { io.github.inkpad.io.Exporter.selectionToClipboard(ed) },
                MenuItem(tr("存为图片…"), "image") { io.github.inkpad.io.Exporter.saveSelection(ed) },
            ), close)
        }
    }
}

@Composable
private fun CanvasMenu(ed: EditorState, x: Double, y: Double) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val c = ed.canvas
    val density = LocalDensity.current.density
    val rect = c.selectionRect()
    val paste = MenuItem(tr("粘贴"), "paste", enabled = c.hasClip()) { c.paste(Pair(x, y)) }
    val rows: List<MenuItem?> = if (rect != null && x >= rect[0] - 8 && x <= rect[0] + rect[2] + 8 && y >= rect[1] - 8 && y <= rect[1] + rect[3] + 8) {
        listOf(
            MenuItem(tr("剪切"), "scissors") { if (c.copySelection()) c.deleteSelection() },
            MenuItem(tr("拷贝"), "copy") { if (c.copySelection()) ed.app.show(tr("已拷贝")) },
            MenuItem(tr("复制"), "duplicate") { c.duplicateSelection() },
            MenuItem(tr("删除"), "trash", danger = true) { c.deleteSelection() },
            null, paste,
        )
    } else listOf(paste)
    val loc = IntArray(2)
    c.getLocationInWindow(loc)
    PopAt(loc[0] + (x * density).roundToInt(), loc[1] + (y * density).roundToInt(), { ed.menuAt = null }) {
        MenuList(rows) { ed.menuAt = null }
    }
}

// -- find in notebook ----------------------------------------------------------------------------------

@Composable
private fun FindBar(ed: EditorState, modifier: Modifier) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    Pill(modifier) {
        Icon("search", palette.ink(0.5f), 16.dp)
        Box(Modifier.width(220.dp)) {
            Field(ed.query, {
                ed.query = it
                ed.find()
            }, tr("查找文本框和 PDF 中的文字"), focus = true)
        }
        Label(if (ed.findCount > 0) "${ed.findAt + 1} / ${ed.findCount}" else if (ed.query.isNotBlank()) tr("无结果") else "", 13f,
            color = palette.ink(0.6f), modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 4.dp))
        PillButton(onClick = { ed.findStep(-1) }) { Icon("up", size = 16.dp) }
        PillButton(onClick = { ed.findStep(1) }) { Icon("down", size = 16.dp) }
        PillButton(onClick = { ed.hideFind() }) { Icon("close", size = 16.dp) }
    }
}

// -- the header's panels ---------------------------------------------------------------------------------

@Composable
private fun AddPagePanel(ed: EditorState, close: () -> Unit) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    val st = ed.st
    val page = ed.currentPage()
    var pos by remember { mutableStateOf(st.str("add_pos")) }
    Column(Modifier.width(320.dp)) {
        PanelTitle(if (page.board) tr("添加白板") else tr("添加页面"))
        Row(Modifier.padding(horizontal = 6.dp).fillMaxWidth().background(palette.ink(0.06f), RoundedCornerShape(10.dp)).padding(3.dp)) {
            for ((v, t) in listOf("before" to "在此之前", "after" to "在此之后", "last" to "最后一页")) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (pos == v) palette.cardBg else Color.Transparent).clickable {
                    pos = v
                    st["add_pos"] = v
                    ed.app.saveSettings()
                }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) { Label(tr(t), 13f) }
            }
        }
        Caption(tr("模板"))
        val blank = remember(page) { page.blankLike() }
        Column(Modifier.padding(6.dp).clip(RoundedCornerShape(10.dp)).clickable {
            close()
            ed.addPages(listOf(page.blankLike()))
        }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PageThumb(blank, 78, 104)
            Label(tr("当前模板"), 13f)
        }
        MenuList(listOf(
            null,
            MenuItem(tr("更多模板…"), "grid") {
                ed.paperFor = PaperRequest(page.template(), page.board, tr("添加页面"), tr("添加")) { t ->
                    ed.addPages(listOf(io.github.inkpad.core.Page(t.w, t.h, t.paper, color = t.color, board = page.board)))
                }
            },
            MenuItem(tr("图像…"), "image") { io.github.inkpad.io.Importer.addPicturePage(ed) },
            MenuItem(tr("导入 PDF…"), "share") { io.github.inkpad.io.Importer.importPdfPages(ed) },
        ), close)
    }
}

/** The header menu: page actions as a grid of tiles, then view options and the rest. It stays open
 *  after an action, so the result can be watched and the action repeated. */
@Composable
private fun PagePanel(ed: EditorState, close: () -> Unit) {
    ed.optionsTick.intValue   // the settings are a plain map: this is what redraws it
    ed.session.tick.intValue
    var tick by remember { mutableIntStateOf(0) }
    tick.let { }
    val st = ed.st
    val page = ed.currentPage()
    val p = palette
    fun act(fn: () -> Unit): () -> Unit = {
        fn()
        tick++
    }
    fun leave(fn: () -> Unit): () -> Unit = {
        close()
        fn()
    }
    val tiles = mutableListOf(
        Triple(if (page.bookmark) "bookmark_filled" else "bookmark", if (page.bookmark) tr("移除书签") else tr("书签"), act { ed.toggleBookmark(page) }),
        Triple("outline", tr("大纲"), leave { ed.editOutline(page) }),
        Triple("grid", tr("模板"), leave { ed.changeTemplate(page) }),
        Triple("page_add", tr("加页"), act { ed.addAfter(page) }),
        Triple("copy", tr("拷贝"), act { ed.copyPage(page) }),
        Triple("duplicate", tr("复制"), act { ed.duplicatePage(page) }),
        Triple("reset", tr("左转"), act { ed.rotatePage(page, 3) }),
        Triple("rotate_cw", tr("右转"), act { ed.rotatePage(page, 1) }),
    )
    if (ed.pageClip != null) tiles.add(Triple("paste", tr("粘贴"), act { ed.pastePage(page) }))
    Column(Modifier.width(336.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PanelTitle(trf("第 %d / %d 页", ed.index(page) + 1, ed.doc.pages.size))
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).background(p.ink(0.08f)).clickable(onClick = leave { ed.gotoPage() }).padding(horizontal = 14.dp, vertical = 4.dp)) { Label(tr("前往…"), 14f) }
        }
        for (row in tiles.chunked(4)) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((ic, t, fn) in row) {
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(p.ink(0.05f)).clickable(onClick = fn).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(ic)
                        Label(t, 12f, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.padding(6.dp).fillMaxWidth().background(p.ink(0.06f), RoundedCornerShape(10.dp)).padding(3.dp)) {
            Box(Modifier.weight(1f).clickable { ed.canvas.zoomBy(1 / 1.2) }.padding(8.dp), contentAlignment = Alignment.Center) { Icon("minus", size = 16.dp) }
            Box(Modifier.weight(1f).clickable { ed.canvas.fitWidth() }.padding(8.dp), contentAlignment = Alignment.Center) { Label(tr("适合宽度"), 13f) }
            Box(Modifier.weight(1f).clickable { ed.canvas.zoomBy(1.2) }.padding(8.dp), contentAlignment = Alignment.Center) { Icon("plus", size = 16.dp) }
        }
        SwitchLine(tr("涂画以擦除"), st["scribble_erase"] == true) {
            st["scribble_erase"] = it
            ed.save()
        }
        SwitchLine(tr("用手指书写"), st["finger_draw"] == true) {
            st["finger_draw"] = it
            ed.save()
        }
        MenuList(listOf(
            MenuItem(tr("设置…"), "settings") { ed.app.showSettings = true },
            MenuItem(tr("关于"), "info") { ed.app.alert = "Inkpad\n" + tr("手写笔记、白板与 PDF 批注。") + "\nGPL-3.0" },
            null,
            MenuItem(tr("清除页面"), "eraser", danger = true) { ed.clearPage(page) },
            MenuItem(tr("删除页面"), "trash", danger = true) { ed.deletePage(page) },
        ), close)
    }
}

@Composable
private fun ExportDialog(req: ExportRequest, total: Int, dismiss: () -> Unit) {
    var range by remember { mutableStateOf("") }
    var what by remember { mutableIntStateOf(0) }   // 0 ink and background, 1 ink only, 2 background only
    var bad by remember { mutableStateOf(false) }
    io.github.inkpad.ui.DialogCard(dismiss) {
        Label(req.heading, 17f, FontWeight.Bold)
        if (req.range) {
            Label(trf("页面范围（例如 1-3, 5；留空为全部 %d 页）", total), 13f, color = palette.ink(0.7f))
            Field(range, {
                range = it
                bad = false
            }, tr("全部页面"))
            if (bad) Label(tr("页面范围无法识别"), 13f, color = palette.danger)
        }
        ChoiceRow(what, listOf(Triple(0, @Composable { Icon("pen") }, tr("笔迹和背景")), Triple(1, @Composable { Icon("highlighter") }, tr("仅笔迹")), Triple(2, @Composable { Icon("grid") }, tr("仅背景")))) { what = it }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            io.github.inkpad.ui.DialogButton(tr("取消"), onClick = dismiss)
            Spacer(Modifier.width(8.dp))
            io.github.inkpad.ui.DialogButton(tr("导出"), suggested = true) {
                val pages = try {
                    pageRange(range, total)
                } catch (e: Exception) {
                    bad = true
                    return@DialogButton
                }
                dismiss()
                req.done(pages, what != 1, what != 2)
            }
        }
    }
}
