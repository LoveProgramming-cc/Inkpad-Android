package io.github.inkpad.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// Google Drive sync for the notebook library: the same protocol as the desktop app (B:gdrive.py),
// translated from A:Sync/Sync.swift. The calls to Drive block; the app runs a sync on a thread of
// its own.
//
// Sync is an index-driven delta merge. Every notebook has a permanent id, and one small index file
// on Drive lists each id with its path, content hash, Drive file and state. A sync downloads that
// index, compares it per id with the library and with what both sides looked like last time,
// moves only the notebooks that differ, then writes the index back. Deleted notebooks stay in the
// index as tombstones, which is how a deletion reaches other devices instead of being undone by
// them; renames and moves are just a changed path on the same id. Nothing is destroyed: deletions
// go to the local trash, and a notebook edited on both sides is merged item by item.
//
// Two devices may well sync at the same moment, and one may be cut off half way and go on much
// later with what it read before. So the index is never taken at its word about a notebook's
// content: what counts is the checksum Drive itself reports for the file, asked for again just
// before the file is replaced. The index is read again before it is written, so that what others
// put in it meanwhile is kept.
//
// A notebook's large PDFs and pictures are not part of the file uploaded for it. They never
// change, and they are nearly all of a large notebook's size, so each is kept on Drive once, as a
// file of its own named after its SHA-256, and the notebook's file lists them.

class DriveError(message: String, val code: Int? = null) : Exception(message)

/** A file in a Drive folder, as much of it as the sync looks at. */
class DriveFile(val id: String, val name: String, val md5: String?, val modified: Double = 0.0)

interface Drive {
    /** Called with (bytes so far, total) during an upload or a download. */
    var onBytes: ((Long, Long) -> Unit)?
    fun root(): String
    fun find(name: String, parent: String): String?
    fun getJSON(fileId: String): Map<String, Any?>
    /** Create or replace a small JSON file; returns its id. */
    fun putJSON(name: String, value: Map<String, Any?>, parent: String, fileId: String?): String
    fun rename(fileId: String, name: String)
    /** Create or replace a file; returns (id, md5 as Drive has it). */
    fun upload(file: File, name: String, parent: String, fileId: String?): Pair<String, String?>
    fun download(fileId: String, dest: File)
    fun children(parent: String): List<DriveFile>
    /** The checksum of a file's content as it is on Drive at this moment. */
    fun md5(fileId: String): String?
    /** Whether a file or folder is still there, and not in the bin. */
    fun alive(fileId: String): Boolean = true
}

data class SyncSummary(
    var up: Int = 0, var down: Int = 0, var conflicts: Int = 0, var merged: Int = 0,
    var removed: Int = 0, var moved: Int = 0,
    var mended: Int = 0,   // notebooks found here without their PDFs and made whole again
    var meta: Boolean = false,
) {
    val quiet get() = this == SyncSummary()
}

class CloudDeleted(val id: String, val path: String, val ts: Double)

// -- JSON trees -------------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
internal fun deepCopy(v: Any?): Any? = when (v) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> for ((k, x) in v) m[k as String] = deepCopy(x) }
    is List<*> -> ArrayList<Any?>(v.map { deepCopy(it) })
    else -> v
}

/** Equal as JSON: numbers by value, whatever their type. */
internal fun jsonEq(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    a is Map<*, *> && b is Map<*, *> -> a.size == b.size && a.all { (k, v) -> b.containsKey(k) && jsonEq(v, b[k]) }
    a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { jsonEq(a[it], b[it]) }
    else -> a == b
}

/** A deep copy of a JSON tree, for code outside the core. */
fun deepCopyJson(v: Any?): Any? = deepCopy(v)

internal fun numOf(v: Any?, default: Double = 0.0): Double = (v as? Number)?.toDouble() ?: default

@Suppress("UNCHECKED_CAST")
internal fun mapOfJson(v: Any?): MutableMap<String, Any?> = (v as? MutableMap<String, Any?>) ?: LinkedHashMap()

object LibrarySync {
    const val ROOT_NAME = "Inkpad"
    const val INDEX_NAME = "_inkpad_index.json"
    @Volatile var checkpoint = 10.0       // seconds of transfers after which a sync notes, in the index, how far it has got
    @Volatile var blobMin = 32 * 1024L    // a PDF or picture at least this big is kept on Drive as a file of its own
    const val BLOBS_NAME = "blobs.json"   // in an uploaded notebook: {name in the zip: name of the file on Drive}
    const val FORMAT = 3                  // of the index. 2: notebooks are uploaded without their blobs; 3: blobs named by SHA-256
    @Volatile var thoroughEvery = 6 * 3600.0   // seconds after which a sync checks the index against Drive unasked
    const val SHA_PREFIX = "blob-sha256-"

    /** The id of the Drive folder to sync with, checked to be there. [wanted] is the folder the
     *  user asked for, or "" for one named Inkpad at the top of their Drive. When it is not the
     *  folder this device synced with before, what was remembered about that one is dropped, so
     *  that the library is compared with the new folder from nothing. */
    fun cloudFolder(state: MutableMap<String, Any?>, drive: Drive, wanted: String, check: Boolean = true): String {
        if ((state["folder_set"] as? String ?: "") != wanted) {
            state.clear()
            state["folder_set"] = wanted
        } else if (!check) {
            (state["root"] as? String)?.takeIf { it.isNotEmpty() }?.let { return it }   // taken on trust; a thorough sync looks
        }
        if (wanted.isNotEmpty()) {
            val there = try {
                drive.alive(wanted)
            } catch (e: DriveError) {
                if (e.code == 403 || e.code == 404) false else throw e
            }
            if (!there) {
                // With the drive.file permission Google shows the app only what the app itself made.
                throw DriveError(trf("无法使用文件夹 %s：它不存在、在回收站里，或者不是 Inkpad 创建的。Inkpad 只被允许访问它自己创建的文件夹；可以先留空同步一次，把生成的“Inkpad”文件夹在 Google Drive 里移到想放的位置，再把它的 ID 填到其它设备上。", wanted))
            }
            state["root"] = wanted
        } else {
            val saved = state["root"] as? String
            val kept = saved != null && drive.alive(saved)
            if (!kept) state["root"] = drive.root()
        }
        return state["root"] as? String ?: ""
    }

    /** The id of the folder this library last synced with, or "". */
    fun cloudFolder(root: File): String = loadJson(File(root, ".sync.json"))["root"] as? String ?: ""

    /** A folder id out of what was typed: the id itself, or the address of the folder. */
    fun parseFolder(typed: String): String {
        var text = typed.trim()
        for (mark in listOf("/folders/", "id=")) {
            val at = text.indexOf(mark)
            if (at >= 0) text = text.substring(at + mark.length)
        }
        for (stop in listOf("?", "/", "&", "#")) {
            val at = text.indexOf(stop)
            if (at >= 0) text = text.substring(0, at)
        }
        if (text.isNotEmpty() && !text.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            throw DriveError(tr("这不像是 Google Drive 的文件夹 ID"))
        }
        return text
    }

    fun isBlob(e: ZipEntry) = (e.name.startsWith("pdf/") || e.name.startsWith("img/")) && e.size >= blobMin

    // A blob is known by the SHA-256 of its content: two that carry the same name are the same bytes,
    // as far as anyone can tell, and one that is downloaded is checked against its name. (In index
    // format 2 the name was the zip's CRC-32 and the size. Notebooks uploaded then still list those
    // names, and are read; whatever is uploaded now uses the new ones.)
    fun oldName(e: ZipEntry) = "blob-%08x-%d".format(e.crc, e.size)
    fun shaName(data: Source) = SHA_PREFIX + Hash.sha256(data)

    /** What was found of one blob in a notebook the last time: [CRC, size, name of the blob]. */
    private fun named(seen: Map<String, Any?>?, e: ZipEntry): String? {
        val was = seen?.get(e.name) as? List<*> ?: return null
        if (was.size != 3 || numOf(was[0], -1.0).toLong() != e.crc || numOf(was[1], -1.0).toLong() != e.size) return null
        return was[2] as? String
    }

    class Slimmed(val stat: String, val blobs: Map<String, File>, val found: Map<String, Any?>)

    /**
     * Write to [dest] the notebook as it is uploaded: without its large PDFs and pictures, and with
     * a list of them instead. Those for which [have] is false are written next to dest, to be
     * uploaded first. Returns the file's fileSign(), where each such blob was written, and what to
     * pass as `seen` the next time.
     *
     * [seen] is what was found in this notebook the last time: {name in the zip: [CRC, size, name of
     * the blob]}. A PDF that is still in the same place with the same CRC and size is the one that
     * was there then, and is not read through again to name it; so sending a line written in a
     * 200 MB book does not mean reading the book. The file is read once (held open), so all of this
     * is about one version of it even if it is saved again meanwhile.
     */
    fun slim(file: File, dest: File, seen: Map<String, Any?>?, have: (String) -> Boolean): Slimmed {
        val stat = fileSign(file) ?: ""
        val z = ZipReader.open(OpenFile(file))
        val listed = LinkedHashMap<String, Any?>()
        val parts = LinkedHashMap<String, File>()
        val found = LinkedHashMap<String, Any?>()
        dest.outputStream().buffered(1 shl 16).use { out ->
            val w = ZipWriter(out)
            val done = HashSet<String>()
            for (e in z.entries.asReversed()) {
                if (!done.add(e.name) || e.name == BLOBS_NAME) continue
                if (!isBlob(e)) {
                    w.addRaw(e.name, e.method, e.crc, e.size, z.raw(e))
                    continue
                }
                val name = named(seen, e) ?: shaName(z.sourceOf(e.name))
                listed[e.name] = name
                found[e.name] = listOf(e.crc, e.size, name)
                if (name !in parts && !have(name)) {
                    val part = File(dest.path + "." + name)
                    part.outputStream().use { z.sourceOf(e.name).copyTo(it) }
                    parts[name] = part
                }
            }
            if (listed.isNotEmpty()) w.add(BLOBS_NAME, JsonOut.write(listed))
            w.finish()
        }
        return Slimmed(stat, parts, found)
    }

    /** Make a downloaded notebook whole again: its large PDFs and pictures are taken from the copy
     *  of it that is here already ([have]) where that has them, and downloaded with
     *  fetch(name of the blob, where to) otherwise. */
    fun fatten(part: File, have: File?, seen: Map<String, Any?>? = null, fetch: (String, File) -> Unit) {
        val z = ZipReader.open(OpenFile(part))
        if (!z.has(BLOBS_NAME)) return   // uploaded whole, by an earlier version
        val listed = Json.parse(z.text(BLOBS_NAME)).obj()
        val old = have?.let { runCatching { ZipReader.open(OpenFile(it)) }.getOrNull() }
        val mine = old?.entries?.filter(::isBlob) ?: emptyList()
        val byOld = mine.associateBy { oldName(it) }
        var bySha: Map<String, ZipEntry>? = null   // worked out when first asked for: it means reading every one of them
        fun local(blob: String): ZipEntry? {
            if (!blob.startsWith(SHA_PREFIX)) return byOld[blob]
            if (bySha == null) bySha = mine.associateBy { named(seen, it) ?: shaName(old!!.sourceOf(it.name)) }
            return bySha!![blob]
        }
        val full = File(part.path + ".full")
        try {
            full.outputStream().buffered(1 shl 16).use { out ->
                val w = ZipWriter(out)
                for (e in z.entries) if (e.name != BLOBS_NAME) w.addRaw(e.name, e.method, e.crc, e.size, z.raw(e))
                for ((name, blobV) in listed.toSortedMap()) {
                    val blob = blobV as String
                    val e = if (old != null) local(blob) else null
                    if (old != null && e != null) {
                        w.addRaw(name, e.method, e.crc, e.size, old.raw(e))
                    } else {
                        val tmp = File(full.path + ".blob")
                        fetch(blob, tmp)
                        val data = OpenFile(tmp)
                        if (blob.startsWith(SHA_PREFIX) && shaName(data) != blob) {
                            throw DriveError(trf("从云端下载的 PDF 或图片（%s）内容不对，没有使用它。请再同步一次。", blob.take(24)))
                        }
                        // Stored, as the app itself writes them: they are compressed already.
                        w.addStored(name, data)
                        tmp.delete()
                    }
                }
                w.finish()
            }
            replace(full, part)
        } finally {
            full.delete()
        }
    }

    /** Whether a notebook in the library is one as it is uploaded, without its large PDFs and
     *  pictures. That is never meant to be: it is what a download that was cut off left behind,
     *  or what an early version of the apps put in place of the whole notebook. It cannot be
     *  opened, and must not be taken for a notebook that is up to date, or be sent anywhere. */
    fun stripped(f: File): Boolean = runCatching { ZipReader.open(OpenFile(f)).has(BLOBS_NAME) }.getOrDefault(false)

    private fun fetcher(drive: Drive, rootId: String, names: () -> Map<String, Any?>): (String, File) -> Unit = { blob, dest ->
        val id = names()[blob] as? String ?: drive.find(blob, rootId)
            ?: throw DriveError(trf("云端缺少这本笔记里的一个 PDF 或图片（%s）。请在仍有这本笔记的设备上同步一次。", blob))
        drive.download(id, dest)
    }

    fun md5(f: File): String = Hash.md5(OpenFile(f))

    /** A name not taken: "x.inkpad", then "x 2.inkpad"... */
    fun unique(f: File): File {
        val ext = if (f.name.contains('.')) "." + f.name.substringAfterLast('.') else ""
        val base = f.name.removeSuffix(ext)
        var out = f
        var n = 2
        while (out.exists()) {
            out = File(f.parentFile, "$base $n$ext")
            n++
        }
        return out
    }

    fun relPath(f: File, root: File): String {
        val path = f.canonicalPath
        val base = root.canonicalPath
        return if (path.startsWith("$base/")) path.substring(base.length + 1) else f.name
    }

    /** (notebooks, folders) in the library: relative path -> file, and a set of relative paths. */
    fun scan(root: File): Pair<Map<String, File>, MutableSet<String>> {
        val files = LinkedHashMap<String, File>()
        val folders = HashSet<String>()
        fun walk(dir: File, prefix: String) {
            for (f in dir.listFiles() ?: emptyArray()) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    folders.add(prefix + f.name)
                    walk(f, prefix + f.name + "/")
                } else if (f.name.endsWith(EXT)) {
                    files[prefix + f.name] = f
                }
            }
        }
        walk(root, "")
        return Pair(files, folders)
    }

    /** When this library last finished a sync, in seconds, or null. */
    fun lastSync(root: File): Double? = (loadJson(File(root, ".sync.json"))["last"] as? Number)?.toDouble()

    private fun replace(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

    private class Index(val cloud: MutableMap<String, Any?>, val rootId: String, val indexId: String?)

    private fun index(drive: Drive, state: MutableMap<String, Any?>, folder: String): Index {
        val rootId = cloudFolder(state, drive, folder)
        val indexId = state["index"] as? String ?: drive.find(INDEX_NAME, rootId)
        val cloud = if (indexId != null) LinkedHashMap(drive.getJSON(indexId)) else LinkedHashMap()
        return Index(cloud, rootId, indexId)
    }

    /** Notebooks the index marks as deleted while their file is still on Drive. Nothing is
     *  downloaded: this only reads the index. Newest first. */
    fun cloudDeleted(root: File, drive: Drive, folder: String = ""): List<CloudDeleted> {
        val state = loadJson(File(root, ".sync.json"))
        val items = index(drive, state, folder).cloud["items"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return items.mapNotNull { (nid, entry) ->
            val e = entry as? Map<*, *> ?: return@mapNotNull null
            if (e["deleted"] != true || e["file"] !is String) null
            else CloudDeleted(nid as String, e["path"] as? String ?: nid, numOf(e["ts"]))
        }.sortedByDescending { it.ts }
    }

    /** Download the chosen deleted notebooks back to the paths they had; returns how many. */
    fun cloudRestore(root: File, drive: Drive, ids: List<String>, folder: String = "", progress: ((Double, String) -> Unit)? = null): Int {
        val base = root.canonicalFile
        val statePath = File(base, ".sync.json")
        val state = loadJson(statePath)
        val idx = index(drive, state, folder)
        val cloud = idx.cloud
        val items = mapOfJson(deepCopy(cloud["items"]))
        val known = mapOfJson(deepCopy(state["items"]))
        var done = 0
        var names: Map<String, Any?>? = null
        for ((n, nid) in ids.withIndex()) {
            val entry = mapOfJson(items[nid])
            val file = entry["file"] as? String ?: continue
            val rel = entry["path"] as? String ?: (nid + EXT)
            val name = rel.substringAfterLast('/')
            progress?.invoke(n.toDouble() / ids.size, name)
            drive.onBytes = { got, total -> if (total > 0) progress?.invoke((n + got.toDouble() / total) / ids.size, name) }
            val target = unique(rel.split("/").fold(base) { f, p -> File(f, p) })
            target.parentFile.mkdirs()
            if (names == null) names = drive.children(idx.rootId).reversed().associate { it.name to it.id }
            val all = names!!
            val coming = File(target.path + ".part")   // whole before it is given its name in the library
            drive.download(file, coming)
            fatten(coming, null, fetch = fetcher(drive, idx.rootId) { all })
            replace(coming, target)
            val now = relPath(target, base)
            // It is a live notebook again, on both sides, with what the cloud holds.
            entry["deleted"] = false
            entry["trashed"] = false
            entry["path"] = now
            entry["ts"] = Clock.now()
            items[nid] = entry
            known[nid] = linkedMapOf("path" to now, "trashed" to false, "md5" to (entry["md5"] as? String ?: ""), "local" to md5(target))
            done++
        }
        drive.onBytes = null
        if (done > 0) {
            cloud["items"] = items
            state["root"] = idx.rootId
            state["index"] = drive.putJSON(INDEX_NAME, cloud, idx.rootId, idx.indexId)
            state["items"] = known
            saveJson(statePath, state)
        }
        progress?.invoke(1.0, "")
        return done
    }

    private class Ref(var d: MutableMap<String, Any?> = LinkedHashMap()) {
        val deleted get() = d["deleted"] == true
        val trashed get() = d["trashed"] == true
        val md5 get() = d["md5"] as? String
        val file get() = d["file"] as? String
        val path get() = d["path"] as? String
    }

    private class Local(val file: File, val rel: String, val inTrash: Boolean)

    /**
     * Merge the library folder with the Drive index.
     *
     * [skip] lists notebooks to leave alone. [progress] reports how far along the sync is: one step
     * for reading the index, one per notebook (advancing with the bytes of its transfer) and one
     * for the end.
     *
     * A sync is quick or thorough. A quick one takes the index at its word about what is on Drive,
     * and this device's own record about which notebooks were written since the last sync: it reads
     * the index, moves what differs and writes the index back. A thorough one also lists the folder
     * on Drive and puts right what does not fit: files deleted there, a folder that is gone, an index
     * that is behind the files. That is done when asked for (thorough = true: "sync now", and once
     * when the app starts), when it has not been done for some hours, and when a quick sync comes
     * upon something on Drive that is not there.
     *
     * With [only], nothing but those notebooks is looked at, which takes a few requests and is what
     * an open notebook is kept up to date with while it is being written in.
     */
    fun sync(root: File, drive: Drive, skip: List<File> = emptyList(), only: List<File>? = null, folder: String = "",
             thorough: Boolean? = null, progress: ((Double, String) -> Unit)? = null): SyncSummary {
        var all = thorough
        if (all == null) {
            val state = loadJson(File(root.canonicalFile, ".sync.json"))
            all = state["index"] !is String || Clock.now() - numOf(state["checked"]) > thoroughEvery
        }
        return try {
            run(root, drive, skip, only, folder, all, progress)
        } catch (e: DriveError) {
            if (all || e.code != 404) throw e
            run(root, drive, skip, only, folder, true, progress)   // something was not where the index said
        }
    }

    private fun run(rootIn: File, drive: Drive, skip: List<File>, only: List<File>?, folder: String, thorough: Boolean,
                    progress: ((Double, String) -> Unit)?): SyncSummary {
        val root = rootIn.canonicalFile
        val report = progress ?: { _, _ -> }
        report(0.0, tr("正在读取云端目录…"))
        val statePath = File(root, ".sync.json")
        val state = loadJson(statePath)
        val rootId = cloudFolder(state, drive, folder, thorough)
        // id -> {path, trashed, md5, ...} when both sides last agreed
        val known = mapOfJson(deepCopy(state["items"]))
        val trashDir = File(root, ".trash")
        trashDir.mkdirs()
        val summary = SyncSummary()

        // A thorough sync has the folder's listing to go by; a quick one has only the index.
        val inFolder = if (thorough) drive.children(rootId) else null
        val live = inFolder?.map { it.id }?.toSet()
        var indexId = state["index"] as? String
        if (inFolder != null && live != null) {
            if (indexId == null || indexId !in live) indexId = inFolder.firstOrNull { it.name == INDEX_NAME }?.id
        } else if (indexId == null) {
            indexId = drive.find(INDEX_NAME, rootId)
        }
        var cloud: MutableMap<String, Any?> = if (indexId != null) mapOfJson(deepCopy(drive.getJSON(indexId))) else LinkedHashMap()
        if (numOf(cloud["format"], 1.0) > FORMAT) {
            throw DriveError(tr("云端的笔记是由更新版本的 Inkpad 同步的，请先更新这台设备上的 Inkpad"))
        }
        // The PDFs and pictures kept on Drive as files of their own are listed in the index too.
        var names = mapOfJson(deepCopy(cloud["blobs"]))   // name -> Drive file
        val fetch = fetcher(drive, rootId) { names }
        val blobsSeen = HashMap<String, Map<String, Any?>>()   // id -> the blobs found in the notebook when it was sent: see slim()
        val workDir = File(root, ".sync")
        workDir.deleteRecursively()   // whatever a sync that was cut off left behind
        workDir.mkdirs()
        val agreed = HashMap<String, Pair<String, String>>()   // id -> (checksum, fileSign()) of the local file that matches the cloud's version
        // id -> {path, trashed, md5, file, deleted, ts}
        val items = LinkedHashMap<String, Ref>()
        for ((nid, value) in mapOfJson(cloud["items"])) items[nid] = Ref(mapOfJson(deepCopy(value)))
        for (part in listOf("items", "blobs")) if (cloud[part] == null) cloud[part] = LinkedHashMap<String, Any?>()
        val read0 = deepCopy(cloud)   // the index as it was read, to tell later what others changed
        if (inFolder != null && live != null) {
            for ((nid, entry) in items.entries.toList()) {
                if (entry.file == null || entry.file !in live) {
                    if (entry.deleted) {
                        entry.d["file"] = null   // still a deletion to pass on, with nothing left to restore
                    } else {
                        // Listed, but its file is gone from Drive. Forgotten here, it is sent again by
                        // whichever device still has it.
                        items.remove(nid)
                    }
                }
            }
            val actual = inFolder.filter { it.md5 != null }.reversed().associate { it.id to it.md5!! }
            for (entry in items.values) {
                // The file itself says what is in it. The index may be behind: a device that was cut
                // off after an upload never got to write it down.
                if (!entry.deleted) entry.file?.let { f -> actual[f]?.let { entry.d["md5"] = it } }
            }
            // The same for the list of blobs: those that are gone are forgotten, and those that are
            // there unlisted (uploaded before the index kept this list) are taken up.
            names = LinkedHashMap(names.filterValues { it in live })
            for (f in inFolder) if (f.name.startsWith("blob-") && f.name !in names) names[f.name] = f.id
            state["checked"] = Clock.now()
        }

        /** The index as this sync has it now. */
        fun indexNow(): MutableMap<String, Any?> {
            val out = LinkedHashMap(cloud)
            out["items"] = LinkedHashMap<String, Any?>().also { m -> for ((k, v) in items) m[k] = v.d }
            out["blobs"] = LinkedHashMap(names)
            return out
        }
        var read = read0
        var mended = !jsonEq(indexNow(), read0)   // whether looking at Drive showed the index to be wrong
        var mineWas = deepCopy(indexNow())        // and as this sync started out with it
        val sent = HashSet<String>()              // ids of the notebooks this sync uploaded
        var savedAt = System.nanoTime()
        var moved = 0

        /** Write the index. It is read again first: whatever another device changed in it while
         *  this sync was running is kept, wherever this sync did not change the same thing. */
        fun publish() {
            if (indexId != null && !mended && jsonEq(indexNow(), mineWas)) {
                state["index"] = indexId   // found, if not written: remembered all the same
                return                     // nothing to write down
            }
            if (indexId == null) indexId = drive.find(INDEX_NAME, rootId)
            val latest = indexId?.let { drive.getJSON(it) } ?: emptyMap()
            val out = indexNow()
            for (part in listOf("items", "folders", "blobs")) {
                val ours = mapOfJson(deepCopy(out[part]))
                val was = mapOfJson(read.obj()[part])
                val began = mapOfJson(mineWas.obj()[part])
                for ((key, value) in mapOfJson(latest[part])) {
                    if (!jsonEq(value, was[key]) && jsonEq(ours[key], began[key])) {
                        ours[key] = deepCopy(value)
                        if (part == "items") items[key] = Ref(mapOfJson(deepCopy(value)))
                        if (part == "blobs" && value is String) names[key] = value
                    }
                }
                out[part] = ours
            }
            if (numOf(mapOfJson(latest["meta"])["ts"]) > numOf(mapOfJson(out["meta"])["ts"])) out["meta"] = deepCopy(latest["meta"])
            indexId = drive.putJSON(INDEX_NAME, out, rootId, indexId)
            state["index"] = indexId
            cloud = out
            read = deepCopy(out)
            mineWas = deepCopy(out)
            mended = false
        }

        fun abspath(rel: String) = rel.split("/").fold(root) { f, p -> File(f, p) }

        // What is on this device: id -> (file, relative path, in trash?).
        val skipPaths = skip.map { it.canonicalPath }.toSet()
        val skipIds = HashSet<String>()
        val local = LinkedHashMap<String, Local>()
        val (files, _) = scan(root)
        val found = files.toSortedMap().map { Local(it.value, it.key, false) } +
            (trashDir.list() ?: emptyArray()).filter { it.endsWith(EXT) }.sorted().map { Local(File(trashDir, it), it, true) }
        val onlyPaths = only?.map { it.canonicalPath }?.toSet()
        // A notebook that has not been written since the last sync is known by its place: it is not
        // opened to be asked who it is. This device's record of the last sync is the list of what
        // has been edited: whatever is not in it as it is now.
        val sameAs = HashMap<String, Pair<String, String?>>()
        for ((nid, e) in known) {
            val m = mapOfJson(e)
            sameAs["${m["trashed"] == true}/${m["path"] as? String ?: ""}"] = Pair(nid, m["stat"] as? String)
        }
        val unwritten = HashSet<String>()
        for (entry in found) {
            var here = entry
            if (onlyPaths != null && here.file.canonicalPath !in onlyPaths) continue
            var nid: String
            val same = sameAs["${here.inTrash}/${here.rel}"]
            if (same != null && same.second != null && same.second == fileSign(here.file) && local[same.first] == null) {
                nid = same.first
                unwritten.add(nid)
            } else {
                nid = runCatching { notebookId(here.file) }.getOrNull() ?: continue   // not a readable notebook; leave it alone
            }
            if (here.file.canonicalPath in skipPaths) {
                skipIds.add(nid)
                continue
            }
            val other = local[nid]
            if (other != null) {
                // A copy made outside the app shares its original's id; make it a notebook of its own.
                // The original is the one still where the last sync left it, whichever was met first.
                val was = mapOfJson(known[nid])["path"] as? String
                if (here.rel == was && other.rel != was) {
                    local[nid] = here
                    here = other
                }
                nid = Ids.hex()
                setNotebookId(here.file, nid)
            }
            if (local[nid] != null) unwritten.remove(nid)   // two files with one id: both are looked at
            local[nid] = here
        }

        /** Move a local notebook to where the index says it belongs. */
        fun place(nid: String, rel: String, inTrash: Boolean) {
            val path = local[nid]!!.file
            var target = if (inTrash) File(trashDir, rel.substringAfterLast('/')) else abspath(rel)
            if (target.canonicalPath != path.canonicalPath) {
                target.parentFile.mkdirs()
                target = unique(target)
                if (!path.renameTo(target)) throw DriveError(trf("无法移动“%s”", path.name))
            }
            local[nid] = Local(target, if (inTrash) target.name else relPath(target, root), inTrash)
        }

        /** (what stands for the content, fileSign()) of a notebook here. No notebook is read
         *  through for this: one that has not been written since the last sync is what it was then,
         *  and one that has been written is another. (Only a record from before the time of writing
         *  was kept gives nothing to go by; then the checksum is worked out, once.) */
        fun localMd5(nid: String, f: File): Pair<String, String> {
            val was = known[nid]?.let { mapOfJson(it) }
            val stat = fileSign(f) ?: ""
            val sum = was?.get("local") as? String
            if (!sum.isNullOrEmpty() && was["stat"] as? String == stat) return Pair(sum, stat)
            if (!(was?.get("stat") as? String).isNullOrEmpty()) return Pair("written $stat", stat)
            return Pair(md5(f), stat)
        }

        /** Upload a local notebook's content and record it in the index entry. */
        fun push(nid: String, entry: Ref) {
            val here = local[nid]!!
            val slimmed = File(workDir, "upload")
            val s = slim(here.file, slimmed, mapOfJson(known[nid])["blobs"]?.let { mapOfJson(it) }) { it in names }
            blobsSeen[nid] = s.found
            for ((name, part) in s.blobs.toSortedMap()) {
                // Each of these is uploaded once, whatever is written in the notebook afterwards.
                val (blobId, arrived) = drive.upload(part, name, rootId, null)
                if (arrived != null && arrived != md5(part)) {
                    throw DriveError(tr("上传的 PDF 或图片在云端的校验值不对，请再同步一次"))   // and it is not listed
                }
                names[name] = blobId
                part.delete()
            }
            val (fileId, sum) = drive.upload(slimmed, here.rel.substringAfterLast('/'), rootId, entry.file)
            // What arrived is what was sent: only then is it written down in the index.
            val sentSum = md5(slimmed)
            if (sum != null && sum != sentSum) throw DriveError(tr("上传的笔记本在云端的校验值不对，请再同步一次"))
            slimmed.delete()
            agreed[nid] = Pair("written " + s.stat, s.stat)
            cloud["format"] = FORMAT
            entry.d["file"] = fileId
            entry.d["md5"] = sum ?: sentSum
            entry.d["path"] = here.rel
            entry.d["trashed"] = here.inTrash
            entry.d["deleted"] = false
            entry.d["ts"] = Clock.now()
            summary.up++
            sent.add(nid)
        }

        /** Make a notebook and the cloud's version of it the same, losing nothing of either. */
        fun settle(nid: String, entry: Ref, before: Map<String, Any?>?) {
            val path = local[nid]!!.file
            // What both sides last agreed on: the cloud's file, and the one here that matched it.
            // (They are not the same file: the cloud's is without the notebook's PDFs and pictures.)
            var base = before?.get("md5") as? String
            var baseLocal = (before?.get("local") as? String)?.ifEmpty { null } ?: base
            var mineSent = before?.get("sent") == true   // ...which this device had written
            repeat(5) {
                val (mine, stat) = localMd5(nid, path)
                val localSame = baseLocal != null && mine == baseLocal
                val cloudSame = base != null && entry.md5 == base
                if (mine == entry.md5 || (localSame && cloudSame)) {
                    val listed = mapOfJson(before?.get("blobs")).values.mapNotNull { (it as? List<*>)?.lastOrNull() as? String }
                    if (thorough && listed.any { it !in names }) {
                        push(nid, entry)   // a PDF or picture of it is gone from Drive: it is sent again
                        return
                    }
                    if (agreed[nid] == null) agreed[nid] = Pair(mine, stat)
                    return
                }
                val file = entry.file
                if (file == null) {
                    push(nid, entry)
                    return
                }
                if (cloudSame) {
                    // Only this device has changed it, as far as the index says. A thorough sync asks
                    // Drive once more, in case another device sent its version this very moment; a
                    // quick one takes the index at its word. (Should two devices cross all the same,
                    // nothing is lost: the one written over sees at its next sync that the cloud's
                    // version is not its own, and merges its edits back in.)
                    val now = if (thorough) drive.md5(file) else null
                    if (now == null || now == base) {
                        push(nid, entry)
                        return
                    }
                    entry.d["md5"] = now
                    return@repeat
                }
                // Aside, so that nothing half done is left by the notebook.
                val theirs = File(workDir, "theirs")
                drive.download(file, theirs)
                entry.d["md5"] = md5(theirs)
                fatten(theirs, path, mapOfJson(known[nid])["blobs"]?.let { mapOfJson(it) }, fetch)
                val taken = synchronizedFileLock {
                    val untouched = localSame && fileSign(path) == stat
                    var replaceIt = untouched && !mineSent   // nothing here that the cloud's version lacks
                    if (!replaceIt) {
                        try {
                            val hereDoc = Document.load(path)
                            val theirDoc = Document.load(theirs)
                            if (untouched) {
                                // The cloud's version ought to be a later one of what this device sent.
                                // If another device wrote over that without having seen it, it is not;
                                // then what was written here is put back into it.
                                if (merge(theirDoc, hereDoc).all { it == 0 }) replaceIt = true
                                else theirDoc.save(path)
                            } else {
                                // Edited on both sides: merge the two, item by item.
                                merge(hereDoc, theirDoc)
                                hereDoc.save(path)
                            }
                            if (!replaceIt) {
                                theirs.delete()
                                summary.merged++
                            }
                        } catch (e: Exception) {
                            if (e is InterruptedException) throw e
                            // Only if merging is not possible is the remote version kept as a notebook
                            // of its own.
                            val copy = unique(File(path.parentFile, path.name.removeSuffix(EXT) + tr("（云端副本）") + EXT))
                            replace(theirs, copy)
                            runCatching { setNotebookId(copy, Ids.hex()) }
                            summary.conflicts++
                        }
                    }
                    if (replaceIt) {
                        replace(theirs, path)
                        val st = fileSign(path) ?: ""
                        agreed[nid] = Pair("written $st", st)
                        summary.down++
                    }
                    replaceIt
                }
                if (taken) return
                // What is here now has all of that version in it, and more.
                base = entry.md5
                baseLocal = null
                mineSent = false
            }
            throw DriveError(trf("“%s”在同步时一直被其它设备更改，请稍后再同步", path.name))
        }

        val ids = if (only == null) (local.keys + items.keys + known.keys).toSortedSet().toList() else local.keys.sorted()
        val steps = (ids.size + 2).toDouble()
        val unmended = ArrayList<Pair<String, String>>()   // notebooks here that lack their PDFs and could not be given them back
        for ((n, nid) in ids.withIndex()) {
            checkCancelled()
            val here = local[nid]
            val before = known[nid]?.let { mapOfJson(it) }
            var entry = items[nid]
            val name = (here?.rel ?: entry?.path ?: "").substringAfterLast('/')
            report((n + 1) / steps, name)
            // A transfer moves the bar smoothly through this notebook's step.
            drive.onBytes = { got, total -> if (total > 0) report((n + 1 + got.toDouble() / total) / steps, name) }
            if (nid in skipIds) continue
            if (here != null && nid !in unwritten && stripped(here.file)) {
                // A notebook here without its PDFs and pictures (see stripped). They are fetched and
                // it is made whole before anything else is done with it; while that is not possible
                // it is left exactly as it is, and above all not uploaded.
                try {
                    val mending = File(workDir, "mending")
                    here.file.copyTo(mending, overwrite = true)
                    fatten(mending, null, fetch = fetch)
                    synchronizedFileLock { replace(mending, here.file) }
                    summary.mended++
                } catch (e: DriveError) {
                    unmended.add(Pair(name, e.message ?: ""))
                    continue
                }
            }
            val beforeMd5 = before?.get("md5") as? String
            if (here == null && entry == null) {
                known.remove(nid)
                continue
            }
            if (entry == null && here != null) {
                // New on this device. Something already in the trash is not worth sending.
                if (here.inTrash) continue
                entry = Ref()
                items[nid] = entry
                push(nid, entry)
            } else if (here == null && entry != null) {
                if (entry.deleted) {
                    known.remove(nid)
                    continue
                }
                if (before != null && entry.md5 == beforeMd5) {
                    // Gone from this device and untouched elsewhere: leave a tombstone. The file itself
                    // stays on Drive, so a deletion can always be recovered from there.
                    entry.d["deleted"] = true
                    entry.d["ts"] = Clock.now()
                    known.remove(nid)
                    summary.removed++
                    continue
                }
                if (entry.trashed && before == null) continue   // sitting in another device's trash; no need to fetch it
                val path = entry.path ?: continue
                val file = entry.file ?: continue
                var target = if (entry.trashed) File(trashDir, path.substringAfterLast('/')) else abspath(path)
                target.parentFile.mkdirs()
                target = unique(target)
                // Put together aside, and moved into the library only when it is whole: a download
                // that is cut off leaves nothing there.
                val coming = File(workDir, "coming")
                drive.download(file, coming)
                fatten(coming, null, fetch = fetch)
                replace(coming, target)
                val st = fileSign(target) ?: ""
                agreed[nid] = Pair("written $st", st)
                local[nid] = Local(target, if (entry.trashed) target.name else relPath(target, root), entry.trashed)
                summary.down++
            } else if (here != null && entry != null) {
                val mine = localMd5(nid, here.file).first
                val wasLocal = (before?.get("local") as? String)?.ifEmpty { null } ?: beforeMd5
                val unchanged = before != null && mine == wasLocal
                var content = true
                if (entry.deleted) {
                    if (here.inTrash || unchanged) {
                        // Deleted elsewhere and not edited here since: follow, into the trash.
                        place(nid, here.rel, true)
                        known.remove(nid)
                        if (!here.inTrash) summary.removed++
                        continue
                    }
                    push(nid, entry)   // edited after the deletion: the edit wins
                    content = false
                }
                if (content) settle(nid, entry, before)
                // Name, folder and trash state travel separately from the content.
                val therePath = entry.path
                val thereTrash = entry.trashed
                if (here.rel != therePath || here.inTrash != thereTrash) {
                    if (before != null && here.rel == before["path"] as? String && here.inTrash == (before["trashed"] == true) && therePath != null) {
                        place(nid, therePath, thereTrash)
                    } else {
                        val mineName = here.rel.substringAfterLast('/')
                        val file = entry.file
                        if (file != null && mineName != (therePath ?: "").substringAfterLast('/')) drive.rename(file, mineName)
                        entry.d["path"] = here.rel
                        entry.d["trashed"] = here.inTrash
                        entry.d["ts"] = Clock.now()
                    }
                    summary.moved++
                }
            }
            val at = local[nid] ?: continue
            val e = items[nid] ?: continue
            e.d["path"] = at.rel
            e.d["trashed"] = at.inTrash
            val was = known[nid]?.let { mapOfJson(it) }
            val rec = linkedMapOf<String, Any?>("path" to at.rel, "trashed" to at.inTrash, "md5" to (e.md5 ?: ""))
            val ag = agreed[nid]
            if (ag != null) {
                rec["local"] = ag.first
                rec["stat"] = ag.second
            } else if (was?.get("local") is String && was["md5"] as? String == e.md5) {
                rec["local"] = was["local"]
            }
            if (blobsSeen[nid] != null) rec["blobs"] = blobsSeen[nid]
            else if (was?.get("blobs") != null) rec["blobs"] = was["blobs"]
            if (nid in sent || (was?.get("sent") == true && was["md5"] as? String == e.md5)) {
                rec["sent"] = true   // the cloud's version is the one this device wrote
            }
            known[nid] = rec
            if (summary.up + summary.down != moved) {
                moved = summary.up + summary.down
                // Write down, in the cloud and here, what has been done so far, so that a sync that
                // is cut off goes on from there the next time.
                if ((System.nanoTime() - savedAt) / 1e9 >= checkpoint) {
                    publish()
                    state["items"] = known
                    saveJson(statePath, state)
                    savedAt = System.nanoTime()
                }
            }
        }

        drive.onBytes = null
        workDir.deleteRecursively()
        if (only != null) {
            publish()
            state["items"] = known
            saveJson(statePath, state)
            report(1.0, "")
            return summary
        }
        report((steps - 1) / steps, tr("正在更新云端目录…"))
        // Folders, so that empty ones and their removal carry over too.
        val folders = mapOfJson(deepCopy(cloud["folders"]))   // relative path -> deleted?
        val seen = (state["folders"] as? List<*>)?.map { it as String }?.toSet() ?: emptySet()
        val localDirs = scan(root).second
        for (rel in localDirs.sortedByDescending { it.length }) {
            val visible = (abspath(rel).list() ?: emptyArray()).filter { it != ".DS_Store" }
            if (folders[rel] == true && rel in seen && visible.isEmpty()) {
                abspath(rel).delete()
                localDirs.remove(rel)
            } else {
                folders[rel] = false
            }
        }
        for (rel in folders.keys.sorted()) {
            if (folders[rel] != true && rel !in localDirs) {
                if (rel in seen) {
                    folders[rel] = true
                } else {
                    abspath(rel).mkdirs()
                    localDirs.add(rel)
                }
            }
        }
        state["folders"] = localDirs.sorted()

        // Favourites and folder styles: the newer copy wins as a whole.
        val metaPath = File(root, ".library.json")
        val meta = loadJson(metaPath)
        val theirs = cloud["meta"] as? Map<*, *>
        val mineTs = numOf(meta["ts"])
        val theirTs = numOf(theirs?.get("ts"))
        if (theirs != null && theirTs > mineTs) {
            for (key in listOf("favorites", "folders")) if (theirs[key] != null) meta[key] = deepCopy(theirs[key])
            meta["ts"] = theirTs
            saveJson(metaPath, meta)
            summary.meta = true
        } else if (mineTs > theirTs) {
            cloud["meta"] = linkedMapOf("ts" to mineTs, "favorites" to meta["favorites"], "folders" to meta["folders"])
        }

        cloud["folders"] = folders
        publish()
        state["items"] = known
        state["last"] = Clock.now()
        saveJson(statePath, state)
        report(1.0, "")
        unmended.firstOrNull()?.let { (name, why) -> throw DriveError(trf("“%s”缺少它的 PDF 或图片，暂时无法补全：%s", name, why)) }
        return summary
    }

    private inline fun <T> synchronizedFileLock(fn: () -> T): T {
        fileLock.lock()
        try {
            return fn()
        } finally {
            fileLock.unlock()
        }
    }
}
