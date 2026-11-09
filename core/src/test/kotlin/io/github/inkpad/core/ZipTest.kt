package io.github.inkpad.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs B's language on a script, for checking what the two make of each other's files. */
fun python(script: String, vararg args: String): String {
    val p = ProcessBuilder(listOf("python3", "-I", "-c", script) + args).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "python failed:\n$out" }
    return out
}

class ZipTest {
    private fun write(fn: (ZipWriter) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        val w = ZipWriter(out)
        fn(w)
        w.finish()
        return out.toByteArray()
    }

    @Test
    fun roundTripStoredDeflatedUnicodeAndRawCopy() {
        val big = "hello world ".repeat(500).toByteArray()
        val bin = ByteArray(5000) { ((it * 2654435761L) ushr 24).toByte() }
        val zip = write { w ->
            w.add("document.json", big)
            w.add("id", "abc123", compress = false)
            w.add("pdf/0.pdf", bin, compress = false)
            w.add("笔记/名字.txt", "你好")
            w.add("empty", ByteArray(0))
        }
        val r = ZipReader.open(zip.source())
        assertEquals(listOf("document.json", "id", "pdf/0.pdf", "笔记/名字.txt", "empty"), r.names)
        assertEquals(8, r.entry("document.json")!!.method)
        assertEquals(0, r.entry("id")!!.method)
        assertEquals(0L, r.entry("empty")!!.size)
        assertContentEquals(big, r.bytes("document.json"))
        assertEquals("abc123", r.text("id"))
        assertContentEquals(bin, r.bytes("pdf/0.pdf"))
        assertEquals("你好", r.text("笔记/名字.txt"))
        for (e in r.entries) assertTrue(r.crcOk(e), e.name)
        // entries copied without recompressing
        val again = write { w -> for (e in r.entries) w.addRaw(e.name, e.method, e.crc, e.size, r.raw(e)) }
        val r2 = ZipReader.open(again.source())
        assertContentEquals(big, r2.bytes("document.json"))
        assertEquals(r.entry("document.json")!!.compSize, r2.entry("document.json")!!.compSize)
    }

    @Test
    fun partsOfAFileAreCopiedThrough() {
        val dir = Files.createTempDirectory("zt").toFile()
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val f = File(dir, "big.bin").apply { writeBytes(data) }
        val zip = File(dir, "a.zip")
        zip.outputStream().use { o -> ZipWriter(o).apply { addStored("pdf/0.pdf", f.source().slice(1000, 201_000)); finish() } }
        val r = ZipReader.open(zip.source())
        assertContentEquals(data.copyOfRange(1000, 201_000), r.bytes("pdf/0.pdf"))
    }

    @Test
    fun pythonAcceptsWhatWeWrite() {
        val dir = Files.createTempDirectory("zt").toFile()
        val file = File(dir, "a.zip")
        file.writeBytes(write { w ->
            w.add("document.json", "{\"a\":1}".repeat(100))
            w.add("img/0.png", ByteArray(300) { it.toByte() }, compress = false)
            w.add("名.txt", "x")
            w.addDir("folder")
        })
        val out = python("import sys,zipfile\nz=zipfile.ZipFile(sys.argv[1])\nprint(z.testzip())\nprint(sorted(z.namelist()))", file.path)
        assertTrue(out.startsWith("None\n"), out)
        assertTrue("名.txt" in out, out)
    }

    @Test
    fun weReadWhatPythonWritesIncludingZip64LocalHeaders() {
        val dir = Files.createTempDirectory("zt").toFile()
        val file = File(dir, "p.zip")
        python(
            """
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('document.json', '{"x": 1}' * 50)
    z.writestr(zipfile.ZipInfo('id'), 'deadbeef')
    z.writestr('名字/é.txt', 'héllo')
    with z.open(zipfile.ZipInfo('pdf/0.pdf'), 'w', force_zip64=True) as f:
        f.write(bytes(range(256)) * 20)
""",
            file.path,
        )
        val r = ZipReader.open(file.source())
        assertEquals(listOf("document.json", "id", "名字/é.txt", "pdf/0.pdf"), r.names)
        assertEquals("deadbeef", r.text("id"))
        assertEquals("{\"x\": 1}".repeat(50), r.text("document.json"))
        assertEquals("héllo", r.text("名字/é.txt"))
        val pdf = r.bytes("pdf/0.pdf")
        assertEquals(5120, pdf.size)
        assertEquals(r.entry("pdf/0.pdf")!!.crc, Hash.crc32(pdf))
        for (e in r.entries) assertTrue(r.crcOk(e), e.name)
    }

    @Test
    fun checksumsMatchKnownValues() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Hash.md5("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.sha256("abc".toByteArray()))
        assertEquals(0x352441c2L, Hash.crc32("abc".toByteArray()))
        val big = ByteArray(3_000_001) { (it * 7).toByte() }
        assertEquals(Hash.sha256(big), Hash.sha256(big.source().slice(0)))
    }
}
