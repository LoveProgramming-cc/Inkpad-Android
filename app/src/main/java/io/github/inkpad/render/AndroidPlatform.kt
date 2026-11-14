package io.github.inkpad.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import io.github.inkpad.core.Document
import io.github.inkpad.core.ImageCodec
import io.github.inkpad.core.ImageData
import io.github.inkpad.core.Json
import io.github.inkpad.core.Platform
import io.github.inkpad.core.TextMetrics
import io.github.inkpad.core.obj
import io.github.inkpad.core.pageBox
import io.github.inkpad.pdf.PdfPages
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Text measured in the font it is shown in. */
object AndroidMetrics : TextMetrics {
    private val paint = textPaint(100.0, 0)
    private val fm = paint.fontMetrics
    // Measured at 100 points and scaled: the same at every zoom, as a PDF's text is.
    @Synchronized
    override fun width(text: String, size: Double) = paint.measureText(text) / 100.0 * size
    override fun lineHeight(size: Double) = (fm.descent - fm.ascent + fm.leading) / 100.0 * size
    override fun ascent(size: Double) = -fm.ascent / 100.0 * size
}

/** Pictures on pages: decoded once, kept while the data is. */
object Pictures : ImageCodec {
    private val cache = WeakHashMap<ImageData, Bitmap>()

    @Synchronized
    fun bitmap(data: ImageData): Bitmap? = cache[data] ?: runCatching {
        val bytes = data.source.bytes()
        // A picture from elsewhere may be far larger than any screen shows it: it is read at
        // most about 4096 pixels on its longer side (a power of two smaller), so that one
        // camera photo cannot take all the memory there is.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > PICTURE_MAX_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()?.also { cache[data] = it }

    private const val PICTURE_MAX_SIDE = 4096

    override fun rotate(png: ByteArray, k: Int): ByteArray {
        val src = BitmapFactory.decodeByteArray(png, 0, png.size) ?: return png
        val m = Matrix().apply { postRotate(90f * k) }
        return encode(Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true))
    }

    override fun toPng(bytes: ByteArray, limit: Int): ByteArray = convert(bytes, limit).first

    fun encode(bmp: Bitmap): ByteArray = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Any picture file as PNG, turned upright, no larger than [limit] pixels on its longer side. */
    fun convert(bytes: ByteArray, limit: Int = 2000): Triple<ByteArray, Int, Int> {
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { d, info, _ ->
            val s = info.size
            val scale = min(1.0, limit.toDouble() / max(s.width, s.height))
            if (scale < 1) d.setTargetSize(max(1, (s.width * scale).roundToInt()), max(1, (s.height * scale).roundToInt()))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return Triple(encode(decoded), decoded.width, decoded.height)
    }
}

/** Interface text. Source strings are Simplified Chinese; English and Japanese look them up. */
object I18n {
    var lang = "zh"
        private set
    private var table: Map<String, String> = emptyMap()

    fun resolve(setting: String): String {
        if (setting == "zh" || setting == "en" || setting == "ja") return setting
        val l = Locale.getDefault().language
        return when {
            l.startsWith("ja") -> "ja"
            l.startsWith("zh") -> "zh"
            else -> "en"
        }
    }

    fun use(context: Context, setting: String) {
        lang = resolve(setting)
        table = if (lang == "zh") emptyMap() else runCatching {
            Json.parse(context.assets.open("i18n/$lang.json").readBytes().toString(Charsets.UTF_8)).obj().mapValues { it.value as String }
        }.getOrDefault(emptyMap())
        Platform.tr = { text -> table[text] ?: text }
    }
}

object AndroidPlatform {
    fun install(context: Context) {
        Platform.metrics = AndroidMetrics
        Platform.images = Pictures
        Platform.covers = ::cover
        PdfPages.cacheDir = java.io.File(context.cacheDir, "pdf")
    }

    /** The cover as PNG: the designed one, or the first page shrunk. */
    fun cover(doc: Document): ByteArray {
        val spec = doc.coverSpec
        if (spec != null) return Pictures.encode(renderCover(spec.first, spec.second, doc.displayName()))
        val page = doc.pages[0]
        val (x0, y0, x1, y1) = pageBox(page)
        val scale = 300 / max(x1 - x0, y1 - y0)
        // A board's content can have any shape; show it centred on a sheet of its paper.
        val bmp = if (page.board) pageBitmap(page, 0.0, Pair(300, 400))
        else pageBitmap(page, scale, background = PdfPages.background(page))
        return Pictures.encode(bmp)
    }
}
