package io.github.inkpad.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

val PNG: ByteArray = java.util.HexFormat.of().parseHex(
    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c6360f8cf5000000401010000d1b6f4c00000000049454e44ae426082",
)
val black = rgb(0.0, 0.0, 0.0)

fun resource(name: String): File {
    val url = Thread.currentThread().contextClassLoader.getResource(name) ?: error("no resource $name")
    return File(url.toURI())
}

fun tempDir(): File = Files.createTempDirectory("inkpad").toFile()

/** The file a snapshot makes, as bytes. */
fun encode(snap: Snapshot): ByteArray {
    val dir = tempDir()
    val f = File(dir, "x.inkpad")
    Document.writeSnapshot(f, snap)
    return f.readBytes()
}

/** The same description of a document that dump_doc.py gives of B's. */
fun dumpDoc(doc: Document): Map<String, Any?> = mapOf(
    "id" to doc.id,
    "gone_pages" to doc.gonePages.sorted(),
    "order_mt" to doc.orderMt,
    "cover" to doc.coverSpec?.let { listOf(it.first, it.second.toList()) },
    "pages" to doc.pages.map { page ->
        mapOf(
            "id" to page.id, "w" to page.w, "h" to page.h, "paper" to page.paper, "color" to page.color.toList(),
            "board" to page.board, "bookmark" to page.bookmark, "outline" to page.outline, "rot" to page.rot,
            "mt" to page.mt, "gone" to page.gone.sorted(), "pdf" to page.pdf?.key, "pdf_sha" to page.pdf?.sha256(),
            "pdf_page" to page.pdfPage,
            "items" to page.items.map { it ->
                val d = linkedMapOf<String, Any?>("kind" to it.kind, "id" to it.id, "bbox" to it.bbox.toList())
                when (it) {
                    is Stroke -> d.putAll(mapOf("tool" to it.tool, "color" to it.color.toList(), "width" to it.width, "sens" to it.sens,
                        "dash" to it.dash, "open" to it.open, "pts" to it.pts.map { p -> p.toList() }))
                    is Shape -> d.putAll(mapOf("shape" to it.shape, "r" to listOf(it.x0, it.y0, it.x1, it.y1), "color" to it.color.toList(), "width" to it.width))
                    is Text -> d.putAll(mapOf("x" to it.x, "y" to it.y, "text" to it.text, "size" to it.size, "color" to it.color.toList(),
                        "wrap" to it.wrap, "bg" to it.bg?.toList()))
                    is Image -> d.putAll(mapOf("r" to listOf(it.x, it.y, it.w, it.h), "sha" to it.data.sha256()))
                }
                d
            },
        )
    },
)

/** Deep comparison of a dump with an expected one: numbers to 1e-9, maps by key. */
fun nearDump(actual: Any?, expected: Any?, path: String = "$") {
    if (expected is Map<*, *>) {
        val a = actual as Map<*, *>
        for ((k, v) in expected) nearDump(a[k], v, "$path.$k")
    } else if (expected is List<*> && actual is List<*>) {
        assertEquals(expected.size, actual.size, "$path length")
        expected.forEachIndexed { i, v -> nearDump(actual[i], v, "$path[$i]") }
    } else {
        near(actual, expected, path)
    }
}

fun py(vararg args: String): String {
    val p = ProcessBuilder(listOf("python3", "-I") + args).redirectErrorStream(true).start()
    val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
    check(p.waitFor() == 0) { "python failed:\n$out" }
    return out
}

val testPython: File get() = File(System.getProperty("inkpad.root", ".."), "core/src/test/python")

class DocumentTest {
    @AfterTest
    fun restoreClock() {
        Clock.override = null
    }

    private fun sampleDocument(): Document {
        val doc = Document(Template(595.0, 842.0, "grid", rgb(0.976, 0.965, 0.902)))
        val page = doc.pages[0]
        val tape = Stroke("tape", rgb(0.96, 0.82, 0.36), 16.0, listOf(pt(50.0, 300.0), pt(200.0, 300.0)))
        tape.open = true
        doc.addItems(page, listOf(
            Stroke("fountain", rgb(0.1, 0.2, 0.3), 1.6, listOf(pt(10.0, 10.0, 0.2), pt(60.0, 40.0, 0.8), pt(120.0, 30.0, 0.5)), 1.2), tape,
            Shape("arrow", 20.0, 100.0, 200.0, 180.0, rgb(1.0, 0.0, 0.0), 2.0),
            Text(40.0, 400.0, "你好 Inkpad", 18.0, black, 300.0),
            Text(300.0, 400.0, "note", 13.0, rgb(0.16, 0.15, 0.12), 170.0, rgb(1.0, 0.93, 0.55)),
            Image(100.0, 500.0, 80.0, 60.0, ImageData(PNG.source())),
        ))
        val second = page.blankLike()
        second.bookmark = true
        second.outline = "第二页"
        doc.addPages(1, listOf(second))
        doc.addItems(second, listOf(Stroke("hl", rgb(1.0, 0.88, 0.3), 14.0, listOf(pt(5.0, 5.0), pt(100.0, 5.0)), 1.0, 1)))
        return doc
    }

    @Test
    fun packedPointsMatchTheDesktopApp() {
        val pts = listOf(pt(100.123, 200.456, 0.5), pt(101.005, 199.995, 0.73), pt(50.0, -12.345, 0.0),
            pt(50000000.25, 50000000.75, 1.0), pt(49999990.115, 50000003.125, 0.2))
        val packed = "HVpkO98HWUA730+Nlw5pQAAAgLABW7rXT+PLAgCi+q6gJbrcr6Al/+kP2gMz"
        assertEquals(packed, packPoints(pts))
        near(unpackPoints(packed), listOf(listOf(100.123, 200.456, 0.5019607843137255), listOf(101.0, 200.0, 0.7294117647058823),
            listOf(50.0, -12.34, 0.0), listOf(50000000.25, 50000000.75, 1.0), listOf(49999990.12, 50000003.12, 0.2)))
    }

    @Test
    fun documentRoundTrip() {
        val doc = sampleDocument()
        doc.coverSpec = Pair("band", rgb(0.17, 0.27, 0.45))
        val dir = tempDir()
        val f = File(dir, "a.inkpad")
        doc.save(f)
        assertEquals(doc.id, notebookId(f))
        val back = Document.load(f)
        assertEquals(doc.id, back.id)
        assertEquals(doc.pages.map { it.id }, back.pages.map { it.id })
        assertEquals("grid", back.pages[0].paper)
        assertEquals(0.965, back.pages[0].color[1])
        assertEquals(doc.pages[0].items.map { it.id }, back.pages[0].items.map { it.id })
        assertEquals(listOf("s", "s", "h", "t", "t", "i"), back.pages[0].items.map { it.kind })
        val s = back.pages[0].items[0] as Stroke
        assertEquals("fountain", s.tool)
        assertEquals(1.2, s.sens)
        assertEquals(3, s.pts.size)
        assertTrue(abs(s.pts[1][0] - 60) < 0.01)
        assertTrue((back.pages[0].items[1] as Stroke).open)
        assertEquals("你好 Inkpad", (back.pages[0].items[3] as Text).text)
        assertContentEquals(doubleArrayOf(1.0, 0.93, 0.55), (back.pages[0].items[4] as Text).bg)
        assertContentEquals(PNG, (back.pages[0].items[5] as Image).data.source.bytes())
        assertTrue(back.pages[1].bookmark)
        assertEquals("第二页", back.pages[1].outline)
        assertEquals(1, (back.pages[1].items[0] as Stroke).dash)
        assertEquals("band", back.coverSpec!!.first)
        val z = ZipReader.open(f.source())
        assertEquals("document.json", z.names[0])
        assertTrue(z.has("id") && z.has("cover.png") && z.has("img/0.png"))
        assertEquals(0, z.entry("img/0.png")!!.method)
        // written as B writes it: floats with a point, non-ASCII escaped
        assertTrue("\"w\":595.0" in z.text("document.json"))
        assertTrue("\\u7b2c\\u4e8c\\u9875" in z.text("document.json"))

        setNotebookId(f, "feedfacefeedfacefeedfacefeedface")
        assertEquals("feedfacefeedfacefeedfacefeedface", notebookId(f))
        val again = Document.load(f)
        assertEquals("feedfacefeedfacefeedfacefeedface", again.id)
        assertEquals(6, again.pages[0].items.size)
    }

    @Test
    fun aDocumentReadsItsPicturesAfterItsFileIsReplaced() {
        val doc = sampleDocument()
        val f = File(tempDir(), "a.inkpad")
        doc.save(f)
        val back = Document.load(f)
        back.addItems(back.pages[0], listOf(Shape("line", 0.0, 0.0, 9.0, 9.0, black, 1.0)))
        back.save()
        back.save()   // the second save reads the picture from the file the first one replaced
        assertContentEquals(PNG, (Document.load(f).pages[0].items[5] as Image).data.source.bytes())
    }

    @Test
    fun undoLeavesTombstones() {
        val doc = Document()
        val page = doc.pages[0]
        val a = Stroke("ball", black, 1.0, listOf(pt(0.0, 0.0), pt(5.0, 5.0)))
        val b = Stroke("ball", black, 1.0, listOf(pt(9.0, 0.0), pt(5.0, 5.0)))
        doc.addItems(page, listOf(a, b))
        doc.removeItems(page, listOf(a))
        assertEquals(1, page.items.size)
        assertTrue(a.id in page.gone)
        doc.undo()
        assertEquals(listOf(a.id, b.id), page.items.map { it.id })
        assertFalse(a.id in page.gone)
        doc.undo()
        assertEquals(0, page.items.size)
        assertTrue(a.id in page.gone && b.id in page.gone)
        doc.redo()
        assertEquals(2, page.items.size)
        assertTrue(doc.canUndo && doc.canRedo)
    }

    @Test
    fun aPureAppendIsAddedAnythingElseDirty() {
        val doc = Document()
        val page = doc.pages[0]
        val log = ArrayList<String>()
        val stop = doc.listen { log.add(it::class.simpleName!!) }
        val s1 = Stroke("ball", black, 1.0, listOf(pt(0.0, 0.0), pt(5.0, 5.0)))
        doc.addItems(page, listOf(s1))
        doc.removeItems(page, listOf(s1))
        doc.undo()
        stop()
        assertEquals(listOf("Added", "State", "Dirty", "State", "Added", "State"), log)
        assertEquals(1, doc.undoStack.size)
    }

    @Test
    fun undoHistoryIsCappedAt300() {
        val doc = Document()
        repeat(320) { doc.addItems(doc.pages[0], listOf(Shape("line", 0.0, 0.0, it.toDouble(), it.toDouble(), black, 1.0))) }
        assertEquals(300, doc.undoStack.size)
    }

    @Test
    fun mergeItemByItem() {
        val doc = sampleDocument()
        val bytes = encode(doc.snapshot())
        val mine = Document.load(bytes.source())
        val theirs = Document.load(bytes.source())
        val arrow = mine.pages[0].items[2]
        mine.removeItems(mine.pages[0], listOf(arrow))
        val myNew = Stroke("ball", black, 1.0, listOf(pt(1.0, 1.0), pt(2.0, 2.0)))
        mine.addItems(mine.pages[0], listOf(myNew))
        val tape = theirs.pages[0].items[1]
        theirs.removeItems(theirs.pages[0], listOf(tape))
        val theirNew = Stroke("ball", black, 1.0, listOf(pt(3.0, 3.0), pt(4.0, 4.0)))
        theirs.addItems(theirs.pages[0], listOf(theirNew))
        val extra = theirs.pages[0].blankLike()
        theirs.addPages(1, listOf(extra))
        val counts = merge(mine, theirs)
        assertEquals(1, counts[0])
        assertEquals(1, counts[2])
        assertEquals(1, counts[3])
        assertEquals(listOf(doc.pages[0].id, extra.id, doc.pages[1].id), mine.pages.map { it.id })
        val ids = mine.pages[0].items.map { it.id }.toSet()
        assertTrue(myNew.id in ids && theirNew.id in ids)
        assertFalse(arrow.id in ids || tape.id in ids)
        assertEquals(6, mine.pages[0].items.size)
        assertEquals(0, mine.undoStack.size)
    }

    @Test
    fun theSamePdfIsWrittenOnce() {
        val doc = Document()
        val a = PdfData("%PDF-1.4 same".toByteArray().source())
        val b = PdfData("%PDF-1.4 same".toByteArray().source())   // a second copy of the same file
        val c = PdfData("%PDF-1.4 other".toByteArray().source())
        doc.pages = arrayListOf(Page(pdf = a), Page(pdf = b, pdfPage = 1), Page(pdf = c))
        val snap = doc.snapshot()
        assertEquals(2, snap.pdfs.size)
        assertEquals(listOf(0L, 0L, 1L), Json.parse(snap.json).obj()["pages"].arr().map { it.obj()["pdf"] })
        val back = Document.load(encode(snap).source())
        assertSame(back.pages[0].pdf, back.pages[1].pdf)
        assertNotSame(back.pages[0].pdf, back.pages[2].pdf)
        assertEquals(1, back.pages[1].pdfPage)
    }

    @Test
    fun aPictureUsedTwiceIsWrittenOnce() {
        val doc = Document()
        val data = ImageData(PNG.source())
        doc.addItems(doc.pages[0], listOf(Image(0.0, 0.0, 10.0, 10.0, data), Image(20.0, 0.0, 10.0, 10.0, data),
            Image(40.0, 0.0, 10.0, 10.0, ImageData(PNG.copyOf().source()))))
        val snap = doc.snapshot()
        assertEquals(2, snap.images.size)
        val back = Document.load(encode(snap).source())
        val imgs = back.pages[0].items.map { (it as Image).data }
        assertSame(imgs[0], imgs[1])
        assertSame(imgs[0], imgs[2])   // identical bytes stored twice come back as one
    }

    @Test
    fun pagesRememberWhenTheirSettingsAndOrderChanged() {
        var t = 1000.0
        Clock.override = { t }
        val doc = Document()
        doc.pages.add(Page())
        doc.stamp()
        val p = doc.pages[0]
        assertEquals(1000.0, p.mt, "a new page is stamped at its first look")
        assertEquals(0.0, doc.orderMt, "the first look at the order only records it")
        t = 2000.0
        doc.stamp()
        assertEquals(1000.0, p.mt, "nothing changed, nothing stamped")
        p.paper = "dots"
        doc.stamp()
        assertEquals(2000.0, p.mt, "a settings change is stamped, in seconds")
        t = 3000.0
        doc.pages.add(Page())
        doc.stamp()
        assertEquals(0.0, doc.orderMt, "adding a page is not a reordering")
        doc.pages.reverse()
        doc.stamp()
        assertEquals(3000.0, doc.orderMt)
    }

    @Test
    fun whatBWroteWeReadTheSame() {
        val expected = fixture("b-sample.json")
        val doc = Document.load(resource("b-sample.inkpad"))
        nearDump(dumpDoc(doc), expected)
        assertSame(doc.pages[2].pdf, doc.pages[3].pdf, "the same PDF twice is one object")
        assertEquals(3, doc.pages[3].rot)
        assertTrue(doc.pages[4].board)
        assertEquals(1, doc.pages[0].gone.size)
    }

    @Test
    fun whatWeWriteBReadsTheSame() {
        val doc = Document.load(resource("b-sample.inkpad"))
        doc.addItems(doc.pages[0], listOf(Text(1.0, 2.0, "extra", 12.0, black, 0.0), Shape("ellipse", 1.0, 2.0, 30.0, 40.0, rgb(0.0, 0.5, 1.0), 1.5)))
        doc.pages[1].outline = "改过"
        val f = File(tempDir(), "kt.inkpad")
        Document.writeSnapshot(f, doc.snapshot())
        val seen = Json.parse(py(File(testPython, "dump_doc.py").path, f.path)).obj()
        nearDump(dumpDoc(doc), seen)
        val pages = seen["pages"].arr().map { it.obj() }
        assertEquals("改过", pages[1]["outline"])
        assertEquals(pages[2]["pdf_sha"], pages[3]["pdf_sha"])
        assertEquals(9, pages[0]["items"].arr().size)
    }

    @Test
    fun mergingGivesTheResultBGives() {
        val want = fixture("merge-result.json").obj()
        val mine = Document.load(resource("merge-mine.inkpad"))
        val theirs = Document.load(resource("merge-theirs.inkpad"))
        val counts = merge(mine, theirs)
        near(counts.toList(), want["counts"])
        nearDump(dumpDoc(mine), want["doc"])
    }

    @Test
    fun jsonIsWrittenAsPythonWritesIt() {
        assertEquals("{\"a\":595.0,\"b\":1e+16,\"c\":0.0001,\"d\":1e-05,\"e\":-2.5,\"f\":100000000.0,\"g\":3,\"h\":\"\\u4f60\\\"x\\\\\"}",
            JsonOut.write(linkedMapOf("a" to 595.0, "b" to 1e16, "c" to 0.0001, "d" to 0.00001, "e" to -2.5, "f" to 1e8, "g" to 3, "h" to "你\"x\\")))
        val out = ByteArrayOutputStream()
        out.write(0)
        assertEquals("1760000000.5", JsonOut.pyFloat(1760000000.5))
        assertEquals("0.1", JsonOut.pyFloat(0.1))
        assertEquals("0.30000000000000004", JsonOut.pyFloat(0.1 + 0.2))
    }
}
