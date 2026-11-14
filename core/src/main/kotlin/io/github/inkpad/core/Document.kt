package io.github.inkpad.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// The document: pages, undo history, item-by-item merging and the .inkpad file format.
// Translated from B:model.py Document, merge, notebook_id, set_notebook_id (W:src/core/document.js).

const val GONE_LIMIT = 20000

/** The file is no longer the one that the document was read from or last written to. */
class Changed(path: String) : Exception(path)

/** Taken by whatever replaces a notebook file: an editor saving it, and a sync putting another
 *  device's version in its place. Without it either could write over what the other had only
 *  just written. (Reentrant, as B's RLock is.) */
val fileLock = ReentrantLock()

/** What tells one writing of a file from the next, or null when there is no file. */
fun fileSign(file: File): String? = try {
    val p = file.toPath()
    "${Files.getLastModifiedTime(p).to(TimeUnit.NANOSECONDS)}/${Files.size(p)}"
} catch (e: java.io.IOException) {
    null
}

private fun digest(text: String) = Hash.md5(text.toByteArray(Charsets.UTF_8)).substring(0, 16)

/** Python's sorted(set)[-LIMIT:]: sorted by string value, the end of the list. */
private fun lastSorted(s: Set<String>): List<String> {
    val a = s.sorted()
    return if (a.size > GONE_LIMIT) a.subList(a.size - GONE_LIMIT, a.size) else a
}

sealed class Event {
    /** Items appended to the end of a page. */
    class Added(val page: Page, val items: List<Item>) : Event()
    /** Anything else on one page; rect null means all of it. */
    class Dirty(val page: Page, val rect: Box?) : Event()
    /** The list of pages or their settings. */
    object Pages : Event()
    /** Bookmarks, outline, cover. */
    object Meta : Event()
    /** The undo or saved state may have changed. */
    object State : Event()
}

/** One step of undo. Index lists are (index, thing) pairs. */
sealed class Action {
    class Items(val page: Page, val removed: List<Pair<Int, Item>>, val added: List<Pair<Int, Item>>) : Action()
    class Pages(val removed: List<Pair<Int, Page>>, val added: List<Pair<Int, Page>>) : Action()
    class Retemplate(val page: Page, val old: Template, val new: Template) : Action()
}

/** Everything needed to write the file, as data that further editing does not change. */
class Snapshot(
    val json: String,
    val pages: LinkedHashMap<String, String>,
    val pdfs: List<Source>,
    val images: List<Source>,
    val cover: ByteArray,
    val rev: Int,
    val id: String,
)

class Document(template: Template? = null) {
    var pages = arrayListOf(if (template != null) Page(template.w, template.h, template.paper, color = template.color) else Page())
    var file: File? = null
    var rev = 0
    var savedRev = 0
    val undoStack = ArrayList<Action>()
    val redoStack = ArrayList<Action>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Event) -> Unit>()
    private var coverPng: ByteArray? = null
    var id: String = Ids.hex()                 // stable across renames and moves; what sync tracks
    var gonePages = HashSet<String>()          // ids of pages removed from the document
    var coverSpec: Pair<String, Color>? = null // (style, colour) of a designed cover; null shows the first page
    var sign: String? = null                   // fileSign() of the file as this document last read or wrote it
    var orderMt = 0.0                          // when the pages were last put in another order
    private var order: List<String>? = null    // ...and the order they were in when that was last looked at

    val modified get() = rev != savedRev
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Returns a function that stops listening. */
    fun listen(fn: (Event) -> Unit): () -> Unit {
        listeners.add(fn)
        return { listeners.remove(fn) }
    }

    fun emit(e: Event) {
        if (e is Event.Pages || (e is Event.Added && pages.firstOrNull() === e.page) || (e is Event.Dirty && pages.firstOrNull() === e.page)) {
            coverPng = null
        }
        for (fn in listeners) fn(e)
    }

    /** Mark a change that is not on the undo stack (bookmarks, outline, a lifted tape). */
    fun touch() {
        rev += 1
        emit(Event.Meta)
        emit(Event.State)
    }

    // -- undo ---------------------------------------------------------------------------

    fun perform(action: Action) {
        apply(action, true)
        push(action)
    }

    /** Note what an action changed: the page is dirty, and removed items leave tombstones. */
    private fun mark(action: Action, forward: Boolean) {
        when (action) {
            is Action.Items -> {
                val (out, into) = if (forward) Pair(action.removed, action.added) else Pair(action.added, action.removed)
                action.page.rev += 1
                val kept = java.util.Collections.newSetFromMap(IdentityHashMap<Item, Boolean>()).apply { into.forEach { add(it.second) } }
                for ((_, it) in out) if (it !in kept) action.page.gone.add(it.itemId)
                for ((_, it) in into) action.page.gone.remove(it.itemId)
            }
            is Action.Pages -> {
                val (out, into) = if (forward) Pair(action.removed, action.added) else Pair(action.added, action.removed)
                for ((_, page) in out) gonePages.add(page.id)
                for ((_, page) in into) gonePages.remove(page.id)
            }
            is Action.Retemplate -> {}
        }
    }

    /** Record an action whose effect is already applied. */
    fun push(action: Action) {
        mark(action, true)
        undoStack.add(action)
        if (undoStack.size > 300) undoStack.subList(0, undoStack.size - 300).clear()
        redoStack.clear()
        rev += 1
        emit(Event.State)
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        apply(action, false)
        mark(action, false)
        redoStack.add(action)
        rev += 1
        emit(Event.State)
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        apply(action, true)
        mark(action, true)
        undoStack.add(action)
        rev += 1
        emit(Event.State)
    }

    fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun apply(action: Action, forward: Boolean) {
        when (action) {
            is Action.Items -> {
                val (out, into) = if (forward) Pair(action.removed, action.added) else Pair(action.added, action.removed)
                val page = action.page
                val before = page.items.size
                swap(page.items, out, into)
                if (out.isEmpty() && into.isNotEmpty() && into.minOf { it.first } >= before) {
                    emit(Event.Added(page, into.map { it.second }))
                } else {
                    var rect: Box? = null
                    for ((_, it) in out + into) rect = Geom.union(rect, it.bbox)
                    emit(Event.Dirty(page, rect))
                }
            }
            is Action.Pages -> {
                val (out, into) = if (forward) Pair(action.removed, action.added) else Pair(action.added, action.removed)
                swap(pages, out, into)
                emit(Event.Pages)
            }
            is Action.Retemplate -> {
                action.page.applyTemplate(if (forward) action.new else action.old)
                emit(Event.Pages)
                emit(Event.Dirty(action.page, null))
            }
        }
    }

    fun addItems(page: Page, items: List<Item>) {
        val n = page.items.size
        perform(Action.Items(page, emptyList(), items.mapIndexed { i, it -> Pair(n + i, it) }))
    }

    fun removeItems(page: Page, items: List<Item>) {
        val index = IdentityHashMap<Item, Int>().apply { page.items.forEachIndexed { i, it -> put(it, i) } }
        perform(Action.Items(page, items.map { Pair(index[it]!!, it) }, emptyList()))
    }

    fun replaceItems(page: Page, olds: List<Item>, news: List<Item>) {
        val index = IdentityHashMap<Item, Int>().apply { page.items.forEachIndexed { i, it -> put(it, i) } }
        val pairs = olds.zip(news).sortedBy { index[it.first]!! }
        perform(Action.Items(page, pairs.map { Pair(index[it.first]!!, it.first) }, pairs.map { Pair(index[it.first]!!, it.second) }))
    }

    /** Record an edit made directly on page.items, given the list from before it. */
    fun pushDiff(page: Page, before: List<Item>) {
        val now = identitySet(page.items)
        val old = identitySet(before)
        val removed = before.withIndex().filter { it.value !in now }.map { Pair(it.index, it.value) }
        val added = page.items.withIndex().filter { it.value !in old }.map { Pair(it.index, it.value) }
        if (removed.isNotEmpty() || added.isNotEmpty()) push(Action.Items(page, removed, added))
    }

    fun addPages(at: Int, new: List<Page>) = perform(Action.Pages(emptyList(), new.mapIndexed { i, p -> Pair(at + i, p) }))

    fun removePages(gone: List<Page>) {
        val index = IdentityHashMap<Page, Int>().apply { pages.forEachIndexed { i, p -> put(p, i) } }
        perform(Action.Pages(gone.map { Pair(index[it]!!, it) }, emptyList()))
    }

    // -- cover ----------------------------------------------------------------------------

    fun setCover(spec: Pair<String, Color>?) {
        coverSpec = spec
        coverPng = null
        touch()
    }

    fun displayName(): String = file?.nameWithoutExtension ?: tr("未命名笔记")


    /** The cover as PNG: the designed one, or the first page shrunk. */
    fun cover(): ByteArray = coverPng ?: (Platform.covers?.invoke(this) ?: BLANK_PNG).also { coverPng = it }

    // -- file format ----------------------------------------------------------------------
    // The file is a zip:
    //   document.json    the page list (id, size, paper, background, bookmark, ...) and what
    //                    was removed from it
    //   pages/<id>.json  one page's items, each with a permanent id, and the ids of the items
    //                    that were removed from the page
    //   pdf/N.pdf, img/N.png, cover.png, id
    // Pages are separate so that only a changed page is serialised again, and items have ids
    // and leave tombstones so that two versions of a notebook can be merged item by item.

    /** Note the time on the settings of every page that has changed them, and on the order of
     *  the pages if that has changed, since this was last done. When two versions of a notebook
     *  are merged, the later change is the one that stays. */
    fun stamp() {
        val now = Clock.now()
        for (page in pages) {
            val sign = page.settingsSign()
            if (sign != page.sign) {
                page.mt = now
                page.sign = sign
            }
        }
        val ids = pages.map { it.id }
        val was = order
        if (was != null && was != ids) {
            val here = ids.toSet()
            val before = was.toSet()
            if (was.filter { it in here } != ids.filter { it in before }) orderMt = now
        }
        order = ids
    }

    internal fun resetOrder() {
        order = pages.map { it.id }
    }

    fun snapshot(): Snapshot {
        stamp()
        val ctx = SaveCtx()
        val pdfs = ArrayList<PdfData>()
        val slots = HashMap<String, MutableList<Int>>()
        val metaPages = ArrayList<Map<String, Any?>>()
        val blobs = LinkedHashMap<String, String>()
        for (page in pages) {
            val d = linkedMapOf<String, Any?>("id" to page.id, "w" to page.w, "h" to page.h, "paper" to page.paper, "color" to page.color.toList())
            if (page.board) d["board"] = true
            val pdf = page.pdf
            if (pdf != null) {
                // The same PDF is written once. The key only picks out which ones to look at: two
                // are the same when their bytes are.
                val list = slots.getOrPut(pdf.key) { ArrayList() }
                var found = list.firstOrNull { pdfs[it].sameAs(pdf) }
                if (found == null) {
                    found = pdfs.size
                    list.add(found)
                    pdfs.add(pdf)
                }
                d["pdf"] = found
                d["pdf_page"] = page.pdfPage
                d["rot"] = page.rot
            }
            if (page.bookmark) d["bookmark"] = true
            page.outline?.takeIf { it.isNotEmpty() }?.let { d["outline"] = it }
            if (page.mt != 0.0) d["mt"] = page.mt
            metaPages.add(d)
            val hasImage = page.items.any { it is Image }
            val blob = page.blob
            if (blob == null || blob.first != page.rev || hasImage) {
                val items = page.items.map { it.toData(ctx).also { d -> d["id"] = it.itemId } }
                // Tombstones are only needed until every device has seen the removal; cap them.
                page.blob = Pair(page.rev, JsonOut.write(linkedMapOf("items" to items, "gone" to lastSorted(page.gone))))
            }
            blobs[page.id] = page.blob!!.second
        }
        val meta = linkedMapOf<String, Any?>("format" to "inkpad", "version" to 3, "pages" to metaPages, "id" to id, "gone" to lastSorted(gonePages))
        if (orderMt != 0.0) meta["order_mt"] = orderMt
        coverSpec?.let { meta["cover"] = listOf(it.first, it.second.toList()) }
        return Snapshot(JsonOut.write(meta), blobs, pdfs.map { it.source }, ctx.images.map { it.source }, cover(), rev, id)
    }

    /** Whether the file was written by something else (a sync) since this document read it. */
    fun stale(): Boolean {
        val f = file ?: return false
        val s = sign ?: return false
        val now = fileSign(f)
        return now != null && now != s
    }

    /** Take in what something else wrote to the file, keeping what was done here since. */
    fun absorb() {
        val f = file ?: return
        val other = fileLock.withLock {
            load(f).also { sign = it.sign }
        }
        if (merge(this, other).any { it != 0 }) {
            emit(Event.Pages)
            for (page in pages) emit(Event.Dirty(page, null))
        }
        emit(Event.State)
    }

    /** Write the notebook to its file (or to [to], which then becomes its file). Never writes
     *  over what a sync brought: that is folded in first. */
    fun save(to: File? = null) {
        val target = to ?: file ?: error("no file to save to")
        val snap: Snapshot
        fileLock.withLock {
            if (target == file && stale()) {
                try {
                    absorb()   // never write over what a sync brought: fold it in first
                } catch (e: Exception) {
                    // unreadable; this document is the better of the two
                }
            }
            snap = snapshot()
            sign = writeSnapshot(target, snap)
        }
        file = target
        savedRev = snap.rev
        emit(Event.State)
    }

    companion object {
        /** The designed cover in a document.json's meta, or null for the first page. */
        fun coverSpecOf(meta: Map<String, Any?>): Pair<String, Color>? {
            val cover = meta["cover"] ?: return null
            val c = cover.arr()
            // Older files stored a bare colour.
            return if (c[0] is String) Pair(c[0] as String, c[1].point()) else Pair("simple", cover.point())
        }

        /** The designed cover of a notebook file, read without loading the notebook. A library
         *  draws such a cover with the file's present name: the picture stored in the file was
         *  drawn with the name it had then, and a rename (here, or from another device) does
         *  not redraw it. */
        fun coverSpecOf(z: ZipReader): Pair<String, Color>? =
            runCatching { coverSpecOf(Json.parse(z.text("document.json")).obj()) }.getOrNull()

        /** Write the file and return its fileSign(). With [expect], the sign the file had when it
         *  was last read or written, Changed is thrown if something else has written it since. */
        fun writeSnapshot(file: File, snap: Snapshot, expect: String? = null): String = fileLock.withLock {
            if (expect != null && fileSign(file) != expect) throw Changed(file.path)
            write(file, snap)
            fileSign(file)!!
        }

        private fun write(file: File, snap: Snapshot) {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().buffered(1 shl 16).use { out ->
                val z = ZipWriter(out)
                z.add("document.json", snap.json)
                for ((pageId, text) in snap.pages) z.add("pages/$pageId.json", text)
                z.add("id", snap.id, compress = false)
                z.add("cover.png", snap.cover, compress = false)
                snap.pdfs.forEachIndexed { i, s -> z.addStored("pdf/$i.pdf", s) }   // stored: PDFs are already compressed
                snap.images.forEachIndexed { i, s -> z.addStored("img/$i.png", s) }
                z.finish()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /** The file's bytes as one Source, opened once: the PDFs and pictures read from it stay
         *  readable after the file is replaced (an open file outlives its name). */
        fun load(file: File): Document {
            val sign = fileSign(file)
            return load(OpenFile(file)).also {
                it.file = file
                it.sign = sign
            }
        }

        fun load(source: Source): Document {
            val z = ZipReader.open(source)
            val meta = Json.parse(z.text("document.json")).obj()
            if (meta["format"] != "inkpad") throw IllegalArgumentException(tr("不是 Inkpad 文档"))
            // The same PDF or picture stored twice is read once, and so saved once from then on.
            val pdfs = ArrayList<PdfData>()
            val samePdf = HashMap<String, MutableList<PdfData>>()
            while (z.has("pdf/${pdfs.size}.pdf")) {
                val e = z.entry("pdf/${pdfs.size}.pdf")!!
                // A size and checksum seen before is a sign that it may be a second copy; it is one when its content is the same.
                val key = "${e.size}/${e.crc}"
                val fresh = PdfData(z.sourceOf(e.name), key)
                val list = samePdf.getOrPut(key) { ArrayList() }
                pdfs.add(list.firstOrNull { it.sameAs(fresh) } ?: fresh.also { list.add(it) })
            }
            val images = ArrayList<ImageData>()
            val sameImage = HashMap<String, MutableList<ImageData>>()
            while (z.has("img/${images.size}.png")) {
                val e = z.entry("img/${images.size}.png")!!
                val key = "${e.size}/${e.crc}"
                val fresh = ImageData(z.sourceOf(e.name))
                val list = sameImage.getOrPut(key) { ArrayList() }
                images.add(list.firstOrNull { it.sameBytes(fresh) } ?: fresh.also { list.add(it) })
            }
            val metaPages = meta["pages"].arr().map { it.obj() }
            val contents = HashMap<String, Map<String, Any?>>()
            for (d in metaPages) {
                val pid = d["id"] as String? ?: continue
                if (z.has("pages/$pid.json")) contents[pid] = Json.parse(z.text("pages/$pid.json")).obj()
            }
            val doc = Document()
            doc.id = meta["id"] as String? ?: doc.id
            doc.pages = ArrayList()
            for ((n, d) in metaPages.withIndex()) {
                val page = Page(d["w"].num(), d["h"].num(), d["paper"] as String? ?: "blank",
                    color = d["color"]?.point() ?: rgb(1.0, 1.0, 1.0), board = d["board"] == true)
                if ("pdf" in d) {
                    page.pdf = pdfs[d["pdf"].num().toInt()]
                    page.pdfPage = d["pdf_page"].num().toInt()
                    page.rot = d["rot"]?.num()?.toInt() ?: 0
                }
                page.bookmark = d["bookmark"] == true
                page.outline = d["outline"] as String?
                // Files from before version 3 have no ids. They are derived from the content, so
                // that two devices opening the same old file agree on them.
                page.id = d["id"] as String? ?: digest("${doc.id}/$n")
                val content = contents[page.id] ?: mapOf("items" to (d["items"] ?: emptyList<Any?>()))
                for (data in content["items"].arr()) {
                    val m = data.obj()
                    val item = Item.fromData(m, images)
                    // Items of such files get a fresh id (A's way; B's content-derived ones would
                    // need Python's exact float formatting of the item - W PORTING.md R11).
                    item.id = m["id"] as String? ?: Ids.short()
                    page.items.add(item)
                }
                page.gone = HashSet((content["gone"] ?: emptyList<Any?>()).arr().map { it as String })
                page.mt = d["mt"]?.num() ?: 0.0
                page.sign = page.settingsSign()
                doc.pages.add(page)
            }
            if (doc.pages.isEmpty()) doc.pages = arrayListOf(Page())
            doc.gonePages = HashSet((meta["gone"] ?: emptyList<Any?>()).arr().map { it as String })
            doc.orderMt = meta["order_mt"]?.num() ?: 0.0
            doc.resetOrder()
            doc.coverSpec = coverSpecOf(meta)
            return doc
        }

        /** A notebook made of a PDF, given its page sizes (the app reads them). */
        fun fromPdf(pdf: PdfData, sizes: List<Pair<Double, Double>>): Document = Document().also { doc ->
            doc.pages = ArrayList(pdfPages(pdf, sizes))
            doc.rev = 1   // not on disk yet
        }

        fun pdfPages(pdf: PdfData, sizes: List<Pair<Double, Double>>) = sizes.mapIndexed { i, (w, h) -> Page(w, h, pdf = pdf, pdfPage = i) }
    }
}

/** A file held open, so that what was read from it stays readable after it is replaced. */
class OpenFile(file: File) : Source, java.io.Closeable {
    private val raf = java.io.RandomAccessFile(file, "r")

    /** Only for a file read through and done with: one a document loaded from keeps reading it. */
    override fun close() = raf.close()
    override val size: Long = raf.length()

    @Synchronized
    override fun read(offset: Long, len: Int): ByteArray {
        val n = minOf(len.toLong(), size - offset).toInt().coerceAtLeast(0)
        val out = ByteArray(n)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }
}

fun <T> identitySet(of: Collection<T>): MutableSet<T> = java.util.Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).apply { addAll(of) }

/** Take [out] away from [seq], then put [into] back at its indexes (in index order). */
private fun <T> swap(seq: MutableList<T>, out: List<Pair<Int, T>>, into: List<Pair<Int, T>>) {
    val gone = identitySet(out.map { it.second })
    seq.removeAll { it in gone }
    for ((index, obj) in into.sortedBy { it.first }) seq.add(minOf(index, seq.size), obj)
}

/**
 * Fold another version of the same notebook into mine, item by item.
 *
 * A page or an item that either side removed stays removed; everything else that either side has
 * is kept. A page's settings (paper, background, bookmark...) are those of the side that changed
 * them last, and so is the order of the pages. Returns what changed in mine: pages added, pages
 * removed, items added, items removed, settings taken over.
 */
fun merge(mine: Document, theirs: Document): IntArray {
    mine.stamp()
    theirs.stamp()
    val gonePages = HashSet(mine.gonePages).apply { addAll(theirs.gonePages) }
    val counts = IntArray(5)
    val byId = mine.pages.associateBy { it.id }
    val other = theirs.pages.associateBy { it.id }
    // The pages are in the order of the side that arranged them last. A page only the other side
    // has goes after the page it follows there.
    val (lead, follow) = if (theirs.orderMt > mine.orderMt) Pair(theirs, mine) else Pair(mine, theirs)
    val order = lead.pages.map { it.id }.toMutableList()
    val have = order.toHashSet()
    var previous: String? = null
    for (page in follow.pages) {
        if (page.id !in have) {
            order.add(if (previous != null && previous in have) order.indexOf(previous) + 1 else order.size, page.id)
            have.add(page.id)
        }
        previous = page.id
    }
    val pages = order.filter { it !in gonePages }.map { byId[it] ?: other[it]!! }
    val was = mine.pages.map { it.id }
    counts[0] = pages.count { it.id !in byId }
    mine.pages = if (pages.isNotEmpty()) ArrayList(pages) else arrayListOf(mine.pages[0])
    val nowIds = mine.pages.map { it.id }.toSet()
    counts[1] = was.count { it !in nowIds }
    if (was.filter { it in nowIds } != mine.pages.filter { it.id in byId }.map { it.id }) counts[4] += 1
    mine.orderMt = maxOf(mine.orderMt, theirs.orderMt)
    mine.resetOrder()
    var changed = false
    for (page in mine.pages) {
        val their = other[page.id]
        if (their == null || their === page) continue
        if (their.mt > page.mt) {
            page.takeSettings(their)
            page.mt = their.mt
            page.sign = page.settingsSign()
            counts[4] += 1
        }
        val gone = HashSet(page.gone).apply { addAll(their.gone) }
        val haveIds = page.items.map { it.itemId }.toHashSet()
        val fresh = their.items.filter { it.itemId !in haveIds && it.itemId !in gone }
        val kept = page.items.filter { it.itemId !in gone }
        counts[2] += fresh.size
        counts[3] += page.items.size - kept.size
        if (fresh.isNotEmpty() || kept.size != page.items.size || gone != page.gone) {
            page.items = ArrayList(kept + fresh)
            page.gone = gone
            page.rev += 1
            changed = true
        }
    }
    if (changed || gonePages != mine.gonePages || counts.any { it != 0 }) {
        mine.gonePages = gonePages
        mine.clearHistory()
        mine.rev += 1
    }
    return counts
}

/** The stable id of a notebook file, giving it one if it predates ids. */
fun notebookId(file: File): String = fileLock.withLock {
    OpenFile(file).use { src ->
        val z = ZipReader.open(src)
        if (z.has("id")) return z.text("id")
        val id = Ids.hex()
        rewrite(file) { w ->
            for (e in z.entries) w.addRaw(e.name, e.method, e.crc, e.size, z.raw(e))
            w.add("id", id, compress = false)
        }
        id
    }
}

/** Give a notebook file a different id (a copy made outside the app shares its original's). */
fun setNotebookId(file: File, id: String) = fileLock.withLock {
    val z = ZipReader.open(OpenFile(file))
    rewrite(file) { w ->
        val done = HashSet<String>()
        for (e in z.entries) {
            if (e.name == "id" || !done.add(e.name)) continue
            val last = z.entry(e.name)!!
            if (e.name == "document.json") {
                val meta = LinkedHashMap(Json.parse(z.text("document.json")).obj())
                meta["id"] = id
                w.add("document.json", JsonOut.write(intsOf(meta)))
            } else {
                w.addRaw(last.name, last.method, last.crc, last.size, z.raw(last))
            }
        }
        w.add("id", id, compress = false)
    }
}

/** JSON read back holds whole numbers as Long; nothing else needs changing to write it again. */
private fun intsOf(v: Any?): Any? = v

private fun rewrite(file: File, fill: (ZipWriter) -> Unit) {
    val tmp = File(file.path + ".tmp")
    tmp.outputStream().buffered(1 shl 16).use { out -> ZipWriter(out).also(fill).finish() }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
