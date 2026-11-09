package io.github.inkpad.core

import java.util.UUID

// What the core needs from the platform, with stand-ins that let it run on a plain JVM. The app
// installs the real ones at start-up (text measured with the screen font, PNGs drawn and turned,
// covers rendered).

/** The only clock for times kept in files: wall-clock SECONDS as a float (B's time.time()). */
object Clock {
    @Volatile var override: (() -> Double)? = null
    private var last = 0.0

    /** Never the same time twice, as Python's microsecond clock in effect never is. */
    @Synchronized
    fun now(): Double {
        override?.let { return it() }
        last = maxOf(System.currentTimeMillis() / 1000.0, last + 1e-6)
        return last
    }
}

object Ids {
    /** 32 hex digits: what documents are named by. */
    fun hex(): String = UUID.randomUUID().toString().replace("-", "")

    /** 16 hex digits: pages and items. */
    fun short(): String = hex().substring(0, 16)
}

/** Measures text in the font it is shown in. */
interface TextMetrics {
    fun width(text: String, size: Double): Double
    fun lineHeight(size: Double): Double
    fun ascent(size: Double): Double
}

/** The stand-in B is given in tests (A:tools/compat.py), so that text boxes measure alike. */
object StubMetrics : TextMetrics {
    override fun width(text: String, size: Double) = text.codePointCount(0, text.length) * size * 0.6
    override fun lineHeight(size: Double) = size * 1.3
    override fun ascent(size: Double) = size
}

/** Pictures: turning one a quarter turn means encoding it again. */
interface ImageCodec {
    /** PNG bytes turned k quarter turns clockwise. */
    fun rotate(png: ByteArray, k: Int): ByteArray

    /** Any picture file as PNG, upright, no larger than [limit] pixels on its longer side. */
    fun toPng(bytes: ByteArray, limit: Int): ByteArray
}

object Platform {
    @Volatile var metrics: TextMetrics = StubMetrics
    @Volatile var images: ImageCodec? = null

    /** Draws a document's cover as PNG; without one a blank picture is kept. */
    @Volatile var covers: ((Document) -> ByteArray)? = null

    /** Interface text in the user's language (the source text is Chinese). */
    @Volatile var tr: (String) -> String = { it }
}

fun tr(text: String) = Platform.tr(text)

/** tr() with %s and %d filled in. */
fun trf(text: String, vararg args: Any?): String {
    var i = 0
    return Regex("%[sd]").replace(tr(text)) { args.getOrNull(i++)?.toString() ?: "" }
}

/** A 1×1 PNG, for a cover when there is nothing to draw one with. */
val BLANK_PNG: ByteArray = java.util.Base64.getDecoder()
    .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
