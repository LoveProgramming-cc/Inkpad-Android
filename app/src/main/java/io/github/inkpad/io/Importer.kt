package io.github.inkpad.io

import android.net.Uri
import android.provider.DocumentsContract
import io.github.inkpad.AppModel
import io.github.inkpad.Files
import io.github.inkpad.core.Document
import io.github.inkpad.core.EXT
import io.github.inkpad.core.Goodnotes
import io.github.inkpad.core.Image
import io.github.inkpad.core.ImageData
import io.github.inkpad.core.Ids
import io.github.inkpad.core.Store
import io.github.inkpad.core.setNotebookId
import io.github.inkpad.core.source
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.editor.EditorState
import io.github.inkpad.editor.insertImage
import io.github.inkpad.pdf.PdfPages
import io.github.inkpad.render.Pictures
import java.io.File
import kotlin.math.min

// Bringing things in: PDFs, Goodnotes documents, notebooks, folders of them, archives, pictures.

object Importer {
    private val docTypes = arrayOf("application/pdf", "application/octet-stream", "application/zip", "*/*")

    /** A PDF as a new notebook. */
    fun pdfNotebook(file: File): Document {
        val pdf = PdfPages.read(file)
        return Document.fromPdf(pdf, PdfPages.sizes(pdf))
    }

    fun goodnotesSummary(s: Goodnotes.Stats): String {
        var text = trf("已导入 %d 页、%d 笔", s.pages, s.strokes)
        if (s.skipped > 0) text += trf("，%d 项无法导入", s.skipped)
        return text
    }

    /** Import one file into a folder; returns the new notebook and what to say about it. */
    fun importFile(app: AppModel, file: File, folder: File, name: String = file.name): Pair<File, String?> {
        val lower = name.lowercase()
        val stem = name.substringBeforeLast('.').replace("/", "-")
        return when {
            lower.endsWith(".pdf") -> Pair(app.store.addDocument(folder, stem, pdfNotebook(file)), null)
            lower.endsWith(".goodnotes") -> {
                val (doc, title, stats) = Goodnotes.load(file, ::pdfSizes)
                Pair(app.store.addDocument(folder, title.ifBlank { stem }, doc), goodnotesSummary(stats))
            }
            else -> {
                // A notebook from elsewhere is a notebook of its own here (it may be a copy of one in the library).
                Document.load(file)   // is it one?
                Pair(app.store.importNotebook(file, folder, stem), null)
            }
        }
    }

    private fun pdfSizes(data: io.github.inkpad.core.Source): List<Pair<Double, Double>> = PdfPages.sizes(PdfPages.read(data))

    fun importFiles(app: AppModel, folder: File) = Files.open(docTypes, multiple = true) { uris ->
        if (uris.isEmpty()) return@open
        app.show(tr("正在导入…"))
        val notes = ArrayList<String>()
        var last: File? = null
        background(app, tr("无法导入"), {
            for (uri in uris) {
                val name = Files.name(uri)
                val f = Files.copyIn(uri)
                val (made, note) = importFile(app, f, folder, name)
                last = made
                note?.let { notes.add(it) }
                f.delete()
            }
        }) {
            app.refreshLibrary()
            if (uris.size == 1) last?.let { app.open(it) }
            app.show(notes.firstOrNull() ?: trf("已导入 %d 项", uris.size))
        }
    }

    /** A file handed to the app from outside: a notebook, a PDF or a Goodnotes document. */
    fun openExternal(app: AppModel, uri: Uri) {
        if (app.session != null) app.closeEditor()
        background(app, tr("无法打开文件"), {
            val name = Files.name(uri)
            val f = Files.copyIn(uri)
            val (made, note) = importFile(app, f, app.store.root, name)
            f.delete()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                app.refreshLibrary()
                app.open(made)
                note?.let { app.show(it) }
            }
        })
    }

    /** Every Goodnotes document, PDF and notebook under a folder, keeping its folder structure. */
    fun importTree(app: AppModel, folder: File) = Files.tree { tree ->
        if (tree == null) return@tree
        val resolver = app.context.contentResolver
        val found = ArrayList<Pair<Uri, String>>()
        fun walk(docId: String, rel: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                val rows = ArrayList<Triple<String, String, String>>()
                while (c.moveToNext()) rows.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
                for ((id, name, mime) in rows.sortedBy { it.second }) {
                    if (name.startsWith(".")) continue
                    val path = if (rel.isEmpty()) name else "$rel/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, path)
                    else if (listOf(".goodnotes", ".pdf", EXT).any { name.lowercase().endsWith(it) }) found.add(Pair(DocumentsContract.buildDocumentUriUsingTree(tree, id), path))
                }
            }
        }
        val failed = ArrayList<String>()
        var done = 0
        app.show(tr("正在导入…"))
        background(app, tr("无法导入"), {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            walk(rootId, "")
            val top = Store.unique(folder, Files.name(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)).ifBlank { tr("导入") })
            for ((uri, rel) in found) {
                val target = rel.split("/").dropLast(1).fold(top) { f, p -> File(f, p) }
                target.mkdirs()
                try {
                    val f = Files.copyIn(uri)
                    importFile(app, f, target, rel.substringAfterLast('/'))
                    f.delete()
                    done++
                } catch (e: Exception) {
                    failed.add(rel + "：" + (e.message ?: e.toString()))
                }
            }
        }) {
            app.refreshLibrary()
            if (failed.isEmpty()) app.show(trf("已导入 %d 项", done))
            else app.alert = trf("已导入 %d 项，%d 项失败：", done, failed.size) + "\n" + failed.take(10).joinToString("\n")
        }
    }

    fun importArchive(app: AppModel, folder: File) = Files.open(arrayOf("application/zip", "*/*")) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        var n = 0
        background(app, tr("无法导入"), {
            val f = Files.copyIn(uri)
            n = app.store.importArchive(f, folder)
            f.delete()
        }) {
            app.refreshLibrary()
            app.show(trf("已导入 %d 本笔记", n))
        }
    }

    /** Pages of a PDF, added to the open notebook where new pages go. */
    fun importPdfPages(ed: EditorState) = Files.open(arrayOf("application/pdf")) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        var pages: List<io.github.inkpad.core.Page> = emptyList()
        background(ed.app, tr("无法导入"), {
            val f = Files.copyIn(uri)
            val pdf = PdfPages.read(f)
            pages = Document.pdfPages(pdf, PdfPages.sizes(pdf))
        }) { ed.addPages(pages) }
    }

    private fun pickPicture(app: AppModel, then: (ByteArray, Int, Int) -> Unit) = Files.open(arrayOf("image/*")) { uris ->
        val uri = uris.firstOrNull() ?: return@open
        var png: Triple<ByteArray, Int, Int>? = null
        background(app, tr("无法插入图片"), {
            val bytes = app.context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            png = Pictures.convert(bytes)
        }) { png?.let { (b, w, h) -> then(b, w, h) } }
    }

    fun insertPicture(ed: EditorState) = pickPicture(ed.app) { png, w, h ->
        ed.canvas.insertImage(png, w, h)
        ed.tool = "lasso"
    }

    /** A picture as a page of its own. */
    fun addPicturePage(ed: EditorState) = pickPicture(ed.app) { png, w, h ->
        val page = ed.currentPage().blankLike()
        val box = if (page.board) doubleArrayOf(page.w / 2 - 300, page.h / 2 - 220, page.w / 2 + 300, page.h / 2 + 220) else doubleArrayOf(0.0, 0.0, page.w, page.h)
        val k = minOf((box[2] - box[0]) * 0.9 / w, (box[3] - box[1]) * 0.9 / h, 1.5)
        page.items.add(Image((box[0] + box[2] - w * k) / 2, (box[1] + box[3] - h * k) / 2, w * k, h * k, ImageData(png.source())))
        ed.addPages(listOf(page))
    }
}
