package io.github.inkpad.core

import kotlin.math.max

// Pages (B:model.py Page, page_box) and the fixed lists of paper sizes, colours and kinds.

val A4 = Pair(595.0, 842.0)

val SIZES = listOf("A4" to Pair(595.0, 842.0), "A5" to Pair(420.0, 595.0), "A3" to Pair(842.0, 1191.0),
    "B5" to Pair(499.0, 709.0), "Letter" to Pair(612.0, 792.0))
val PAPER_COLORS = listOf("白色纸张" to rgb(1.0, 1.0, 1.0), "黄色纸张" to rgb(0.976, 0.965, 0.902), "深色纸张" to rgb(0.13, 0.13, 0.15))
val COVER_STYLES = listOf("simple", "label", "ribbon", "band", "grid")
val COVER_COLORS = listOf(rgb(0.69, 0.61, 0.50), rgb(0.13, 0.15, 0.27), rgb(0.17, 0.27, 0.45), rgb(0.24, 0.46, 0.38),
    rgb(0.70, 0.26, 0.26), rgb(0.33, 0.34, 0.37), rgb(0.93, 0.75, 0.30), rgb(0.89, 0.89, 0.88))
val PAPERS = listOf(
    "基本样式" to listOf("blank" to "空白", "dots" to "点阵纸", "ruled" to "窄行距", "wide" to "宽行距", "grid" to "方格纸"),
    "书写用纸" to listOf("cornell" to "康奈尔", "legal" to "拍纸簿", "col2" to "双栏", "col3" to "三栏"),
    "日程与计划" to listOf("todo" to "待办清单", "weekly" to "周计划"),
    "音乐与棋类" to listOf("staff" to "五线谱", "go" to "围棋棋谱"),
)

/** A whiteboard has no edges: its items may lie anywhere. This size is only nominal; it gives a
 *  new board's first strokes coordinates of a comfortable magnitude. */
val BOARD_SIZE = Pair(1e8, 1e8)

/** An embedded PDF whose pages serve as backgrounds. Opening and drawing it is the app's work. */
class PdfData(val source: Source, key: String? = null) {
    /** size/crc32: what two copies of the same PDF have in common, for finding them quickly.
     *  Whether they are the same is settled by comparing their content. */
    val key: String = key ?: "${source.size}/${Hash.crc32(source)}"
    private var sha: String? = null
    fun sha256(): String = sha ?: Hash.sha256(source).also { sha = it }
    fun sameAs(other: PdfData) = this === other || (key == other.key && sha256() == other.sha256())

    /** Set by the app: the opened document, kept with the data. */
    @Volatile var handle: Any? = null
}

/** The settings a page is made with, and what "change template" swaps. */
data class Template(val w: Double, val h: Double, val paper: String, val color: Color)

class Page(
    var w: Double = A4.first,
    var h: Double = A4.second,
    var paper: String = "blank",
    var pdf: PdfData? = null,
    var pdfPage: Int = 0,
    color: Color = rgb(1.0, 1.0, 1.0),
    var board: Boolean = false,  // a whiteboard: one very large page, shown and exported cropped to its content
) {
    var color: Color = color.copyOf()
    var id: String = Ids.short()
    var rev = 0                  // counts changes to the items, so an unchanged page is not serialised again
    var gone = HashSet<String>() // ids of items removed from this page, kept so that a sync can tell
    internal var blob: Pair<Int, String>? = null   // (rev, serialised items)
    var rot = 0                  // quarter turns applied to the PDF background
    var bookmark = false
    var outline: String? = null  // outline title, if the page is in the outline
    var items = ArrayList<Item>()
    var mt = 0.0                 // when the settings above were last changed, for merging two versions
    internal var sign: String? = null   // ...and what they were when that was last looked at

    fun template() = Template(w, h, paper, color)

    fun applyTemplate(t: Template) {
        w = t.w; h = t.h; paper = t.paper; color = t.color.copyOf()
    }

    fun blankLike() = Page(w, h, paper, color = color, board = board)

    fun clone() = Page(w, h, paper, pdf, pdfPage, color, board).also { page ->
        page.rot = rot
        page.items = ArrayList(items.map { it.transformed(1.0, 0.0, 0.0, 0.0, 0.0) })
    }

    /** A copy turned k quarter turns clockwise, content included. */
    fun rotated(k: Int): Page {
        val (nw, nh) = if (k % 2 != 0) Pair(h, w) else Pair(w, h)
        return Page(nw, nh, paper, pdf, pdfPage, color, board).also { page ->
            page.rot = (rot + k) % 4
            page.bookmark = bookmark
            page.outline = outline
            page.items = ArrayList(items.map { it.rotated(k, w, h) })
        }
    }

    /** What is kept in document.json about the page apart from its items, as one comparable string. */
    internal fun settingsSign(): String = JsonOut.write(listOf(w, h, paper, color.toList(), board, pdf?.key, pdfPage, rot, bookmark, outline))

    internal fun takeSettings(o: Page) {
        w = o.w; h = o.h; paper = o.paper; color = o.color.copyOf(); board = o.board
        pdf = o.pdf; pdfPage = o.pdfPage; rot = o.rot; bookmark = o.bookmark; outline = o.outline
    }
}

/** The part of a page worth showing: all of it, or a whiteboard's content. */
fun pageBox(page: Page): Box {
    if (!page.board) return doubleArrayOf(0.0, 0.0, page.w, page.h)
    var box: Box? = null
    for (it in page.items) box = Geom.union(box, it.bbox)
    if (box == null) return doubleArrayOf(page.w / 2 - 400, page.h / 2 - 300, page.w / 2 + 400, page.h / 2 + 300)
    // Pad, and keep a sensible minimum size and shape.
    val cx = (box[0] + box[2]) / 2
    val cy = (box[1] + box[3]) / 2
    val w = max(box[2] - box[0] + 80, 400.0)
    val h = max(box[3] - box[1] + 80, 300.0)
    return doubleArrayOf(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
}
