package io.github.inkpad.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.inkpad.core.Page
import io.github.inkpad.core.PdfData
import io.github.inkpad.core.Source
import io.github.inkpad.core.source
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PDFs behind pages, through the system's PdfRenderer (B:pdf.py, poppler there). A notebook's PDF
 * is a part of its zip; the renderer wants a file, so each PDF is written once to the cache, named
 * by its size and checksum. A renderer draws one page at a time, so every use is synchronized on
 * it.
 */
object PdfPages {
    @Volatile var cacheDir: File? = null

    class Handle(val file: File) {
        val fd: ParcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        val count get() = renderer.pageCount
    }

    private fun fileFor(pdf: PdfData): File {
        val dir = cacheDir ?: File(System.getProperty("java.io.tmpdir"), "pdf")
        dir.mkdirs()
        val f = File(dir, pdf.key.replace('/', '-') + ".pdf")
        if (!f.exists() || f.length() != pdf.source.size) {
            val tmp = File(f.path + ".part")
            tmp.outputStream().buffered(1 shl 16).use { pdf.source.copyTo(it) }
            tmp.renameTo(f)
        }
        return f
    }

    fun open(pdf: PdfData): Handle = synchronized(pdf) {
        (pdf.handle as? Handle) ?: Handle(fileFor(pdf)).also { pdf.handle = it }
    }

    /** Page sizes in points, with the page's own rotation. */
    fun sizes(pdf: PdfData): List<Pair<Double, Double>> {
        val h = open(pdf)
        return synchronized(h) {
            (0 until h.count).map { i -> h.renderer.openPage(i).use { Pair(it.width.toDouble(), it.height.toDouble()) } }
        }
    }

    /** A PDF from a file being imported, checked to open. */
    fun read(bytes: Source): PdfData {
        val pdf = PdfData(bytes)
        open(pdf)
        return pdf
    }

    fun read(file: File): PdfData = read(file.readBytes().source())

    /**
     * Draw the PDF page behind [page] into [target], where page point (x, y) lands at
     * ((x - ox) * sx, (y - oy) * sy). The page's own quarter turns (rot) are applied first
     * (B:model.py render_pdf_bg).
     */
    fun render(page: Page, target: Bitmap, ox: Double, oy: Double, sx: Double, sy: Double) {
        val pdf = page.pdf ?: return
        val h = open(pdf)
        synchronized(h) {
            if (page.pdfPage >= h.count) return
            h.renderer.openPage(page.pdfPage).use { p ->
                val w0 = p.width.toFloat()
                val h0 = p.height.toFloat()
                val m = Matrix()
                when (((page.rot % 4) + 4) % 4) {
                    1 -> { m.postRotate(90f); m.postTranslate(h0, 0f) }
                    2 -> { m.postRotate(180f); m.postTranslate(w0, h0) }
                    3 -> { m.postRotate(270f); m.postTranslate(0f, w0) }
                }
                m.postTranslate((-ox).toFloat(), (-oy).toFloat())
                m.postScale(sx.toFloat(), sy.toFloat())
                p.render(target, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }

    /** The PDF page of [page] as a picture of w × h pixels, on white. */
    fun bitmap(page: Page, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        render(page, bmp, 0.0, 0.0, w / page.w, h / page.h)
        return bmp
    }

    /** For pageBitmap(): draws the page's PDF under the ink, or null when it has none. */
    fun background(page: Page): ((Canvas, Double, Double) -> Unit)? {
        if (page.pdf == null) return null
        return { cv, sx, sy ->
            runCatching {
                val bmp = bitmap(page, (page.w * sx).roundToInt(), (page.h * sy).roundToInt())
                cv.save()
                cv.scale((1 / sx).toFloat(), (1 / sy).toFloat())
                cv.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                cv.restore()
            }
        }
    }

    /** Where [query] occurs on the page's PDF, as boxes in page points (Android 15 and later). */
    fun find(page: Page, query: String): List<DoubleArray> {
        val pdf = page.pdf ?: return emptyList()
        if (Build.VERSION.SDK_INT < 35 || query.isBlank()) return emptyList()
        val h = open(pdf)
        return synchronized(h) {
            runCatching {
                h.renderer.openPage(page.pdfPage).use { p ->
                    val w0 = p.width.toDouble()
                    val h0 = p.height.toDouble()
                    p.searchText(query).flatMap { match ->
                        match.bounds.map { r ->
                            val a = io.github.inkpad.core.rotPt(page.rot, w0, h0, r.left.toDouble(), r.top.toDouble())
                            val b = io.github.inkpad.core.rotPt(page.rot, w0, h0, r.right.toDouble(), r.bottom.toDouble())
                            doubleArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
}
