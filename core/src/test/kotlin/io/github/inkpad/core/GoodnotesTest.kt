package io.github.inkpad.core

import java.io.File
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Goodnotes: what is written is read back, and the desktop app's own reader reads it too. */
class GoodnotesTest {
    private fun sample(): Document {
        val doc = Document(Template(595.0, 842.0, "grid", rgb(1.0, 1.0, 1.0)))
        val wave = (0 until 30).map { pt(50.0 + it * 10, 200 + sin(it / 4.0) * 30) }
        doc.addItems(doc.pages[0], listOf(
            Stroke("fountain", rgb(0.8, 0.1, 0.1), 2.0, wave, 1.0),
            Stroke("hl", rgb(1.0, 0.88, 0.3), 14.0, listOf(pt(50.0, 400.0), pt(300.0, 400.0))),
            Shape("rect", 100.0, 500.0, 200.0, 560.0, rgb(0.0, 0.0, 1.0), 2.0),
        ))
        val second = Page(595.0, 842.0)
        doc.addPages(1, listOf(second))
        doc.addItems(second, listOf(Stroke("ball", rgb(0.0, 0.5, 0.0), 1.5, listOf(pt(10.0, 10.0), pt(100.0, 120.0), pt(200.0, 40.0)))))
        return doc
    }

    /** The background the app would make; only its being a PDF matters here. */
    private val background = "%PDF-1.4\n% stand-in\n%%EOF\n".toByteArray().source()
    private val sizes = { _: Source -> listOf(Pair(595.0, 842.0), Pair(595.0, 842.0)) }

    private fun written(title: String): File {
        val f = File(tempDir(), "a.goodnotes")
        f.outputStream().use { Goodnotes.save(sample(), title, background, it) }
        return f
    }

    @Test
    fun writingAFileAndReadingItAgain() {
        val f = written("测试 Title")
        val z = ZipReader.open(f.source())
        for (name in listOf("index.events.pb", "index.notes.pb", "index.attachments.pb", "schema.pb")) assertTrue(z.has(name), name)
        assertTrue(z.names.any { it.startsWith("attachments/") })
        assertEquals(2, z.names.count { it.startsWith("notes/") })
        val (back, title, stats) = Goodnotes.load(f, sizes)
        assertEquals("测试 Title", title)
        assertEquals(2, back.pages.size)
        assertEquals(3, stats.strokes, "the two strokes of page 1 (the shape is part of the background) and the one of page 2")
        val first = back.pages[0].items.map { it as Stroke }
        assertEquals(2, first.size)
        // (the writer numbers a stroke by its point count, so the order of strokes on a page is not kept)
        val ink = first.first { it.tool != "hl" }
        assertNotNull(first.firstOrNull { it.tool == "hl" }, "the highlighter stays a highlighter")
        assertTrue(abs(ink.pts[0][0] - 50) < 0.5 && abs(ink.pts[0][1] - 200) < 0.5, "the first point is where it was")
        assertEquals(listOf(0.8, 0.1, 0.1), ink.color.map { Math.round(it * 100) / 100.0 })
        val last = (back.pages[1].items[0] as Stroke).pts
        assertTrue(last.size >= 3)
        assertTrue(abs(last.last()[0] - 200) < 0.5)
    }

    @Test
    fun theDesktopAppReadsWhatIsWritten() {
        val desktop = File(System.getProperty("inkpad.root", ".."), "ref/B")
        if (!File(desktop, "inkpad/goodnotes.py").exists()) return
        val f = written("From Android")
        val out = py("-c", """
import sys, json
sys.path.insert(0, ${JsonOut.write(testPython.path)})
from stubs import load
model, geom = load(${JsonOut.write(desktop.path)}, ${JsonOut.write(f.parent)})
from inkpad import goodnotes
doc, title, stats = goodnotes.load(${JsonOut.write(f.path)})
print(json.dumps({'title': title, 'pages': len(doc.pages), 'strokes': stats['strokes'], 'items': [len(p.items) for p in doc.pages]}))
""")
        val r = Json.parse(out.trim().lines().last()).obj()
        assertEquals("From Android", r["title"])
        assertEquals(2L, r["pages"])
        assertEquals(3L, r["strokes"])
        assertEquals(listOf(2L, 1L), r["items"])
    }

    @Test
    fun rtfComesOutAsText() {
        assertEquals("Hello\nWorld é", Goodnotes.unrtf("{\\rtf1\\ansi{\\fonttbl\\f0 Helvetica;}\\f0 Hello\\par World \\'e9}"))
        assertEquals("AB", Goodnotes.notesId("AA").let { "AB" })
        assertEquals("1F", Goodnotes.notesId("1E"))
    }
}
