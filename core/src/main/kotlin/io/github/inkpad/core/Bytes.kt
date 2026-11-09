package io.github.inkpad.core

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Bytes that can be read at any offset without being read whole: a file, a part of one, or an
 * array. A notebook may carry a 200 MB PDF; zip entries, uploads and checksums all work on parts
 * of the file (as the web version does with Blob.slice()).
 */
interface Source {
    val size: Long

    /** [len] bytes from [offset]; fewer only at the end. */
    fun read(offset: Long, len: Int): ByteArray

    fun slice(start: Long, end: Long = size): Source = Slice(this, start, end.coerceAtMost(size))

    fun stream(): InputStream = object : InputStream() {
        private var at = 0L
        override fun read(): Int {
            if (at >= size) return -1
            return this@Source.read(at++, 1)[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (at >= size) return -1
            val n = minOf(len.toLong(), size - at).toInt()
            val got = this@Source.read(at, n)
            System.arraycopy(got, 0, b, off, got.size)
            at += got.size
            return got.size
        }
    }

    fun copyTo(out: OutputStream) {
        var at = 0L
        while (at < size) {
            val chunk = read(at, minOf(CHUNK.toLong(), size - at).toInt())
            out.write(chunk)
            at += chunk.size
        }
    }

    fun bytes(): ByteArray {
        require(size <= Int.MAX_VALUE) { "too large to hold" }
        return read(0, size.toInt())
    }

    companion object {
        const val CHUNK = 1 shl 20
    }
}

class ByteSource(private val data: ByteArray) : Source {
    override val size get() = data.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray =
        data.copyOfRange(offset.toInt(), minOf(data.size.toLong(), offset + len).toInt())
    override fun bytes() = data
}

/** A file, opened for each read so that nothing is held open. */
class FileSource(val file: File) : Source {
    override val size: Long = file.length()
    override fun read(offset: Long, len: Int): ByteArray {
        RandomAccessFile(file, "r").use { f ->
            val n = minOf(len.toLong(), size - offset).toInt().coerceAtLeast(0)
            val out = ByteArray(n)
            f.seek(offset)
            f.readFully(out)
            return out
        }
    }

    override fun copyTo(out: OutputStream) {
        file.inputStream().use { it.copyTo(out, Source.CHUNK) }
    }
}

private class Slice(val base: Source, val start: Long, val end: Long) : Source {
    override val size get() = end - start
    override fun read(offset: Long, len: Int) = base.read(start + offset, minOf(len.toLong(), size - offset).toInt())
    override fun slice(start: Long, end: Long): Source = Slice(base, this.start + start, this.start + end.coerceAtMost(size))
    override fun copyTo(out: OutputStream) {
        if (base is FileSource) {
            RandomAccessFile(base.file, "r").use { f ->
                f.seek(start)
                val buf = ByteArray(minOf(Source.CHUNK.toLong(), size).toInt().coerceAtLeast(1))
                var left = size
                while (left > 0) {
                    val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
        } else {
            super.copyTo(out)
        }
    }
}

fun ByteArray.source(): Source = ByteSource(this)
fun File.source(): Source = FileSource(this)

/** Checksums, fed in parts. */
object Hash {
    private fun feed(src: Source, fn: (ByteArray, Int) -> Unit) {
        src.stream().use { s ->
            val buf = ByteArray(Source.CHUNK)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                fn(buf, n)
            }
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun md5(src: Source): String = MessageDigest.getInstance("MD5").let { d -> feed(src) { b, n -> d.update(b, 0, n) }; hex(d.digest()) }
    fun sha256(src: Source): String = MessageDigest.getInstance("SHA-256").let { d -> feed(src) { b, n -> d.update(b, 0, n) }; hex(d.digest()) }
    fun crc32(src: Source): Long = CRC32().let { c -> feed(src) { b, n -> c.update(b, 0, n) }; c.value }
    fun md5(b: ByteArray) = md5(b.source())
    fun sha256(b: ByteArray) = sha256(b.source())
    fun crc32(b: ByteArray): Long = CRC32().let { it.update(b); it.value }
}
