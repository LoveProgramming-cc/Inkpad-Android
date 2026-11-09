package io.github.inkpad.io

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import io.github.inkpad.AppModel
import io.github.inkpad.Files
import io.github.inkpad.core.Document
import io.github.inkpad.core.Goodnotes
import io.github.inkpad.core.Page
import io.github.inkpad.core.PdfData
import io.github.inkpad.core.ZipWriter
import io.github.inkpad.core.pageBox
import io.github.inkpad.core.source
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.editor.EditorState
import io.github.inkpad.editor.ExportRequest
import io.github.inkpad.editor.selectionImage
import io.github.inkpad.pdf.PdfPages
import io.github.inkpad.render.Pictures
import io.github.inkpad.render.pageBitmap
import io.github.inkpad.render.renderPage
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

/** Work that takes a while, off the main thread; [done] runs on it afterwards. */
fun background(app: AppModel, failure: String, work: () -> Unit, done: () -> Unit = {}) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        try {
            work()
            main.post(done)
        } catch (e: Throwable) {
            main.post { app.alert = failure + "\n" + (e.message ?: e.toString()) }
        }
    }.apply { isDaemon = true }.start()
}

// The editor's export menu, and the library's archives (B:window.py _export_*; W:src/io/exporter.js).
object Exporter {
    /** The PDF page sizes are kept below this, as the desktop app keeps them. */
    private const val MAX_SIDE = 14400.0

    /**
     * A vector PDF of the pages. The ink and paper are drawn by the same code that draws the screen,
     * into Android's PdfDocument (which writes paths and text as such); pages with a PDF behind them
     * then get the original page put underneath as itself, so its text stays text.
     */
    fun buildPdf(context: Context, doc: Document, pages: List<Page>, paper: Boolean, ink: Boolean, dest: File) {
        val inkOnly = File(dest.path + ".ink")
        val out = PdfDocument()
        val scales = ArrayList<Double>()
        pages.forEachIndexed { n, page ->
            val (x0, y0, x1, y1) = pageBox(page)
            val k = min(1.0, MAX_SIDE / max(x1 - x0, y1 - y0))
            scales.add(k)
            val w = max(1, Math.round((x1 - x0) * k).toInt())
            val h = max(1, Math.round((y1 - y0) * k).toInt())
            val p = out.startPage(PdfDocument.PageInfo.Builder(w, h, n + 1).create())
            val c = p.canvas
            c.scale(k.toFloat(), k.toFloat())
            renderPage(c, page, x0, y0, 1e-3, printing = true, paper = paper && page.pdf == null, ink = ink)
            out.finishPage(p)
        }
        inkOnly.outputStream().use { out.writeTo(it) }
        out.close()
        if (!paper || pages.none { it.pdf != null }) {
            inkOnly.renameTo(dest)
            return
        }
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(inkOnly).use { target ->
            val layers = LayerUtility(target)
            val sources = IdentityHashMap<PdfData, PDDocument>()
            try {
                pages.forEachIndexed { n, page ->
                    val pdf = page.pdf ?: return@forEachIndexed
                    val src = sources.getOrPut(pdf) { PDDocument.load(PdfPages.open(pdf).file) }
                    if (page.pdfPage >= src.numberOfPages) return@forEachIndexed
                    // PDFBox would squeeze a page with a /Rotate of its own into the unturned box; the
                    // form is taken unturned and all the turning is done here.
                    val srcPage = src.getPage(page.pdfPage)
                    val own = ((srcPage.rotation / 90) % 4 + 4) % 4
                    srcPage.rotation = 0
                    val form = layers.importPageAsForm(src, page.pdfPage)
                    val box = srcPage.cropBox ?: srcPage.mediaBox
                    val kk = scales[n].toFloat()
                    // The PDF's own quarter turns and the page's: (u, v) of the unturned PDF page, origin
                    // bottom left, to the output page, whose height is H.
                    val rot = (own + ((page.rot % 4) + 4) % 4) % 4
                    val w0 = box.width
                    val h0 = box.height
                    val H = (page.h * kk).toFloat()
                    val m = when (rot) {
                        1 -> Matrix(0f, -kk, kk, 0f, 0f, H)
                        2 -> Matrix(-kk, 0f, 0f, -kk, w0 * kk, H)
                        3 -> Matrix(0f, kk, -kk, 0f, h0 * kk, 0f)
                        else -> Matrix(kk, 0f, 0f, kk, 0f, 0f)
                    }
                    val tp = target.getPage(n)
                    PDPageContentStream(target, tp, PDPageContentStream.AppendMode.PREPEND, true, true).use { cs ->
                        cs.saveGraphicsState()
                        cs.transform(m)
                        cs.drawForm(form)
                        cs.restoreGraphicsState()
                    }
                }
                target.save(dest)
            } finally {
                for (d in sources.values) d.close()
            }
        }
        inkOnly.delete()
    }

    private fun ask(ed: EditorState, heading: String, range: Boolean, then: (List<Int>?, Boolean, Boolean) -> Unit) {
        ed.exportAsk = ExportRequest(heading, range, then)
    }

    fun exportPdf(ed: EditorState) = ask(ed, tr("导出为 PDF"), true) { indexes, paper, ink ->
        ed.session.save(wait = true)
        val doc = ed.doc
        val pages = indexes?.map { doc.pages[it] } ?: ArrayList(doc.pages)
        val name = doc.displayName() + ".pdf"
        val file = File(Files.outbox(), name)
        ed.app.show(tr("正在导出…"))
        background(ed.app, tr("导出失败"), { buildPdf(ed.app.context, doc, pages, paper, ink, file) }) { save(ed.app, file, name, "application/pdf") }
    }

    /** A page as PNG, three times its size in points (B: export_png). */
    fun pagePng(page: Page, paper: Boolean, ink: Boolean): ByteArray {
        val bmp = pageBitmap(page, 3.0, paper = paper, ink = ink, background = if (paper) PdfPages.background(page) else null)
        return Pictures.encode(bmp)
    }

    fun exportPng(ed: EditorState) = ask(ed, tr("导出当前页为图片"), false) { _, paper, ink ->
        val i = ed.canvas.currentPageIndex()
        val name = "${ed.doc.displayName()}-${i + 1}.png"
        val file = File(Files.outbox(), name)
        background(ed.app, tr("导出失败"), { file.writeBytes(pagePng(ed.doc.pages[i], paper, ink)) }) { save(ed.app, file, name, "image/png") }
    }

    fun exportPngAll(ed: EditorState) = ask(ed, tr("导出全部页面为图片…").removeSuffix("…"), true) { indexes, paper, ink ->
        val doc = ed.doc
        val list = indexes ?: doc.pages.indices.toList()
        val name = doc.displayName()
        val file = File(Files.outbox(), "$name.zip")
        ed.app.show(tr("正在导出…"))
        background(ed.app, tr("导出失败"), {
            // The pictures come as one zip: a folder to write into would mean asking for one.
            file.outputStream().use { o ->
                val z = ZipWriter(o)
                for (n in list) z.add("$name-${(n + 1).toString().padStart(2, '0')}.png", pagePng(doc.pages[n], paper, ink), compress = false)
                z.finish()
            }
        }) { save(ed.app, file, "$name.zip", "application/zip", trf("已导出 %d 张图片", list.size)) }
    }

    fun exportGoodnotes(ed: EditorState) {
        ed.session.save(wait = true)
        val doc = ed.doc
        val name = doc.displayName() + ".goodnotes"
        val file = File(Files.outbox(), name)
        background(ed.app, tr("导出失败"), {
            // The pages' backgrounds go into Goodnotes as one PDF, without the ink.
            val bg = File(file.path + ".bg.pdf")
            buildPdf(ed.app.context, doc, doc.pages, paper = true, ink = false, dest = bg)
            file.outputStream().use { Goodnotes.save(doc, doc.displayName(), bg.source(), it) }
            bg.delete()
        }) { save(ed.app, file, name, "application/octet-stream") }
    }

    /** The notebook as the .inkpad file it is, to other apps. */
    fun shareInkpad(ed: EditorState) {
        ed.session.save(wait = true)
        val f = File(Files.outbox(), ed.session.file.name)
        ed.session.file.copyTo(f, overwrite = true)
        Files.share(f, "application/octet-stream")
    }

    /** The selected area as a picture on the clipboard. */
    fun selectionToClipboard(ed: EditorState) {
        val bmp = ed.canvas.selectionImage(2.0) ?: return
        val f = File(Files.outbox(), "selection.png")
        f.writeBytes(Pictures.encode(bmp))
        val c = ed.app.context
        val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
        val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(c.contentResolver, "Inkpad", uri))
        ed.app.show(tr("截图已拷贝到剪贴板"))
    }

    fun saveSelection(ed: EditorState) {
        val bmp: Bitmap = ed.canvas.selectionImage(3.0) ?: return
        val f = File(Files.outbox(), "selection.png")
        f.writeBytes(Pictures.encode(bmp))
        save(ed.app, f, "selection.png", "image/png")
    }

    /** Several notebooks and folders as one archive. */
    fun exportItems(app: AppModel, files: List<File>) {
        val name = if (files.size == 1) files[0].nameWithoutExtension else "Inkpad"
        val out = File(Files.outbox(), "$name.zip")
        background(app, tr("导出失败"), { app.store.exportPaths(files, out) }) { save(app, out, "$name.zip", "application/zip") }
    }

    fun exportFolder(app: AppModel, folder: File) {
        val out = File(Files.outbox(), folder.name + ".zip")
        background(app, tr("导出失败"), { app.store.exportArchive(folder, out) }) { save(app, out, folder.name + ".zip", "application/zip") }
    }

    /** The whole library, for keeping a copy. */
    fun exportLibrary(app: AppModel) {
        val out = File(Files.outbox(), "Inkpad.zip")
        background(app, tr("导出失败"), { app.store.exportArchive(app.store.root, out) }) { save(app, out, "Inkpad.zip", "application/zip") }
    }

    /** Ask where to keep a file that was made, and write it there. */
    fun save(app: AppModel, file: File, name: String, mime: String, done: String? = null) {
        Files.create(name, mime) { uri ->
            if (uri == null) return@create
            background(app, tr("导出失败"), { Files.write(uri, file) }) { app.show(done ?: (tr("已导出到 ") + name)) }
        }
    }
}
