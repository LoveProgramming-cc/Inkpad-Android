package io.github.inkpad.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.Inflater

// A minimal zip reader and writer: stored and deflated entries, no zip64 archives. Translated from
// W:src/core/zip.js (itself from A:Model/Zip.swift). Reads whatever Python's zipfile writes,
// including the zip64 local headers that B's _fatten produces: sizes are always taken from the
// central directory, never from the local header. Entries are read as parts of the file.

class ZipError(message: String) : Exception(message)

class ZipEntry(
    val name: String,
    val flags: Int,
    val method: Int,      // 0 stored, 8 deflate
    val crc: Long,
    val compSize: Long,
    val size: Long,
    val offset: Long,     // of the local header
)

private const val LIMIT = 0xffffffffL

private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
private fun ByteBuffer.u16(at: Int) = getShort(at).toInt() and 0xffff
private fun ByteBuffer.u32(at: Int) = getInt(at).toLong() and 0xffffffffL

fun inflate(raw: ByteArray, size: Long): ByteArray {
    val inf = Inflater(true)
    try {
        inf.setInput(raw)
        val out = ByteArrayOutputStream(maxOf(32, size.toInt()))
        val buf = ByteArray(65536)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    } catch (e: java.util.zip.DataFormatException) {
        throw ZipError("damaged zip data")
    } finally {
        inf.end()
    }
}

fun deflate(data: ByteArray): ByteArray {
    val d = Deflater(6, true)
    try {
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(65536)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    } finally {
        d.end()
    }
}

class ZipReader private constructor(val source: Source, val entries: List<ZipEntry>) {
    private val index = HashMap<String, ZipEntry>().also { m -> entries.forEach { m[it.name] = it } }   // a later entry of the same name wins

    val names get() = entries.map { it.name }
    fun has(name: String) = name in index
    fun entry(name: String) = index[name]

    /** Where the entry's data starts in the file. */
    fun dataStart(e: ZipEntry): Long {
        val head = source.read(e.offset, 30)
        val h = le(head)
        if (head.size < 30 || h.u32(0) != 0x04034b50L) throw ZipError("damaged zip entry")
        val start = e.offset + 30 + h.u16(26) + h.u16(28)
        if (start + e.compSize > source.size) throw ZipError("damaged zip entry")
        return start
    }

    /** The entry's bytes as they are in the file (still compressed). */
    fun raw(e: ZipEntry): Source {
        val s = dataStart(e)
        return source.slice(s, s + e.compSize)
    }

    /** The entry's contents. Stored entries come back as a part of the file, never copied. */
    fun sourceOf(name: String): Source {
        val e = entry(name) ?: throw ZipError("missing $name")
        val raw = raw(e)
        if (e.method == 0) return raw
        if (e.method != 8) throw ZipError("unsupported compression")
        val out = if (e.size == 0L) ByteArray(0) else inflate(raw.bytes(), e.size)
        if (out.size.toLong() != e.size) throw ZipError("damaged zip data")
        return out.source()
    }

    fun bytes(name: String): ByteArray = sourceOf(name).bytes()
    fun text(name: String): String = bytes(name).toString(Charsets.UTF_8)

    /** Checks one entry's CRC against its data (what Python's testzip does). */
    fun crcOk(e: ZipEntry) = Hash.crc32(sourceOf(e.name)) == e.crc

    companion object {
        fun open(source: Source): ZipReader {
            val n = source.size
            if (n < 22) throw ZipError("not a zip file")
            val tailStart = maxOf(0L, n - 22 - 65535)
            val tail = source.read(tailStart, (n - tailStart).toInt())
            var eocd = -1
            for (i in tail.size - 22 downTo 0) {
                if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw ZipError("not a zip file")
            val t = le(tail)
            val count = t.u16(eocd + 10)
            val cdSize = t.u32(eocd + 12)
            val cdOffset = t.u32(eocd + 16)
            if (count == 0xffff || cdSize == LIMIT || cdOffset == LIMIT) throw ZipError("zip64 archives are not supported")
            if (cdOffset + cdSize > n) throw ZipError("damaged zip directory")
            val cd = source.read(cdOffset, cdSize.toInt())
            val dv = le(cd)
            val entries = ArrayList<ZipEntry>(count)
            var o = 0
            repeat(count) {
                if (o + 46 > cd.size || dv.u32(o) != 0x02014b50L) throw ZipError("damaged zip directory")
                val nameLen = dv.u16(o + 28)
                val extraLen = dv.u16(o + 30)
                val commentLen = dv.u16(o + 32)
                if (o + 46 + nameLen + extraLen > cd.size) throw ZipError("damaged zip directory")
                var compSize = dv.u32(o + 20)
                var size = dv.u32(o + 24)
                var offset = dv.u32(o + 42)
                if (compSize == LIMIT || size == LIMIT || offset == LIMIT) {
                    // Fields that do not fit in 32 bits live in the zip64 extra block, in this order.
                    var e = o + 46 + nameLen
                    val eEnd = e + extraLen
                    while (e + 4 <= eEnd) {
                        val id = dv.u16(e)
                        val len = dv.u16(e + 2)
                        if (id == 1) {
                            var p = e + 4
                            fun u64(): Long = dv.getLong(p).also { p += 8 }
                            if (size == LIMIT) size = u64()
                            if (compSize == LIMIT) compSize = u64()
                            if (offset == LIMIT) offset = u64()
                            break
                        }
                        e += 4 + len
                    }
                }
                val name = String(cd, o + 46, nameLen, Charsets.UTF_8)
                entries.add(ZipEntry(name, dv.u16(o + 8), dv.u16(o + 10), dv.u32(o + 16), compSize, size, offset))
                o += 46 + nameLen + extraLen + commentLen
            }
            return ZipReader(source, entries)
        }
    }
}

/** Writes a zip to [out] as entries are added; [finish] writes the directory. */
class ZipWriter(private val out: OutputStream, now: LocalDateTime = LocalDateTime.now()) {
    private val central = ByteArrayOutputStream()
    private var offset = 0L
    private var count = 0
    private val time = ((now.hour shl 11) or (now.minute shl 5) or (now.second shr 1)) and 0xffff
    private val date = (((maxOf(1980, now.year) - 1980) shl 9) or (now.monthValue shl 5) or now.dayOfMonth) and 0xffff

    /** Adds an entry, deflated when that makes it smaller, unless [compress] is false. */
    fun add(name: String, data: ByteArray, compress: Boolean = true) {
        val crc = Hash.crc32(data)
        if (compress && data.size > 32) {
            val packed = deflate(data)
            if (packed.size < data.size) return addRaw(name, 8, crc, data.size.toLong(), packed.source())
        }
        addRaw(name, 0, crc, data.size.toLong(), data.source())
    }

    fun add(name: String, text: String, compress: Boolean = true) = add(name, text.toByteArray(Charsets.UTF_8), compress)

    /** A stored entry from a part of a file, which is copied through without being held. */
    fun addStored(name: String, data: Source) = addRaw(name, 0, Hash.crc32(data), data.size, data)

    /** Adds an entry whose bytes are already in their stored form (copied from another zip). */
    fun addRaw(name: String, method: Int, crc: Long, size: Long, raw: Source) {
        val rawSize = raw.size
        if (rawSize >= LIMIT || size >= LIMIT || offset >= LIMIT) throw ZipError("file too large")
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val flags = if (nameBytes.any { it < 0 }) 0x800 else 0
        val local = le(ByteArray(30 + nameBytes.size))
        local.putInt(0, 0x04034b50).putShort(4, 20).putShort(6, flags.toShort()).putShort(8, method.toShort())
            .putShort(10, time.toShort()).putShort(12, date.toShort())
            .putInt(14, crc.toInt()).putInt(18, rawSize.toInt()).putInt(22, size.toInt())
            .putShort(26, nameBytes.size.toShort()).putShort(28, 0)
        System.arraycopy(nameBytes, 0, local.array(), 30, nameBytes.size)
        val cen = le(ByteArray(46 + nameBytes.size))
        cen.putInt(0, 0x02014b50).putShort(4, 0x031e).putShort(6, 20).putShort(8, flags.toShort()).putShort(10, method.toShort())
            .putShort(12, time.toShort()).putShort(14, date.toShort())
            .putInt(16, crc.toInt()).putInt(20, rawSize.toInt()).putInt(24, size.toInt())
            .putShort(28, nameBytes.size.toShort())   // extra, comment, disk, internal attributes: all zero
            .putInt(38, (0x81a4 shl 16)).putInt(42, offset.toInt())   // a regular file, rw-r--r--
        System.arraycopy(nameBytes, 0, cen.array(), 46, nameBytes.size)
        out.write(local.array())
        raw.copyTo(out)
        central.write(cen.array())
        offset += 30 + nameBytes.size + rawSize
        count++
    }

    /** A directory entry (name ends with "/"), as the library archive wants. */
    fun addDir(name: String) = addRaw(if (name.endsWith("/")) name else "$name/", 0, 0, 0, ByteArray(0).source())

    fun finish() {
        val cdSize = central.size().toLong()
        if (count > 0xfffe || offset + cdSize >= LIMIT) throw ZipError("file too large")
        central.writeTo(out)
        val end = le(ByteArray(22))
        end.putInt(0, 0x06054b50).putShort(8, count.toShort()).putShort(10, count.toShort())
            .putInt(12, cdSize.toInt()).putInt(16, offset.toInt())
        out.write(end.array())
        out.flush()
    }
}
