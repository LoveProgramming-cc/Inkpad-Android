package io.github.inkpad.core

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** Drive kept in memory, shared by the "devices" of a test (A:InkpadTests/SyncTests.swift). */
open class FakeDrive(val cloud: Cloud) : Drive {
    class Cloud {
        val files = LinkedHashMap<String, Pair<String, ByteArray>>()
        var n = 0
    }

    override var onBytes: ((Long, Long) -> Unit)? = null
    var uploads = 0
    var downloads = 0

    /** Something that another device does at a chosen moment of this one's sync. */
    var afterIndex: (() -> Unit)? = null
    var afterMd5: (() -> Unit)? = null

    private fun newId(): String {
        cloud.n += 1
        return "file${cloud.n}"
    }

    override fun root() = "root"
    override fun find(name: String, parent: String) = cloud.files.entries.firstOrNull { it.value.first == name }?.key

    override fun getJSON(fileId: String): Map<String, Any?> {
        val file = cloud.files[fileId] ?: throw DriveError("not found", 404)
        val out = Json.parse(file.second.toString(Charsets.UTF_8)).obj()
        afterIndex?.let { then -> afterIndex = null; then() }
        return out
    }

    override fun putJSON(name: String, value: Map<String, Any?>, parent: String, fileId: String?): String {
        val id = fileId ?: newId()
        cloud.files[id] = Pair(cloud.files[id]?.first ?: name, JsonOut.write(value).toByteArray())
        return id
    }

    override fun rename(fileId: String, name: String) {
        cloud.files[fileId] = Pair(name, cloud.files[fileId]!!.second)
    }

    override fun upload(file: File, name: String, parent: String, fileId: String?): Pair<String, String?> {
        val id = fileId ?: newId()
        cloud.files[id] = Pair(cloud.files[id]?.first ?: name, file.readBytes())
        uploads++
        return Pair(id, LibrarySync.md5(file))
    }

    override fun download(fileId: String, dest: File) {
        val file = cloud.files[fileId] ?: throw DriveError("not found", 404)
        dest.writeBytes(file.second)
        downloads++
    }

    override fun alive(fileId: String) = fileId == "root" || fileId.startsWith("folder") || fileId in cloud.files

    override fun md5(fileId: String): String? {
        val out = cloud.files[fileId]?.let { Hash.md5(it.second) }
        afterMd5?.let { then -> afterMd5 = null; then() }
        return out
    }

    override fun children(parent: String) = cloud.files.map { DriveFile(it.key, it.value.first, Hash.md5(it.value.second)) }
}

class SyncTest {
    private lateinit var base: File
    private lateinit var a: File
    private lateinit var b: File
    private val cloud = FakeDrive.Cloud()
    private val template = Template(595.0, 842.0, "blank", rgb(1.0, 1.0, 1.0))

    @BeforeTest
    fun setUp() {
        base = tempDir()
        a = File(base, "a").apply { mkdirs() }
        b = File(base, "b").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
        LibrarySync.blobMin = 32 * 1024L
        LibrarySync.checkpoint = 10.0
    }

    private fun sync(root: File) = LibrarySync.sync(root, FakeDrive(cloud))
    private fun books(root: File) = LibrarySync.scan(root).first.keys.sorted()

    private fun addStroke(f: File, x: Double): String {
        val doc = Document.load(f)
        val stroke = Stroke("ball", black, 1.0, listOf(pt(x, 0.0), pt(x, 50.0)))
        doc.addItems(doc.pages[0], listOf(stroke))
        doc.save()
        return stroke.itemId
    }

    private fun indexOf(c: FakeDrive.Cloud) = Json.parse(c.files.values.first { it.first == LibrarySync.INDEX_NAME }.second.toString(Charsets.UTF_8)).obj()

    @Test
    fun twoDevices() {
        val storeA = Store(a)
        val storeB = Store(b)
        val folder = storeA.newFolder(storeA.root, "课程")
        val note = storeA.newNotebook(folder, template, name = "数学")
        storeA.newFolder(storeA.root, "空文件夹")

        var s = sync(a)
        assertEquals(1, s.up)
        s = sync(b)
        assertEquals(1, s.down)
        assertEquals(listOf("课程/数学.inkpad"), books(b))
        assertTrue(File(b, "空文件夹").isDirectory)
        // Nothing to do when nothing changed.
        assertEquals(SyncSummary(), sync(a))
        assertEquals(SyncSummary(), sync(b))

        // An edit on one side travels to the other.
        val noteB = File(b, "课程/数学.inkpad")
        val first = addStroke(noteB, 10.0)
        assertEquals(1, sync(b).up)
        assertEquals(1, sync(a).down)
        assertEquals(listOf(first), Document.load(note).pages[0].items.map { it.id })

        // Edits on both sides are merged item by item.
        val mine = addStroke(note, 20.0)
        val theirs = addStroke(noteB, 30.0)
        assertEquals(1, sync(a).up)
        s = sync(b)
        assertEquals(1, s.merged)
        assertEquals(0, s.conflicts)
        assertEquals(setOf(first, mine, theirs), Document.load(noteB).pages[0].items.map { it.id }.toSet())
        assertEquals(1, sync(a).down)
        assertEquals(setOf(first, mine, theirs), Document.load(note).pages[0].items.map { it.id }.toSet())
        assertEquals(listOf("课程/数学.inkpad"), books(a))

        // A rename is a changed path on the same id, not a new notebook.
        storeB.rename(noteB, "高等数学")
        s = sync(b)
        assertEquals(1, s.moved)
        assertEquals(0, s.up)
        assertEquals(1, sync(a).moved)
        assertEquals(listOf("课程/高等数学.inkpad"), books(a))

        // A deletion reaches the other device as a move to its trash.
        storeA.trash(File(a, "课程/高等数学.inkpad"))
        sync(a)
        sync(b)
        assertEquals(emptyList(), books(b))
        assertEquals(listOf("高等数学"), storeB.trashed().map { it.name })

        // Restoring it brings it back on both (to the top level: this device never knew its folder).
        storeB.restore(storeB.trashed()[0].file)
        sync(b)
        sync(a)
        assertEquals(listOf("高等数学.inkpad"), books(a))
        assertEquals(0, storeA.trashed().size)

        // A removed empty folder goes away on the other side as well.
        File(a, "空文件夹").delete()
        sync(a)
        sync(b)
        assertFalse(File(b, "空文件夹").exists())

        // Favourites travel with the newer library metadata.
        storeA.toggleFavorite(File(a, "高等数学.inkpad"))
        sync(a)
        assertTrue(sync(b).meta)
        storeB.reloadMeta()
        assertEquals(listOf("高等数学"), storeB.favorites().map { it.name })
    }

    @Test
    fun archiveAndCloudRestore() {
        val store = Store(a)
        val folder = store.newFolder(store.root, "课程")
        store.setFolderStyle(folder, FOLDER_COLORS[3], "book")
        store.newNotebook(folder, template, name = "数学")
        val loose = store.newNotebook(store.root, template, name = "散页")
        store.newFolder(folder, "空")

        // The whole library into one zip, and out again somewhere else.
        val zip = File(base, "library.zip")
        assertEquals(2, store.exportArchive(store.root, zip))
        val other = Store(b)
        assertEquals(2, other.importArchive(zip, other.root))
        assertEquals(listOf("散页.inkpad", "课程/数学.inkpad"), books(b))
        assertTrue(File(b, "课程/空").isDirectory)
        assertEquals("book", other.folderStyle(File(b, "课程")).second)
        assertEquals(2, other.importArchive(zip, other.root), "importing again never overwrites")
        assertEquals(4, books(b).size)

        // Deleted for good here, the file stays on Drive and can be fetched back.
        sync(a)
        val id = notebookId(loose)
        store.trash(loose)
        store.delete(store.trashed()[0].file)
        assertEquals(listOf("散页"), store.archived().map { Store.archivedName(it.name) })
        sync(a)
        val gone = LibrarySync.cloudDeleted(a, FakeDrive(cloud))
        assertEquals(listOf(id), gone.map { it.id })
        assertEquals("散页.inkpad", gone.first().path)
        assertEquals(1, LibrarySync.cloudRestore(a, FakeDrive(cloud), listOf(id)))
        assertEquals(listOf("散页.inkpad", "课程/数学.inkpad"), books(a))
        assertTrue(LibrarySync.cloudDeleted(a, FakeDrive(cloud)).isEmpty())
        val s = sync(a)
        assertEquals(0, s.up + s.down + s.removed, "restored on both sides: nothing left to do")

        // And the copy kept on the device comes back to where it was.
        store.unarchive(store.archived()[0].file)
        assertEquals(listOf("散页 2.inkpad", "散页.inkpad", "课程/数学.inkpad"), books(a))
    }

    /** A notebook's file deleted on Drive itself: the index still lists it, but it is gone. It is
     *  forgotten, and sent again by the device that still has it. */
    @Test
    fun fileGoneFromDriveIsSentAgain() {
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "数学")
        sync(a)
        sync(b)
        val nid = notebookId(note)
        val file = indexOf(cloud)["items"].obj()[nid].obj()["file"] as String
        cloud.files.remove(file)

        // A quick sync goes by the index and does not look; a thorough one finds out.
        assertEquals(SyncSummary(), sync(b))
        val s = LibrarySync.sync(b, FakeDrive(cloud), thorough = true)
        assertEquals(1, s.up)
        assertEquals(0, s.removed, "not taken for a deletion")
        assertEquals(listOf("数学.inkpad"), books(b))
        val s2 = sync(a)
        assertEquals(0, s2.up + s2.removed)
        assertEquals(listOf("数学.inkpad"), books(a))
    }

    /** A sync that is cut off has written down how far it got: the next one, on any device, goes
     *  on from there. */
    @Test
    fun checkpoint() {
        class Failing(c: FakeDrive.Cloud) : FakeDrive(c) {
            override fun upload(file: File, name: String, parent: String, fileId: String?): Pair<String, String?> {
                if (uploads == 2) throw DriveError("the connection dropped")
                return super.upload(file, name, parent, fileId)
            }
        }
        val store = Store(a)
        for (name in listOf("一", "二", "三")) store.newNotebook(store.root, template, name = name)
        LibrarySync.checkpoint = 0.0
        assertFailsWith<DriveError> { LibrarySync.sync(a, Failing(cloud)) }
        assertEquals(2, indexOf(cloud)["items"].obj().size, "the two that went up are in the index already")
        assertEquals(2, sync(b).down)
        val rest = FakeDrive(cloud)
        val s = LibrarySync.sync(a, rest)
        assertEquals(1, rest.uploads, "only what was left")
        assertEquals(1, s.up)
    }

    /** Asked to sync with another folder, the device forgets what it knew of the old one and
     *  compares everything again; a folder it cannot see is refused. */
    @Test
    fun changingTheFolder() {
        val store = Store(a)
        store.newNotebook(store.root, template, name = "数学")
        sync(a)
        assertEquals("root", LibrarySync.cloudFolder(a))
        val s = LibrarySync.sync(a, FakeDrive(cloud), folder = "folder2")
        assertEquals("folder2", LibrarySync.cloudFolder(a))
        assertEquals(0, s.removed)
        assertEquals(listOf("数学.inkpad"), books(a))
        assertFailsWith<DriveError> { LibrarySync.sync(a, FakeDrive(cloud), folder = "missing") }
        assertEquals(listOf("数学.inkpad"), books(a))

        assertEquals("1AbC-d_9", LibrarySync.parseFolder(" https://drive.google.com/drive/folders/1AbC-d_9?usp=sharing "))
        assertEquals("xyz123", LibrarySync.parseFolder("https://drive.google.com/open?id=xyz123&x=1"))
        assertEquals("1AbC", LibrarySync.parseFolder("1AbC"))
        assertEquals("", LibrarySync.parseFolder(""))
        assertFailsWith<DriveError> { LibrarySync.parseFolder("my folder!") }
    }

    @Test
    fun movesCanBeUndone() {
        val store = Store(a)
        val folder = store.newFolder(store.root, "课程")
        val inner = store.newFolder(folder, "作业")
        val note = store.newNotebook(store.root, template, name = "数学")
        assertFailsWith<StoreError>("a folder cannot go inside itself") { store.move(folder, inner) }
        store.newNotebook(folder, template, name = "数学")
        val moved = store.move(note, folder)
        assertEquals("数学 2.inkpad", moved.name)
        // Taken back, it has the name it had.
        val back = store.move(moved, store.root, name = "数学")
        assertEquals(note.canonicalPath, back.canonicalPath)
        assertEquals(listOf(".", "课程", "课程/作业"), store.folders().map { store.rel(it) })
    }

    @Test
    fun copyGetsItsOwnId() {
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "原件")
        sync(a)
        note.copyTo(File(a, "拷贝.inkpad"))
        assertEquals(1, sync(a).up)
        assertNotEquals(notebookId(note), notebookId(File(a, "拷贝.inkpad")))
        sync(b)
        assertEquals(listOf("原件.inkpad", "拷贝.inkpad"), books(b))
    }

    /** A new page with a stroke on it, and a stroke on the first page. */
    private fun addPage(f: File, x: Double) {
        val doc = Document.load(f)
        val page = Page()
        doc.addPages(doc.pages.size, listOf(page))
        doc.addItems(page, listOf(Stroke("ball", black, 1.0, listOf(pt(x, 0.0), pt(x, 50.0)))))
        doc.addItems(doc.pages[0], listOf(Stroke("ball", black, 1.0, listOf(pt(x + 1, 0.0), pt(x + 1, 50.0)))))
        doc.save()
    }

    private fun shape(f: File) = Document.load(f).pages.map { it.items.size }

    /** Two devices each add a page and write on the first one, and their syncs overlap: one has
     *  read the index, or is about to upload, when the other syncs from start to end. */
    @Test
    fun overlappingSyncsLoseNothing() {
        for (moment in listOf("index", "md5")) {
            val c = FakeDrive.Cloud()
            val a = File(base, "a-$moment").apply { mkdirs() }
            val b = File(base, "b-$moment").apply { mkdirs() }
            val store = Store(a)
            val mine = store.newNotebook(store.root, template, name = "同一本")
            LibrarySync.sync(a, FakeDrive(c))
            LibrarySync.sync(b, FakeDrive(c))
            val theirs = File(b, "同一本.inkpad")
            addPage(mine, 10.0)
            addPage(theirs, 20.0)

            val slow = FakeDrive(c)
            val other = { LibrarySync.sync(b, FakeDrive(c)); Unit }
            if (moment == "index") slow.afterIndex = other else slow.afterMd5 = other
            LibrarySync.sync(a, slow)
            repeat(3) {
                LibrarySync.sync(a, FakeDrive(c))
                LibrarySync.sync(b, FakeDrive(c))
            }
            assertEquals(listOf(2, 1, 1), shape(mine), moment)
            assertEquals(listOf(2, 1, 1), shape(theirs), moment)
            assertEquals(SyncSummary(), LibrarySync.sync(a, FakeDrive(c)), moment)
        }
    }

    /** The editor never writes over what a sync put in the file. */
    @Test
    fun saveAfterTheFileChanged() {
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "打开着")
        val open = Document.load(note)
        val sign = fileSign(note)
        Thread.sleep(5)
        addStroke(note, 5.0)   // what a sync brings
        assertNotEquals(sign, fileSign(note))
        open.addItems(open.pages[0], listOf(Stroke("ball", black, 1.0, listOf(pt(9.0, 0.0), pt(9.0, 50.0)))))
        assertFailsWith<Changed> { Document.writeSnapshot(note, open.snapshot(), sign) }
        merge(open, Document.load(note))
        Document.writeSnapshot(note, open.snapshot(), fileSign(note))
        assertEquals(listOf(2), shape(note))
    }

    /** The open notebook is synced by itself: nothing else is sent, and the index is only written
     *  when something changed. */
    @Test
    fun syncOfOneNotebook() {
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "打开着")
        val other = store.newNotebook(store.root, template, name = "别的")
        sync(a)
        sync(b)
        val theirs = File(b, "打开着.inkpad")
        addStroke(note, 1.0)
        addStroke(other, 2.0)
        addStroke(theirs, 3.0)

        assertEquals(1, LibrarySync.sync(a, FakeDrive(cloud), only = listOf(note)).up)
        assertEquals(1, LibrarySync.sync(b, FakeDrive(cloud), only = listOf(theirs)).merged)
        assertEquals(listOf(2), shape(theirs))
        assertEquals(listOf(0), shape(File(b, "别的.inkpad")))
        LibrarySync.sync(a, FakeDrive(cloud), only = listOf(note))
        assertEquals(listOf(2), shape(note))
        val index = cloud.files.values.first { it.first == LibrarySync.INDEX_NAME }.second
        assertEquals(SyncSummary(), LibrarySync.sync(a, FakeDrive(cloud), only = listOf(note)))
        assertContentEquals(index, cloud.files.values.first { it.first == LibrarySync.INDEX_NAME }.second)
        // The rest goes with the next sync of the whole library.
        assertEquals(1, sync(a).up)
        sync(b)
        assertEquals(listOf(1), shape(File(b, "别的.inkpad")))
    }

    /** A notebook's PDFs and pictures are uploaded once, as files of their own; after that a sync
     *  sends the ink only. The other device puts the notebook together again. */
    @Test
    fun largePartsAreUploadedOnce() {
        LibrarySync.blobMin = 16
        val note = File(a, "带图.inkpad")
        DocumentTest().sampleDocument().save(note)
        DocumentTest().sampleDocument().save(File(a, "同一张图.inkpad"))
        val picture = ZipReader.open(note.source()).bytes("img/0.png")
        sync(a)
        val blobs = cloud.files.filter { it.value.first.startsWith("blob-") }
        assertEquals(1, blobs.size, "the same picture in two notebooks is one file")
        assertContentEquals(picture, blobs.values.first().second)
        val uploaded = cloud.files.values.first { it.first == "带图.inkpad" }.second
        assertFalse(ZipReader.open(uploaded.source()).has("img/0.png"))

        sync(b)
        val theirs = File(b, "带图.inkpad")
        assertContentEquals(picture, ZipReader.open(theirs.source()).bytes("img/0.png"))
        assertEquals(Document.load(note).pages[0].items.size, Document.load(theirs).pages[0].items.size)

        // Writing on both sides: merged, and the picture is neither sent nor fetched again.
        val before = cloud.files.size
        addStroke(note, 500.0)
        addStroke(theirs, 510.0)
        val driveA = FakeDrive(cloud)
        val driveB = FakeDrive(cloud)
        LibrarySync.sync(a, driveA)
        assertEquals(1, LibrarySync.sync(b, driveB).merged)
        LibrarySync.sync(a, driveA)
        assertEquals(before, cloud.files.size)
        assertEquals(2, driveA.uploads + driveB.uploads)
        assertEquals(2, driveA.downloads + driveB.downloads)
        assertContentEquals(picture, ZipReader.open(note.source()).bytes("img/0.png"))
        assertEquals(Document.load(theirs).pages[0].items.size, Document.load(note).pages[0].items.size)
        assertEquals(SyncSummary(), sync(a))
        assertFalse(File(a, ".sync").exists())
    }

    /** One device moves a page, changes a page's paper and appends a PDF that the notebook has
     *  already; the other writes meanwhile. All of it is there afterwards, on both. */
    @Test
    fun orderPaperAndPdfsSurviveAMerge() {
        val pdfBytes = "%PDF-1.4\n% a stand-in: sync never looks inside\n%%EOF\n".toByteArray()
        val note = File(a, "课本.inkpad")
        val made = Document.fromPdf(PdfData(pdfBytes.source()), listOf(Pair(300.0, 400.0)))
        made.addPages(1, listOf(Page(), Page()))
        made.save(note)
        sync(a)
        sync(b)
        val theirs = File(b, "课本.inkpad")

        val doc = Document.load(note)
        val ids = doc.pages.map { it.id }
        val first = doc.pages[0]
        doc.perform(Action.Pages(listOf(Pair(0, first)), listOf(Pair(2, first))))
        doc.perform(Action.Retemplate(doc.pages[0], doc.pages[0].template(), Template(595.0, 842.0, "grid", rgb(1.0, 1.0, 1.0))))
        doc.addPages(3, Document.pdfPages(PdfData(pdfBytes.copyOf().source()), listOf(Pair(300.0, 400.0))))
        doc.save()
        assertEquals(listOf("pdf/0.pdf"), ZipReader.open(note.source()).names.filter { it.startsWith("pdf/") }, "the same PDF is kept once")
        addStroke(theirs, 77.0)   // on the PDF page, which is first over there

        sync(a)
        assertEquals(1, sync(b).merged)
        sync(a)
        for (f in listOf(note, theirs)) {
            val now = Document.load(f)
            assertEquals(listOf(ids[1], ids[2], ids[0]), now.pages.map { it.id }.take(3), f.path)
            assertEquals(4, now.pages.size)
            assertEquals("grid", now.pages[0].paper)
            assertEquals(1, now.pages[2].items.size, "what was written while the page was being moved")
            assertNotNull(now.pages[2].pdf)
            assertSame(now.pages[2].pdf, now.pages[3].pdf)
        }
    }

    /** A notebook left in the library without its PDFs and pictures is made whole from the cloud,
     *  and never uploaded as it is. */
    @Test
    fun notebookWithoutItsPicturesIsMended() {
        LibrarySync.blobMin = 16
        val note = File(a, "带图.inkpad")
        DocumentTest().sampleDocument().save(note)
        val picture = ZipReader.open(note.source()).bytes("img/0.png")
        sync(a)
        sync(b)
        val theirs = File(b, "带图.inkpad")
        // What is on Drive for it is the notebook without the picture; put that in its place.
        theirs.writeBytes(cloud.files.values.first { it.first == "带图.inkpad" }.second)
        assertTrue(LibrarySync.stripped(theirs))

        val s = LibrarySync.sync(b, FakeDrive(cloud))
        assertEquals(1, s.mended)
        assertFalse(LibrarySync.stripped(theirs))
        assertContentEquals(picture, ZipReader.open(theirs.source()).bytes("img/0.png"))
        assertFalse(LibrarySync.stripped(note))
        val now = cloud.files.values.first { it.first == "带图.inkpad" }.second

        // With the picture gone from the cloud too it cannot be mended: it is left alone, the sync
        // says so, and what is in the cloud is not replaced by it.
        theirs.writeBytes(now)
        cloud.files.keys.filter { cloud.files[it]!!.first.startsWith("blob-") }.forEach { cloud.files.remove(it) }
        try {
            LibrarySync.sync(b, FakeDrive(cloud), thorough = true)
            fail("should have said that it could not be completed")
        } catch (e: DriveError) {
            assertTrue("带图" in e.message!!, e.message)
        }
        assertContentEquals(now, cloud.files.values.first { it.first == "带图.inkpad" }.second)
        // The device that has it whole (and sent it) puts the picture back when it next looks at
        // Drive, and then the other is mended.
        sync(a)
        LibrarySync.sync(a, FakeDrive(cloud), thorough = true)
        assertEquals(1, cloud.files.values.count { it.first.startsWith("blob-sha256-") })
        assertEquals(1, sync(b).mended)
        assertContentEquals(picture, ZipReader.open(theirs.source()).bytes("img/0.png"))
    }

    /** A quick sync with nothing to do reads the index and no more. */
    @Test
    fun quickSyncIsOneRequest() {
        class Counting(c: FakeDrive.Cloud) : FakeDrive(c) {
            val calls = ArrayList<String>()
            override fun root() = super.root().also { calls.add("root") }
            override fun find(name: String, parent: String) = super.find(name, parent).also { calls.add("find") }
            override fun getJSON(fileId: String) = super.getJSON(fileId).also { calls.add("getJSON") }
            override fun putJSON(name: String, value: Map<String, Any?>, parent: String, fileId: String?) =
                super.putJSON(name, value, parent, fileId).also { calls.add("putJSON") }
            override fun rename(fileId: String, name: String) = super.rename(fileId, name).also { calls.add("rename") }
            override fun upload(file: File, name: String, parent: String, fileId: String?) =
                super.upload(file, name, parent, fileId).also { calls.add("upload") }
            override fun download(fileId: String, dest: File) = super.download(fileId, dest).also { calls.add("download") }
            override fun children(parent: String) = super.children(parent).also { calls.add("children") }
            override fun alive(fileId: String) = super.alive(fileId).also { calls.add("alive") }
            override fun md5(fileId: String) = super.md5(fileId).also { calls.add("md5") }
        }
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "一")
        store.newNotebook(store.root, template, name = "二")
        sync(a)
        var drive = Counting(cloud)
        assertEquals(SyncSummary(), LibrarySync.sync(a, drive))
        assertEquals(listOf("getJSON"), drive.calls)
        // Writing in one sends that one: the index is read, the notebook uploaded, the index read
        // again and written.
        addStroke(note, 1.0)
        drive = Counting(cloud)
        assertEquals(1, LibrarySync.sync(a, drive).up)
        assertEquals(listOf("getJSON", "upload", "getJSON", "putJSON"), drive.calls)
        drive = Counting(cloud)
        LibrarySync.sync(a, drive, thorough = true)
        assertEquals(listOf("alive", "children", "getJSON"), drive.calls)
    }

    @Test
    fun skippedNotebookIsLeftAlone() {
        val store = Store(a)
        val note = store.newNotebook(store.root, template, name = "编辑中")
        assertEquals(0, LibrarySync.sync(a, FakeDrive(cloud), skip = listOf(note)).up)
        assertEquals(1, sync(a).up)
    }
}
