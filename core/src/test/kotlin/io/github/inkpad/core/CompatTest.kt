package io.github.inkpad.core

import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drive as a folder on disk, so that this app and the desktop app's own Python code can sync with
 *  each other in a test (A:InkpadTests/CompatTests.swift). core/src/test/python/compat.py holds the
 *  other half. */
class DirDrive(private val dir: File) : Drive {
    override var onBytes: ((Long, Long) -> Unit)? = null

    init {
        dir.mkdirs()
    }

    private val namesFile get() = File(dir, "names.json")
    private var names: Map<String, Any?>
        get() = loadJson(namesFile)
        set(v) = saveJson(namesFile, v)

    private fun file(id: String) = File(dir, "$id.bin")
    private fun newId() = "f" + UUID.randomUUID().toString().take(8)

    override fun root() = "root"
    override fun alive(fileId: String) = fileId == "root" || file(fileId).exists()
    override fun children(parent: String) = names.map { DriveFile(it.key, it.value as String, md5(it.key)) }
    override fun md5(fileId: String) = if (file(fileId).exists()) LibrarySync.md5(file(fileId)) else null
    override fun find(name: String, parent: String) = names.entries.firstOrNull { it.value == name }?.key
    override fun getJSON(fileId: String) = loadJson(file(fileId))

    override fun putJSON(name: String, value: Map<String, Any?>, parent: String, fileId: String?): String {
        val id = fileId ?: newId()
        saveJson(file(id), value)
        if (fileId == null) names = names + (id to name)
        return id
    }

    override fun rename(fileId: String, name: String) {
        names = names + (fileId to name)
    }

    override fun upload(file: File, name: String, parent: String, fileId: String?): Pair<String, String?> {
        val id = fileId ?: newId()
        file.copyTo(file(id), overwrite = true)
        if (fileId == null) names = names + (id to name)
        return Pair(id, LibrarySync.md5(file(id)))
    }

    override fun download(fileId: String, dest: File) {
        file(fileId).copyTo(dest, overwrite = true)
    }
}

class CompatTest {
    @AfterTest
    fun restore() {
        LibrarySync.blobMin = 32 * 1024L
    }

    /** This app makes a library and syncs it up; the desktop app's own code syncs it down, checks
     *  it, edits it and syncs back; this app syncs again and checks what the desktop code did. */
    @Test
    fun syncWithTheDesktopAppsOwnCode() {
        val desktop = File(System.getProperty("inkpad.root", ".."), "ref/B")
        if (!File(desktop, "inkpad/gdrive.py").exists()) return   // the desktop app is not checked out in ref/B
        val base = tempDir()
        // Small enough for the sample's picture to travel as a file of its own, as a large PDF does.
        LibrarySync.blobMin = 16
        val root = File(base, "android").apply { mkdirs() }
        val cloud = File(base, "cloud")
        val store = Store(root)
        val notes = File(root, "课程/笔记.inkpad")

        val folder = store.newFolder(store.root, "课程")
        val doc = DocumentTest().sampleDocument()
        doc.coverSpec = Pair("label", COVER_COLORS[3])
        doc.save(File(folder, "笔记.inkpad"))
        store.newNotebook(store.root, Template(595.0, 842.0, "dots", rgb(1.0, 1.0, 1.0)), board = true, name = "白板")
        store.newNotebook(store.root, Template(595.0, 842.0, "blank", rgb(1.0, 1.0, 1.0)), name = "将被删除")
        store.toggleFavorite(notes)
        assertEquals(3, LibrarySync.sync(root, DirDrive(cloud)).up)
        File(base, "ios-items.txt").writeText(doc.pages[0].items.joinToString("\n") { it.itemId })

        val out = py(File(testPython, "compat.py").path, desktop.path, base.path)
        assertTrue("desktop side ok" in out, out)

        // The desktop side added a stroke to the notebook, renamed the board, deleted one notebook
        // and made one of its own. Meanwhile this side writes in the notebook too.
        val mine = Stroke("ball", black, 1.2, listOf(pt(300.0, 700.0), pt(400.0, 720.0)))
        val open = Document.load(notes)
        open.addItems(open.pages[0], listOf(mine))
        open.save()
        val s = LibrarySync.sync(root, DirDrive(cloud))
        assertEquals(1, s.merged, "edited on both sides: merged item by item")
        assertEquals(0, s.conflicts)
        assertEquals(listOf("桌面新建.inkpad", "画板.inkpad", "课程/笔记.inkpad"), LibrarySync.scan(root).first.keys.sorted())
        assertEquals(listOf("将被删除"), store.trashed().map { it.name })

        val merged = Document.load(notes)
        val ids = merged.pages[0].items.map { it.id }.toSet()
        val original = File(base, "ios-items.txt").readText().split("\n")
        val theirs = File(base, "desktop-item.txt").readText().trim()
        // The desktop removed the arrow (the third item) as well.
        assertEquals(original.toSet() - original[2] + mine.itemId + theirs, ids)
        val added = merged.pages[0].items.first { it.id == theirs } as Stroke
        assertEquals("brush", added.tool)
        assertEquals(3, added.pts.size)
        assertTrue(kotlin.math.abs(added.pts[1][0] - 150.5) < 0.01)
        assertEquals(3, merged.pages.size, "the page the desktop added")
        assertEquals("grid", merged.pages[2].paper)

        val made = Document.load(File(root, "桌面新建.inkpad"))
        assertEquals("cornell", made.pages[0].paper)
        assertEquals("来自桌面", (made.pages[0].items.first() as Text).text)
        assertEquals("ellipse", (made.pages[0].items.last() as Shape).shape)

        store.reloadMeta()
        assertEquals(setOf("笔记", "桌面新建"), store.favorites().map { it.name }.toSet())
        // And nothing more to do afterwards.
        val again = LibrarySync.sync(root, DirDrive(cloud))
        assertEquals(0, again.up + again.down + again.merged + again.moved)
    }
}
