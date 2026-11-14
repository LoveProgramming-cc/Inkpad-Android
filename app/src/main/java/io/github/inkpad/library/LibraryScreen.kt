package io.github.inkpad.library

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.safeDrawing
import io.github.inkpad.ui.barGlass
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.inkpad.AppModel
import io.github.inkpad.core.EXT
import io.github.inkpad.core.Entry
import io.github.inkpad.core.FOLDER_COLORS
import io.github.inkpad.core.LibrarySync
import io.github.inkpad.core.OpenFile
import io.github.inkpad.core.Template
import io.github.inkpad.core.ZipReader
import io.github.inkpad.core.colors
import io.github.inkpad.core.obj
import io.github.inkpad.core.point
import io.github.inkpad.core.str
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.io.Importer
import io.github.inkpad.render.argb
import io.github.inkpad.ui.Confirm
import io.github.inkpad.ui.DialogButton
import io.github.inkpad.ui.DialogCard
import io.github.inkpad.ui.Icon
import io.github.inkpad.ui.Icons
import io.github.inkpad.ui.Label
import io.github.inkpad.ui.MenuItem
import io.github.inkpad.ui.MenuList
import io.github.inkpad.ui.PaperDialog
import io.github.inkpad.ui.PopButton
import io.github.inkpad.ui.Prompt
import io.github.inkpad.ui.palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

// The library (B:library.py LibraryView): folders, favourites, the trash; covers in a grid; moving
// things by dragging them onto a folder, and taking a move back.

object Covers {
    private val cache = LruCache<String, ImageBitmap>(200)

    fun key(f: File) = f.path + "@" + f.lastModified() + ":" + f.length()
    fun cached(f: File) = cache.get(key(f))

    fun load(f: File): ImageBitmap? = cache.get(key(f)) ?: runCatching {
        OpenFile(f).use { src ->
            val z = ZipReader.open(src)
            // A designed cover carries the notebook's name, which is the file's name now, not the
            // one the stored picture was drawn with (a rename does not redraw it).
            val spec = io.github.inkpad.core.Document.coverSpecOf(z)
            if (spec != null) io.github.inkpad.render.renderCover(spec.first, spec.second, f.nameWithoutExtension).asImageBitmap()
            else {
                val png = z.bytes("cover.png")
                BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap()
            }
        }
    }.getOrNull()?.also { cache.put(key(f), it) }
}

/** strftime for the formats the tables use: %Y %m %-m %d %-d %H %M %b. */
fun fmtDate(fmt: String, millis: Long): String {
    val d = Date(millis)
    val c = java.util.Calendar.getInstance().apply { time = d }
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return Regex("%(-?)([YmdHMb])").replace(fmt) { m ->
        val dash = m.groupValues[1].isNotEmpty()
        fun two(n: Int) = if (dash) n.toString() else "%02d".format(n)
        when (m.groupValues[2]) {
            "Y" -> c.get(java.util.Calendar.YEAR).toString()
            "m" -> two(c.get(java.util.Calendar.MONTH) + 1)
            "d" -> two(c.get(java.util.Calendar.DAY_OF_MONTH))
            "H" -> "%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY))
            "M" -> "%02d".format(c.get(java.util.Calendar.MINUTE))
            else -> months[c.get(java.util.Calendar.MONTH)]
        }
    }
}

class LibraryState(val app: AppModel) {
    val store get() = app.store
    var mode by mutableStateOf("docs")          // docs, favs, trash, deleted
    var folder by mutableStateOf(File(app.root, (app.settings["last_folder"] as? String).orEmpty()).let { if (it.isDirectory) it.canonicalFile else app.store.root })
    var query by mutableStateOf("")
    var selecting by mutableStateOf(false)
    val selected = mutableStateMapOf<File, Boolean>()
    var cursor by mutableStateOf<File?>(null)
    private var clickedAt = 0L
    val undo = ArrayList<List<Pair<File, File>>>()   // moves, each a list of (where it was, where it went)
    val redo = ArrayList<List<Pair<File, File>>>()
    var prompt by mutableStateOf<Triple<String, String, (String?) -> Unit>?>(null)
    var confirm by mutableStateOf<Triple<String, String, () -> Unit>?>(null)
    var newDialog by mutableStateOf<String?>(null)   // "notebook" or "board"
    var pickFolder by mutableStateOf<Triple<String, List<File>, (File) -> Unit>?>(null)
    var choice by mutableStateOf<Pair<Entry, String>?>(null)   // a folder and "color" or "icon"
    val targets = HashMap<String, Pair<Rect, File>>()   // where things can be dropped, by key
    var dragging by mutableStateOf<List<File>>(emptyList())
    var dragAt by mutableStateOf(Offset.Zero)
    var dropOn by mutableStateOf<String?>(null)

    fun openFolder(f: File) {
        folder = f
        cursor = null
        app.settings["last_folder"] = if (f == store.root) "" else store.rel(f)
        app.saveSettings()
        app.refreshLibrary()
    }

    fun entries(): List<Entry> {
        app.libraryTick.intValue
        if (!folder.isDirectory) folder = store.root
        val q = query.trim().lowercase()
        var list = when (mode) {
            // A search looks through every folder, not only the one that is open.
            "docs" -> if (q.isNotEmpty()) store.find(q) else store.entries(folder)
            "favs" -> store.favorites()
            "trash" -> store.trashed()
            else -> store.archived()
        }
        if (q.isNotEmpty() && mode != "docs") list = list.filter { q in it.name.lowercase() }
        return if (app.settings["sort"] == "name") list.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })) else list.sortedByDescending { it.mtime }
    }

    fun title(): String = when {
        query.isNotBlank() -> tr("搜索结果")
        mode == "favs" -> tr("收藏")
        mode == "trash" -> tr("废纸篓")
        mode == "deleted" -> tr("已删除")
        folder == store.root -> tr("文稿")
        else -> folder.name
    }

    fun act(fn: () -> Unit) {
        try {
            fn()
        } catch (e: Exception) {
            app.alert = tr("操作失败") + "\n" + (e.message ?: e.toString())
        }
        app.refreshLibrary()
    }

    fun activate(e: Entry) {
        if (selecting) {
            if (selected.remove(e.file) == null) selected[e.file] = true
            return
        }
        if (mode == "trash" || mode == "deleted") return
        if (e.isDir) {
            // A folder opens on a double tap; the first tap only marks it.
            val now = System.currentTimeMillis()
            val last = cursor
            val was = clickedAt
            cursor = e.file
            clickedAt = now
            if (last != e.file || now - was > 500) return
            cursor = null
            query = ""
            mode = "docs"
            openFolder(e.file)
        } else {
            app.open(e.file)
        }
    }

    /** Move items into a folder, as one step that can be undone. Items already there, and a folder
     *  asked to go inside itself, are left alone. */
    fun moveItems(files: List<File>, to: File): Int {
        val done = ArrayList<Pair<File, File>>()
        for (f in files) {
            if (f.parentFile?.canonicalFile == to.canonicalFile || to.canonicalFile == f.canonicalFile || (to.canonicalPath + "/").startsWith(f.canonicalPath + "/")) continue
            try {
                done.add(Pair(f, store.move(f, to)))
            } catch (e: Exception) {
                app.alert = tr("操作失败") + "\n" + e.message
                break
            }
        }
        if (done.isNotEmpty()) {
            undo.add(done)
            while (undo.size > 50) undo.removeAt(0)
            redo.clear()
            val where = if (to.canonicalFile == store.root) tr("文稿") else to.name
            app.show(trf("已将 %d 项移到“%s”", done.size, where), tr("撤销")) { undoMove() }
        }
        selected.clear()
        selecting = false
        app.refreshLibrary()
        return done.size
    }

    /** Take the last move back (or do it again): everything returns to where, and to the name, it had. */
    private fun replay(source: ArrayList<List<Pair<File, File>>>, dest: ArrayList<List<Pair<File, File>>>): Boolean {
        if (mode != "docs" || source.isEmpty()) return false
        val back = ArrayList<Pair<File, File>>()
        for ((before, after) in source.removeAt(source.size - 1).reversed()) {
            if (!after.exists() || before.parentFile?.isDirectory != true) continue   // changed since by something else; leave it be
            try {
                val name = if (before.isDirectory || !before.name.endsWith(EXT)) before.name else before.name.removeSuffix(EXT)
                back.add(Pair(after, store.move(after, before.parentFile!!, if (after.isDirectory) before.name else name)))
            } catch (e: Exception) {
                app.alert = tr("操作失败") + "\n" + e.message
                break
            }
        }
        if (back.isNotEmpty()) dest.add(back)
        app.refreshLibrary()
        return true
    }

    fun undoMove() {
        if (replay(undo, redo)) app.show(tr("已撤销移动"))
    }

    fun redoMove() {
        if (replay(redo, undo)) app.show(tr("已重做移动"))
    }

    fun picked(): List<File> = selected.keys.toList()

    fun create(t: Template, title: String, fallback: String, board: Boolean, cover: Pair<String, DoubleArray>?) {
        try {
            val f = store.newNotebook(folder, t, board, title.replace("/", "-").ifBlank { fallback }, cover)
            app.open(f)
        } catch (e: Exception) {
            app.alert = tr("无法创建") + "\n" + e.message
        }
    }

    fun delete(e: Entry) {
        confirm = Triple(tr("永久删除？"), trf("“%s”将从这台设备上删除。", e.name)) { act { store.delete(e.file) } }
    }
}

@Composable
fun LibraryScreen(app: AppModel) {
    app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val lib = remember { LibraryState(app) }
    val p = palette
    app.libraryTick.intValue
    app.settingsTick.intValue
    androidx.activity.compose.BackHandler(enabled = lib.selecting || (lib.mode == "docs" && lib.folder != lib.store.root) || lib.query.isNotEmpty()) {
        when {
            lib.selecting -> {
                lib.selecting = false
                lib.selected.clear()
            }
            lib.query.isNotEmpty() -> lib.query = ""
            else -> lib.openFolder(lib.folder.parentFile!!.canonicalFile)
        }
    }
    // Focusable itself, and first: when a dialog closes the window gives focus to its first
    // focusable thing, which would otherwise be the search field (and up comes the keyboard).
    BoxWithConstraints(Modifier.fillMaxSize().background(p.viewBg).focusable()) {
        val compact = maxWidth < 700.dp
        Row(Modifier.fillMaxSize()) {
            if (!compact) Nav(lib, Modifier.width(250.dp).fillMaxHeight())
            Body(lib, compact)
        }
        DragGhost(lib)
    }
    lib.prompt?.let { (title, initial, done) ->
        Prompt(title, initial) {
            lib.prompt = null
            done(it)
        }
    }
    lib.confirm?.let { (title, message, done) ->
        Confirm(title, message, tr("确定"), danger = true) { ok ->
            lib.confirm = null
            if (ok) done()
        }
    }
    when (lib.newDialog) {
        "notebook" -> {
            val t = app.settings["template"].obj()
            PaperDialog(Template((t["w"] as Number).toDouble(), (t["h"] as Number).toDouble(), t["paper"] as String, t["color"].point()), false,
                tr("新建笔记本"), tr("创建笔记本"), withTitle = true) { tmpl, title, cover ->
                lib.newDialog = null
                if (tmpl != null) {
                    app.settings["template"] = linkedMapOf("w" to tmpl.w, "h" to tmpl.h, "paper" to tmpl.paper, "color" to tmpl.color.toList())
                    app.saveSettings()
                    lib.create(tmpl, title, tr("未命名笔记"), false, cover)
                }
            }
        }
        "board" -> PaperDialog(Template(1.0, 1.0, "dots", doubleArrayOf(1.0, 1.0, 1.0)), true, tr("新建白板"), tr("创建白板"), withTitle = true) { tmpl, title, _ ->
            lib.newDialog = null
            if (tmpl != null) lib.create(tmpl, title, tr("未命名白板"), true, null)
        }
    }
    lib.pickFolder?.let { (heading, moving, done) -> FolderPicker(lib, heading, moving) { f ->
        lib.pickFolder = null
        if (f != null) done(f)
    } }
    lib.choice?.let { (entry, what) -> FolderStyleDialog(lib, entry, what) { lib.choice = null } }
}

// -- the left column: the brand and the three places ---------------------------------------------------

@Composable
private fun Nav(lib: LibraryState, modifier: Modifier) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    Column(
        modifier
            .background(Brush.linearGradient(listOf(p.sidebarBg.copy(red = p.sidebarBg.red * 0.95f + p.accent.red * 0.05f, green = p.sidebarBg.green * 0.95f + p.accent.green * 0.05f, blue = p.sidebarBg.blue * 0.95f + p.accent.blue * 0.05f), p.sidebarBg)))
            .safeDrawingPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        BasicTextFieldLabel("Inkpad", Modifier.padding(start = 12.dp, top = 22.dp, bottom = 26.dp))
        for ((mode, icon, label) in listOf(Triple("docs", "documents", "文稿"), Triple("favs", "star", "收藏"), Triple("trash", "trash", "废纸篓"))) {
            val on = lib.mode == mode
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(if (on) p.ink(0.10f) else Color.Transparent)
                    .clickable {
                        // The documents again, when they are shown already: back to the top.
                        if (mode == "docs" && lib.mode == "docs") lib.openFolder(lib.store.root)
                        lib.mode = mode
                        lib.selecting = false
                        lib.selected.clear()
                        lib.app.refreshLibrary()
                    }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon)
                Label(tr(label), 17f, if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun BasicTextFieldLabel(text: String, modifier: Modifier) {
    androidx.compose.foundation.text.BasicText(text, modifier, style = TextStyle(fontSize = 27.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = palette.ink))
}

// -- the top bar: search, the cloud, settings ---------------------------------------------------------

@Composable
private fun TopBar(lib: LibraryState, compact: Boolean) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val app = lib.app
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (compact) {
            PopButton({ open ->
                Row(Modifier.clip(CircleShape).clickable(onClick = open).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label(tr(mapOf("docs" to "文稿", "favs" to "收藏", "trash" to "废纸篓", "deleted" to "已删除")[lib.mode]!!), 16f, FontWeight.SemiBold)
                    Icon("chevron", size = 14.dp)
                }
            }) { close ->
                MenuList(listOf(Triple("docs", "documents", "文稿"), Triple("favs", "star", "收藏"), Triple("trash", "trash", "废纸篓")).map { (m, ic, t) ->
                    MenuItem(tr(t), ic) {
                        if (m == "docs" && lib.mode == "docs") lib.openFolder(lib.store.root)
                        lib.mode = m
                        app.refreshLibrary()
                    }
                }, close)
            }
        }
        Box(Modifier.then(if (compact) Modifier.weight(1f) else Modifier.width(220.dp)).height(36.dp).background(p.ink(0.07f), CircleShape).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon("search", p.ink(0.5f), 16.dp)
                Box(Modifier.weight(1f)) {
                    if (lib.query.isEmpty()) Label(tr("搜索"), 15f, color = p.ink(0.45f))
                    BasicTextField(lib.query, { lib.query = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = p.ink), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (!compact) Spacer(Modifier.weight(1f))
        PopButton({ open ->
            Box(Modifier.size(40.dp).clip(CircleShape).background(if (app.syncing) p.accent.copy(alpha = 0.16f) else Color.Transparent).clickable(onClick = open), contentAlignment = Alignment.Center) {
                Icon(if (app.syncing) "syncing" else "cloud", if (app.syncing) p.accent else p.ink)
            }
        }) { close -> CloudMenu(app, close) }
        Box(Modifier.size(40.dp).clip(CircleShape).clickable { app.showSettings = true }, contentAlignment = Alignment.Center) { Icon("settings") }
    }
}

/** The cloud button's menu (B:window.py _cloud_popover). */
@Composable
fun CloudMenu(app: AppModel, close: () -> Unit) {
    app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val context = androidx.compose.ui.platform.LocalContext.current
    // As A:LibraryView CloudPanel: what it does, where it stands, two wide buttons.
    Column(Modifier.width(308.dp).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label("Google Drive", 18f, FontWeight.Bold)
        if (!app.connected) {
            Label(tr("把文稿库同步到你的 Google Drive 里的“Inkpad”文件夹。应用只能访问它自己创建的文件。"), 13f, color = p.ink(0.6f))
            DialogButton(tr("连接 Google Drive…"), suggested = true, modifier = Modifier.fillMaxWidth()) {
                close()
                if (io.github.inkpad.sync.GoogleAccount.client(context) == null) app.showSettings = true
                else io.github.inkpad.sync.Connect.start(app)
            }
        } else {
            val status = when {
                app.syncing -> tr("正在同步…")
                app.syncError != null -> tr("上次同步失败：") + app.syncError
                app.lastSync != null -> tr("上次同步：") + fmtDate(tr("%Y年%-m月%-d日 %H:%M"), (app.lastSync!! * 1000).toLong())
                else -> tr("已连接，尚未同步")
            }
            Label(status, 13f, color = p.ink(0.6f))
            if (app.syncing) {
                // The file being moved has two lines to itself, whatever its length, so that the
                // panel keeps its size.
                Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                    Label(app.syncText, 12f, color = p.ink(0.75f), maxLines = 2, align = androidx.compose.ui.text.style.TextAlign.Center)
                }
                val f by androidx.compose.animation.core.animateFloatAsState(app.syncFraction.toFloat().coerceIn(0f, 1f), label = "sync")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.ink(0.12f))) {
                    Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(p.accent))
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Label("${(app.syncFraction * 100).roundToInt()}%", 12f, color = p.ink(0.6f))
                }
            }
            Label(tr("回到文稿库时自动同步。删除的笔记会进入废纸篓，两边都改过的会逐笔合并。"), 13f, color = p.ink(0.6f))
            DialogButton(tr("立即同步"), suggested = true, enabled = !app.syncing, modifier = Modifier.fillMaxWidth()) {
                close()
                app.sync(quiet = false)
            }
            DialogButton(tr("断开连接"), modifier = Modifier.fillMaxWidth()) {
                close()
                Thread {
                    io.github.inkpad.sync.GoogleAccount.disconnect(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        app.connected = false
                        app.show(tr("已断开连接"))
                    }
                }.start()
            }
        }
    }
}

// -- the grid -----------------------------------------------------------------------------------------

@Composable
private fun Body(lib: LibraryState, compact: Boolean) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val app = lib.app
    val entries = lib.entries()
    val store = lib.store
    val inside = lib.mode == "docs" && lib.folder != store.root && lib.query.isBlank()
    // The notebooks scroll on under the bar at the top (search, title, buttons), which is frosted
    // glass over them, as the other editions' bars are.
    val glass = dev.chrisbanes.haze.rememberHazeState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    var headerPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val headerDp = with(density) { headerPx.toDp() }
    val side = if (compact) 10.dp else 26.dp
    val bottomInset = androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(glass).padding(horizontal = side)) {
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(top = headerDp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val q = lib.query.isNotBlank()
                    Label(if (q) tr("没有匹配的项目") else mapOf("docs" to tr("还没有笔记"), "favs" to tr("还没有收藏"), "trash" to tr("废纸篓是空的"), "deleted" to tr("没有已删除的项目"))[lib.mode]!!, 22f, FontWeight.Bold)
                    if (!q && lib.mode == "docs") Label(tr("点击“新建”创建笔记本，或导入一份 PDF。"), 15f, color = p.ink(0.6f), modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                // Row by row rather than a grid: the cells of a row are as tall as its tallest, and
                // each holds its picture and name in the middle, so a folder beside a notebook
                // does not sit at the top of a gap.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val min = if (compact) 150.dp else 186.dp
                    val gap = if (compact) 8.dp else 22.dp
                    val columns = maxOf(1, ((maxWidth + gap) / (min + gap)).toInt())
                    val rows = entries.chunked(columns)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = headerDp + 6.dp, bottom = bottomInset + 20.dp),
                        verticalArrangement = Arrangement.spacedBy(26.dp)) {
                        items(rows.size, key = { rows[it].first().file.path }) { r ->
                            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(gap)) {
                                for (e in rows[r]) Box(Modifier.weight(1f).fillMaxHeight()) { Cell(lib, e) }
                                repeat(columns - rows[r].size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .onSizeChanged { headerPx = it.height }
                .barGlass(glass)
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal)),
        ) {
            TopBar(lib, compact)
            Column(Modifier.padding(horizontal = side)) {
        if (inside) {
            // The path, every step of which can be gone to (and dropped on).
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).dropTarget(lib, "back", lib.folder.parentFile!!.canonicalFile).clickable { lib.openFolder(lib.folder.parentFile!!.canonicalFile) }, contentAlignment = Alignment.Center) { Icon("back") }
                var path = store.root
                val parts = store.rel(lib.folder.parentFile!!).split("/").filter { it != "." && it.isNotEmpty() }
                for ((n, name) in (listOf(tr("文稿")) + parts).withIndex()) {
                    if (n > 0) {
                        path = File(path, name)
                        Icon("chevron_right", p.ink(0.5f), 12.dp)
                    }
                    val target = path
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).dropTarget(lib, "crumb:" + target.path, target).clickable { lib.openFolder(target) }.padding(horizontal = 8.dp, vertical = 6.dp)) { Label(name, 14f) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.foundation.text.BasicText(lib.title(), Modifier.weight(1f), style = TextStyle(fontSize = if (compact) 26.sp else 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp, color = p.ink), maxLines = 1)
            if (lib.selecting) {
                Label(trf("已选 %d 项", lib.selected.size), 14f, color = p.ink(0.7f))
                SelectionActions(lib)
                Pillish(tr("完成")) {
                    lib.selecting = false
                    lib.selected.clear()
                }
            } else {
                if (lib.mode == "trash" && entries.isNotEmpty()) Pillish(tr("清空废纸篓"), danger = true) {
                    lib.confirm = Triple(tr("清空废纸篓？"), tr("废纸篓里的项目将从这台设备上删除。"), {
                        lib.act { for (e in store.trashed()) store.delete(e.file) }
                    })
                }
                Pillish(tr("选择")) { lib.selecting = true }
                PopButton({ open -> Pillish(if (app.settings["sort"] == "name") tr("名称") else tr("日期"), onClick = open) }) { close ->
                    MenuList(listOf(MenuItem(tr("日期")) {
                        app.settings["sort"] = "date"
                        app.saveSettings()
                    }, MenuItem(tr("名称")) {
                        app.settings["sort"] = "name"
                        app.saveSettings()
                    }), close)
                }
                if (lib.mode == "docs") PopButton({ open ->
                    Row(Modifier.clip(CircleShape).background(p.accent).clickable(onClick = open).padding(horizontal = 18.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon("plus", Color.White, 16.dp)
                        Label(tr("新建"), 15f, FontWeight.SemiBold, Color.White)
                    }
                }) { close ->
                    MenuList(listOf(
                        MenuItem(tr("笔记本"), "cover") { lib.newDialog = "notebook" },
                        MenuItem(tr("白板"), "board") { lib.newDialog = "board" },
                        null,
                        MenuItem(tr("导入 PDF 或 Goodnotes…"), "share") { Importer.importFiles(app, lib.folder) },
                        MenuItem(tr("导入整个文件夹…"), "documents") { Importer.importTree(app, lib.folder) },
                        MenuItem(tr("导入存档（.zip）…"), "documents") { Importer.importArchive(app, lib.folder) },
                        null,
                        MenuItem(tr("文件夹"), "plus") { lib.prompt = Triple(tr("新建文件夹"), tr("新文件夹")) { name -> if (name != null) lib.act { store.newFolder(lib.folder, name) } } },
                    ), close)
                }
            }
        }
            }
        }
    }
}

@Composable
private fun Pillish(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val p = palette
    Box(Modifier.clip(CircleShape).background(p.ink(0.07f)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)) {
        Label(text, 15f, color = if (danger) p.danger else p.ink)
    }
}

@Composable
private fun SelectionActions(lib: LibraryState) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val app = lib.app
    val store = lib.store
    val any = lib.selected.isNotEmpty()
    PopButton({ open -> Pillish(tr("操作"), onClick = open) }) { close ->
        val rows: List<MenuItem?> = when (lib.mode) {
            "trash" -> listOf(
                MenuItem(tr("恢复"), "restore", enabled = any) { lib.act { for (f in lib.picked()) store.restore(f) } },
                MenuItem(tr("永久删除"), "trash", danger = true, enabled = any) { lib.act { for (f in lib.picked()) store.delete(f) } },
            )
            "deleted" -> listOf(MenuItem(tr("恢复到文稿"), "restore", enabled = any) { lib.act { for (f in lib.picked()) store.unarchive(f) } })
            else -> listOf(
                MenuItem(tr("全选"), "check_circle") { for (e in lib.entries()) lib.selected[e.file] = true },
                null,
                MenuItem(tr("导出…"), "share", enabled = any) { io.github.inkpad.io.Exporter.exportItems(app, lib.picked()) },
                MenuItem(tr("复制"), "duplicate", enabled = any) { lib.act { for (f in lib.picked()) store.duplicate(f) } },
                MenuItem(tr("移动到…"), "move", enabled = any) {
                    val moving = lib.picked()
                    lib.pickFolder = Triple(trf("移动 %d 项到", moving.size), moving.filter { it.isDirectory }) { to -> lib.moveItems(moving, to) }
                },
                MenuItem(tr("移到废纸篓"), "trash", danger = true, enabled = any) {
                    lib.act { for (f in lib.picked()) store.trash(f) }
                    lib.selected.clear()
                },
            )
        }
        MenuList(rows, close)
    }
}

/** Something dragged items can be dropped on, to go into [folder]. */
private fun Modifier.dropTarget(lib: LibraryState, key: String, folder: File): Modifier = this
    .onGloballyPositioned { lib.targets[key] = Pair(it.boundsInRoot(), folder) }
    .then(if (lib.dropOn == key) Modifier.background(Color(0x473584E4), RoundedCornerShape(16.dp)).border(2.dp, Color(0xFF3584E4), RoundedCornerShape(16.dp)) else Modifier)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(lib: LibraryState, e: Entry) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val app = lib.app
    val store = lib.store
    val selected = lib.selected[e.file] == true || (!lib.selecting && lib.cursor == e.file)
    var origin by remember { mutableStateOf(Offset.Zero) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) p.accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(if (selected) 2.dp else 0.dp, if (selected) p.accent.copy(alpha = 0.75f) else Color.Transparent, RoundedCornerShape(16.dp))
            .then(if (e.isDir && lib.mode == "docs") Modifier.dropTarget(lib, "dir:" + e.file.path, e.file) else Modifier)
            .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
            .then(if (lib.mode == "docs") Modifier.pointerInput(e.file) {
                // As in a file manager: items are dragged onto a folder, or onto a step of the path, to be moved there.
                detectDragGesturesAfterLongPress(
                    onDragStart = { at ->
                        lib.dragging = if (lib.selecting && lib.selected[e.file] == true) lib.picked() else listOf(e.file)
                        lib.dragAt = origin + at
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        lib.dragAt += delta
                        lib.dropOn = lib.targets.entries.firstOrNull { (_, v) -> v.first.contains(lib.dragAt) && v.second !in lib.dragging }?.key
                    },
                    onDragEnd = {
                        val target = lib.dropOn?.let { lib.targets[it]?.second }
                        val moving = lib.dragging
                        lib.dragging = emptyList()
                        lib.dropOn = null
                        if (target != null) lib.moveItems(moving, target)
                    },
                    onDragCancel = {
                        lib.dragging = emptyList()
                        lib.dropOn = null
                    },
                )
            } else Modifier)
            .combinedClickable(onClick = { lib.activate(e) })
            .fillMaxHeight()
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        // A notebook's cover may be any shape up to 150 x 200; a folder is only as tall as its art,
        // so a row of folders does not stand in empty space.
        Box(Modifier.then(if (e.isDir) Modifier else Modifier.height(200.dp)).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box {
                if (e.isDir) {
                    val (color, icon) = store.folderStyle(e.file)
                    FolderArt(color, icon)
                } else {
                    CoverView(e.file)
                }
                if (lib.mode == "docs" || lib.mode == "favs") {
                    val fav = store.isFavorite(e.file)
                    Box(Modifier.align(Alignment.TopEnd).padding(top = if (e.isDir) 28.dp else 4.dp, end = 4.dp).size(30.dp).clip(CircleShape)
                        .background(p.viewBg.copy(alpha = 0.7f)).clickable { lib.act { store.toggleFavorite(e.file) } }, contentAlignment = Alignment.Center) {
                        Icon(if (fav) "star_filled" else "star", if (fav) p.starOn else p.ink(0.38f), 18.dp)
                    }
                }
            }
        }
        PopButton({ open ->
            // The name on up to three lines, in the middle: a long name is read, not cut at one line.
            Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = open).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(e.name, 15f, maxLines = 3, modifier = Modifier.weight(1f, fill = false), align = androidx.compose.ui.text.style.TextAlign.Center)
                Icon("chevron", size = 14.dp)
            }
        }) { close -> MenuList(cellMenu(lib, e), close) }
        Label(fmtDate(tr("%Y年%-m月%-d日 %H:%M"), e.mtime), 12f, color = p.ink(0.55f))
    }
}

private fun cellMenu(lib: LibraryState, e: Entry): List<MenuItem?> {
    val store = lib.store
    val app = lib.app
    if (lib.mode == "deleted") return listOf(MenuItem(tr("恢复到文稿"), "restore") { lib.act { store.unarchive(e.file) } })
    if (lib.mode == "trash") return listOf(
        MenuItem(tr("恢复"), "restore") { lib.act { store.restore(e.file) } },
        MenuItem(tr("永久删除"), "trash", danger = true) { lib.delete(e) },
    )
    val rows = mutableListOf<MenuItem?>(MenuItem(tr("重命名"), "rename") {
        lib.prompt = Triple(tr("重命名"), e.name) { name -> if (name != null) lib.act { store.rename(e.file, name) } }
    })
    if (e.isDir) {
        rows.add(MenuItem(tr("自定义颜色"), "palette") { lib.choice = Pair(e, "color") })
        rows.add(MenuItem(tr("设置图标"), "smile") { lib.choice = Pair(e, "icon") })
        rows.add(MenuItem(tr("导出文件夹…"), "share") { io.github.inkpad.io.Exporter.exportFolder(app, e.file) })
    } else {
        rows.add(MenuItem(tr("复制"), "duplicate") { lib.act { store.duplicate(e.file) } })
    }
    val fav = store.isFavorite(e.file)
    rows.addAll(listOf(
        null,
        MenuItem(tr("移动到…"), "move") {
            lib.pickFolder = Triple(trf("移动“%s”到", e.name), if (e.isDir) listOf(e.file) else emptyList()) { to -> lib.moveItems(listOf(e.file), to) }
        },
        MenuItem(if (fav) tr("取消收藏") else tr("收藏"), "star") { lib.act { store.toggleFavorite(e.file) } },
        null,
        MenuItem(tr("移到废纸篓"), "trash", danger = true) { lib.act { store.trash(e.file) } },
    ))
    return rows
}

/** What is being dragged, following the finger. */
@Composable
private fun DragGhost(lib: LibraryState) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    if (lib.dragging.isEmpty()) return
    val d = LocalDensity.current.density
    val f = lib.dragging.first()
    Box(Modifier.offset { IntOffset((lib.dragAt.x - 30 * d).roundToInt(), (lib.dragAt.y - 40 * d).roundToInt()) }.size(60.dp, 80.dp).shadow(10.dp, RoundedCornerShape(6.dp))) {
        if (f.isDirectory) FolderArt(lib.store.folderStyle(f).first, lib.store.folderStyle(f).second, 60, 47)
        else Covers.cached(f)?.let { Image(it, null, Modifier.fillMaxSize()) }
        if (lib.dragging.size > 1) Box(Modifier.align(Alignment.TopEnd).size(22.dp).background(palette.accent, CircleShape), contentAlignment = Alignment.Center) {
            Label("${lib.dragging.size}", 12f, FontWeight.Bold, Color.White)
        }
    }
}

/** A notebook's cover, 150 × 200 at most, read from its file in the background. */
@Composable
private fun CoverView(f: File) {
    var img by remember(f, f.lastModified()) { mutableStateOf(Covers.cached(f)) }
    LaunchedEffect(f, f.lastModified()) { if (img == null) img = withContext(Dispatchers.IO) { Covers.load(f) } }
    val i = img
    val w: Int
    val h: Int
    if (i != null) {
        val k = min(150.0 / i.width, 200.0 / i.height)
        w = (i.width * k).roundToInt()
        h = (i.height * k).roundToInt()
    } else {
        w = 150
        h = 200
    }
    Box(Modifier.size(w.dp, h.dp).shadow(8.dp, RoundedCornerShape(5.dp)).background(Color.White, RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))) {
        if (i != null) Image(i, null, Modifier.fillMaxSize())
    }
}

/** A soft two-tone folder in the given colour, with an optional icon on the front (B:library.py folder_texture). */
@Composable
fun FolderArt(color: DoubleArray, icon: String, w: Int = 150, h: Int = 118) {
    val front = Color(argb(color))
    val back = Color(argb(DoubleArray(3) { color[it] * 0.84 }))
    val light = Color(argb(DoubleArray(3) { min(1.0, color[it] + 0.07) }))
    Box(Modifier.size(w.dp, h.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / w
            val W = w * k
            val H = h * k
            val r = 11f * k
            val tabW = W * 0.40f
            val tabH = H * 0.13f
            val top = H * 0.20f
            val backPath = Path().apply {
                moveTo(0f, H - r)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(tabW - 6 * k, 0f)
                cubicTo(tabW + 2 * k, 0f, tabW + 4 * k, tabH, tabW + 14 * k, tabH)
                lineTo(W - r, tabH)
                arcTo(Rect(W - 2 * r, tabH, W, tabH + 2 * r), 270f, 90f, false)
                lineTo(W, H - r)
                arcTo(Rect(W - 2 * r, H - 2 * r, W, H), 0f, 90f, false)
                arcTo(Rect(0f, H - 2 * r, 2 * r, H), 90f, 90f, false)
                close()
            }
            drawPath(backPath, back)
            drawRoundRect(Brush.verticalGradient(listOf(light, front), top, H), Offset(0f, top), Size(W, H - top), CornerRadius(r, r))
            drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(0f, top), Size(W, H - top), CornerRadius(r, r), style = Stroke(1f * k))
        }
        if (icon.isNotEmpty() && Icons[icon] != null) {
            val size = h * 0.36f
            Box(Modifier.offset(((w - size) / 2).dp, (h * 0.20f + (h * 0.80f - size) / 2).dp)) {
                Icon(icon, Color(argb(DoubleArray(3) { color[it] * 0.42 }, 0.78)), size.dp)
            }
        }
    }
}

/** The colours or the icons a folder can have. */
@Composable
private fun FolderStyleDialog(lib: LibraryState, e: Entry, what: String, dismiss: () -> Unit) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val color = what == "color"
    val names = if (color) emptyList() else listOf("") + Icons.glyphNames
    val count = if (color) FOLDER_COLORS.size else names.size
    val columns = if (color) 4 else 5
    // As A:LibraryView ChoiceDialog: the heading in the middle, a grid of 48 dp cells, Cancel
    // across the bottom.
    DialogCard(dismiss, width = if (color) 280 else 320) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Label(if (color) tr("文件夹颜色") else tr("文件夹图标"), 19f, FontWeight.Bold)
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (row in (0 until count).chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (k in row) Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(p.ink(0.05f)).clickable {
                        lib.act { if (color) lib.store.setFolderStyle(e.file, color = FOLDER_COLORS[k]) else lib.store.setFolderStyle(e.file, icon = names[k]) }
                        dismiss()
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (color) Box(Modifier.size(30.dp).clip(CircleShape).background(Color(io.github.inkpad.render.argb(FOLDER_COLORS[k]))).border(1.dp, p.ink(0.15f), CircleShape))
                    else Icon(if (names[k].isEmpty()) "none" else names[k], size = 22.dp)
                }
                repeat(columns - row.size) { Spacer(Modifier.size(48.dp)) }
            }
        }
        DialogButton(tr("取消"), modifier = Modifier.fillMaxWidth(), onClick = dismiss)
    }
}

/** "Move to…": a tree of folders that opens a level at a time, nothing chosen at first, and never
 *  offering a folder that is being moved (or anything inside it). */
@Composable
private fun FolderPicker(lib: LibraryState, heading: String, moving: List<File>, done: (File?) -> Unit) {
    lib.app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val store = lib.store
    val open = remember { mutableStateMapOf<File, Boolean>() }
    var chosen by remember { mutableStateOf<File?>(null) }
    DialogCard({ done(null) }) {
        Label(heading, 17f, FontWeight.Bold)
        Column(Modifier.height(360.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            @Composable
            fun node(f: File, depth: Int) {
                if (moving.any { f.canonicalPath == it.canonicalPath || f.canonicalPath.startsWith(it.canonicalPath + "/") }) return
                val subs = store.entries(f).filter { it.isDir }.sortedBy { it.name.lowercase() }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (chosen == f) p.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { chosen = f }.padding(start = (12 + depth * 18).dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (subs.isNotEmpty()) Box(Modifier.size(24.dp).clickable { if (open.remove(f) == null) open[f] = true }, contentAlignment = Alignment.Center) {
                        Icon(if (open[f] == true) "chevron" else "chevron_right", size = 14.dp)
                    } else Spacer(Modifier.width(24.dp))
                    Icon("documents", size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Label(if (f == store.root) tr("文稿") else f.name)
                }
                if (open[f] == true || (f == store.root)) for (s in subs) node(s.file, depth + 1)
            }
            node(store.root, 0)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(tr("取消")) { done(null) }
            Spacer(Modifier.width(8.dp))
            DialogButton(tr("移动"), suggested = true, enabled = chosen != null) { done(chosen) }
        }
    }
}
