package io.github.inkpad.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The library folder: notebooks, folders, a trash, and the shared favourites and folder styles.
// The layout on disk is the desktop app's (B:library.py Store; A:Library/Store.swift), which is
// what the sync works on.

const val EXT = ".inkpad"

val FOLDER_COLORS = listOf(rgb(0.49, 0.74, 0.99), rgb(0.36, 0.80, 0.82), rgb(0.47, 0.80, 0.52), rgb(0.98, 0.82, 0.38),
    rgb(0.99, 0.62, 0.42), rgb(0.96, 0.52, 0.56), rgb(0.76, 0.62, 0.95), rgb(0.68, 0.70, 0.74))

/** A notebook or a folder in the library. */
class Entry(val file: File) {
    val isDir: Boolean = file.isDirectory
    val name: String = if (isDir) file.name else file.name.removeSuffix(EXT)
    val mtime: Long = file.lastModified()
    override fun equals(other: Any?) = other is Entry && other.file == file
    override fun hashCode() = file.hashCode()
}

class StoreError(message: String) : Exception(message)

class Store(root: File) {
    val root: File = root.canonicalFile
    val trashDir = File(this.root, ".trash")
    val archiveDir = File(this.root, ".deleted")   // things deleted "for good" are kept here until the cache is cleared
    private val metaFile = File(this.root, ".library.json")
    var meta: MutableMap<String, Any?> = LinkedHashMap()
        private set

    init {
        trashDir.mkdirs()
        reloadMeta()
    }

    /** Pick up favourites and folder styles that a sync brought in. */
    fun reloadMeta() {
        val read = loadJson(metaFile)
        meta.putAll(read)
        if (meta["favorites"] == null) meta["favorites"] = ArrayList<Any?>()
        if (meta["trashed"] == null) meta["trashed"] = LinkedHashMap<String, Any?>()   // trash name -> folder it came from
        if (meta["folders"] == null) meta["folders"] = LinkedHashMap<String, Any?>()   // folder -> {color: [r, g, b], icon: name}
    }

    private fun saveMeta() {
        // The time stamp says when the favourites and folder styles, the part that is synced
        // whole (newer wins), were last changed here: other changes leave it, or a device with
        // stale favourites that trashed a notebook would write them over everyone's.
        val old = loadJson(metaFile)
        fun shared(m: Map<String, Any?>) = JsonOut.write(linkedMapOf("f" to (m["favorites"] ?: emptyList<Any?>()), "d" to (m["folders"] ?: emptyMap<String, Any?>())))
        if (shared(old) != shared(meta)) meta["ts"] = Clock.now()
        saveJson(metaFile, meta)
    }

    @Suppress("UNCHECKED_CAST")
    private var favoritesList: List<String>
        get() = (meta["favorites"] as? List<Any?>)?.map { it as String } ?: emptyList()
        set(v) { meta["favorites"] = ArrayList<Any?>(v) }

    @Suppress("UNCHECKED_CAST")
    private var trashedMap: MutableMap<String, Any?>
        get() = LinkedHashMap(meta["trashed"] as? Map<String, Any?> ?: emptyMap())
        set(v) { meta["trashed"] = v }

    @Suppress("UNCHECKED_CAST")
    private var styles: MutableMap<String, Any?>
        get() = LinkedHashMap(meta["folders"] as? Map<String, Any?> ?: emptyMap())
        set(v) { meta["folders"] = v }

    fun rel(f: File): String {
        val path = f.canonicalFile.path
        if (path == root.path) return "."
        return if (path.startsWith(root.path + "/")) path.substring(root.path.length + 1) else path
    }

    fun contains(f: File) = f.canonicalFile.path.startsWith(root.path + "/")

    fun entries(folder: File): List<Entry> = (folder.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
        .map { Entry(it) }.filter { it.isDir || it.file.name.endsWith(EXT) }

    fun favorites(): List<Entry> = favoritesList.map { File(root, it) }.filter { it.exists() }.map { Entry(it) }

    fun isFavorite(f: File) = rel(f) in favoritesList

    fun toggleFavorite(f: File) {
        val r = rel(f)
        favoritesList = if (r in favoritesList) favoritesList - r else favoritesList + r
        saveMeta()
    }

    /** A folder's colour and icon name ("" for none). */
    fun folderStyle(f: File): Pair<Color, String> {
        val style = styles[rel(f)] as? Map<*, *> ?: emptyMap<String, Any?>()
        val color = (style["color"] as? List<*>)?.takeIf { it.size >= 3 }?.map { it.num() }?.toDoubleArray() ?: FOLDER_COLORS[0]
        return Pair(color, style["icon"] as? String ?: "")
    }

    fun setFolderStyle(f: File, color: Color? = null, icon: String? = null) {
        val all = styles
        val style = LinkedHashMap((all[rel(f)] as? Map<String, Any?>) ?: emptyMap())
        if (color != null) style["color"] = color.toList()
        if (icon != null) style["icon"] = icon
        all[rel(f)] = style
        styles = all
        saveMeta()
    }

    /** Every folder in the library as a tree read downwards: the root, then each folder followed at
     *  once by everything inside it. */
    fun folders(): List<File> {
        val out = arrayListOf(root)
        fun walk(dir: File) {
            for (sub in entries(dir).filter { it.isDir }.sortedBy { it.name.lowercase() }) {
                out.add(sub.file)
                walk(sub.file)
            }
        }
        walk(root)
        return out
    }

    private fun split(f: File): Pair<String, String> = if (f.isDirectory) Pair(f.name, "") else Pair(f.name.removeSuffix(EXT), EXT)

    private fun clean(name: String) = name.trim().replace("/", "-")

    fun newNotebook(folder: File, template: Template, board: Boolean = false, name: String? = null, cover: Pair<String, Color>? = null): File {
        val fallback = if (board) tr("未命名白板") else tr("未命名笔记")
        val n = clean(name ?: "")
        val f = unique(folder, n.ifEmpty { fallback }, EXT)
        val doc = Document(template)
        if (board) {
            doc.pages[0].board = true
            doc.pages[0].w = BOARD_SIZE.first
            doc.pages[0].h = BOARD_SIZE.second
        }
        doc.coverSpec = cover
        doc.save(f)
        return f
    }

    /** Save a document made elsewhere (an import) as a new notebook. */
    fun addDocument(folder: File, name: String, doc: Document): File {
        val f = unique(folder, clean(name).ifEmpty { tr("未命名笔记") }, EXT)
        doc.save(f)
        return f
    }

    /** Copy a notebook file from outside into a folder of the library, as a notebook of its own. */
    fun importNotebook(source: File, into: File, name: String = source.name.removeSuffix(EXT)): File {
        val f = unique(into, clean(name), EXT)
        source.copyTo(f)
        setNotebookId(f, Ids.hex())
        return f
    }

    fun newFolder(folder: File, name: String): File {
        val f = unique(folder, clean(name).ifEmpty { tr("新文件夹") })
        f.mkdirs()
        return f
    }

    /** Keep favourites and styles pointing at an item (and what is inside it) after a move. */
    private fun moved(old: File, new: File) {
        val o = rel(old)
        val n = rel(new)
        favoritesList = favoritesList.map { if (it == o || it.startsWith("$o/")) n + it.substring(o.length) else it }
        val all = styles
        for (key in all.keys.toList()) if (key == o || key.startsWith("$o/")) all[n + key.substring(o.length)] = all.remove(key)
        styles = all
        saveMeta()
    }

    fun rename(f: File, name: String): File {
        val c = clean(name)
        val (old, ext) = split(f)
        if (c.isEmpty() || c == old) return f
        val new = unique(f.parentFile, c, ext)
        if (!f.renameTo(new)) throw StoreError(tr("无法重命名"))
        moved(f, new)
        return new
    }

    /** Move an item into a folder, under another name if one is given (as when a move is undone
     *  and it gets back the name it had). Returns where it is now. */
    fun move(f: File, to: File, name: String? = null): File {
        val (old, ext) = split(f)
        val n = name ?: old
        if (f.parentFile.canonicalPath == to.canonicalPath && n == old) return f
        val from = f.canonicalPath
        val dest = to.canonicalPath
        if (dest == from || dest.startsWith("$from/")) throw StoreError(tr("文件夹不能移动到它自己里面"))
        val new = unique(to, n, ext)
        if (!f.renameTo(new)) throw StoreError(tr("无法移动"))
        moved(f, new)
        return new
    }

    fun trash(f: File) {
        val (name, ext) = split(f)
        val new = unique(trashDir, name, ext)
        val r = rel(f)
        favoritesList = favoritesList.filter { it != r && !it.startsWith("$r/") }
        val map = trashedMap
        map[new.name] = rel(f.parentFile)
        trashedMap = map
        if (!f.renameTo(new)) throw StoreError(tr("无法移到废纸篓"))
        saveMeta()
    }

    fun trashed(): List<Entry> = entries(trashDir)

    fun restore(f: File): File {
        val map = trashedMap
        val origin = map.remove(f.name) as? String ?: "."
        trashedMap = map
        var folder = if (origin == ".") root else File(root, origin).canonicalFile
        if (!folder.isDirectory || !(folder == root || contains(folder))) folder = root
        val (name, ext) = split(f)
        val new = unique(folder, name, ext)
        if (!f.renameTo(new)) throw StoreError(tr("无法恢复"))
        saveMeta()
        return new
    }

    /** Remove something from the trash. It is kept out of sight until the cache is cleared. */
    fun delete(f: File) {
        if (f.parentFile.canonicalFile != trashDir.canonicalFile) throw StoreError("only trashed items can be deleted")
        archiveDir.mkdirs()
        val (name, ext) = split(f)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss ", Locale.ROOT).format(Date())
        val kept = unique(archiveDir, stamp + name, ext)
        if (!f.renameTo(kept)) throw StoreError(tr("无法删除"))
        // Where it lived before the trash, so that it can be put back there.
        val map = trashedMap
        @Suppress("UNCHECKED_CAST")
        val archived = LinkedHashMap(meta["archived"] as? Map<String, Any?> ?: emptyMap())
        archived[kept.name] = map.remove(f.name) ?: "."
        meta["archived"] = archived
        trashedMap = map
        saveMeta()
    }

    /** A copy next to the original. Copies are notebooks of their own, with new ids. */
    fun duplicate(f: File): File {
        val (name, ext) = split(f)
        val new = unique(f.parentFile, name + tr(" 副本"), ext)
        f.copyRecursively(new)
        var books = listOf(new)
        if (ext.isEmpty()) {
            books = new.walkTopDown().filter { it.isFile && it.name.endsWith(EXT) }.toList()
            styles[rel(f)]?.let { style ->
                val all = styles
                all[rel(new)] = style
                styles = all
                saveMeta()
            }
        }
        for (book in books) runCatching { setNotebookId(book, Ids.hex()) }
        return new
    }

    /** Notebooks and folders anywhere in the library whose name contains [query]. */
    fun find(query: String): List<Entry> {
        val q = query.lowercase()
        val out = ArrayList<Entry>()
        fun walk(dir: File) {
            for (e in entries(dir)) {
                if (q in e.name.lowercase()) out.add(e)
                if (e.isDir) walk(e.file)
            }
        }
        walk(root)
        return out
    }

    /** What was deleted for good and is still kept on this device. */
    fun archived(): List<Entry> = entries(archiveDir)

    /** The folder (relative to the library) a deleted item would be restored to. */
    fun archivedOrigin(f: File): String = ((meta["archived"] as? Map<*, *>)?.get(f.name) as? String) ?: "."

    /** Bring a permanently deleted item back to the folder it was in (made again if need be). */
    fun unarchive(f: File): File {
        @Suppress("UNCHECKED_CAST")
        val map = LinkedHashMap(meta["archived"] as? Map<String, Any?> ?: emptyMap())
        val origin = map.remove(f.name) as? String ?: "."
        meta["archived"] = map
        var folder = if (origin == "." || origin.isEmpty()) root else File(root, origin).canonicalFile
        if (!(folder == root || contains(folder))) folder = root
        folder.mkdirs()
        val (name, ext) = split(f)
        val new = unique(folder, archivedName(name), ext)
        if (!f.renameTo(new)) throw StoreError(tr("无法恢复"))
        saveMeta()
        return new
    }

    /** Write a folder (or the whole library) with everything below it into one zip file. */
    fun exportArchive(folder: File, to: File): Int = exportPaths(listOf(folder), to, flatten = true)

    /**
     * Write the given notebooks and folders (with everything below them) into one zip file. A
     * single folder given alone is written as its contents ([flatten]); the library archive is.
     */
    fun exportPaths(files: List<File>, to: File, flatten: Boolean = false): Int {
        var count = 0
        val kept = LinkedHashMap<String, Any?>()
        val tmp = File(to.path + ".part")
        tmp.outputStream().buffered(1 shl 16).use { out ->
            val z = ZipWriter(out)
            fun walk(dir: File, inside: String) {
                if (inside.isNotEmpty()) {
                    z.addDir(inside)
                    if (dir == root || contains(dir)) styles[rel(dir)]?.let { kept[inside] = it }
                }
                val list = entries(dir).sortedBy { it.file.name }
                for (e in list.filter { !it.isDir }) {
                    z.addStored(if (inside.isEmpty()) e.file.name else inside + "/" + e.file.name, e.file.source())
                    count++
                }
                for (e in list.filter { it.isDir }) walk(e.file, if (inside.isEmpty()) e.file.name else inside + "/" + e.file.name)
            }
            if (flatten && files.size == 1 && files[0].isDirectory) {
                walk(files[0], "")
            } else {
                val used = HashSet<String>()
                for (f in files) {
                    var top = f.name
                    var n = 2
                    while (!used.add(top)) {   // two selected items may share a name (favourites, trash)
                        top = if (f.isDirectory) "${f.name} $n" else "${f.name.removeSuffix(EXT)} $n$EXT"
                        n++
                    }
                    if (f.isDirectory) walk(f, top) else {
                        z.addStored(top, f.source())
                        count++
                    }
                }
            }
            z.add(ARCHIVE_META, JsonOut.write(linkedMapOf("app" to "Inkpad", "folders" to kept)))
            z.finish()
        }
        if (!tmp.renameTo(to)) {
            tmp.copyTo(to, overwrite = true)
            tmp.delete()
        }
        return count
    }

    /** Unpack an exported zip into [folder]. Existing notebooks are never overwritten. */
    fun importArchive(src: File, into: File): Int {
        val z = ZipReader.open(src.source())
        var count = 0
        val kept = runCatching { Json.parse(z.text(ARCHIVE_META)).obj()["folders"]?.obj() }.getOrNull() ?: emptyMap()
        for (name in z.names) {
            val parts = name.split("/").filter { it.isNotEmpty() }
            if (parts.isEmpty() || name.startsWith("/") || parts.any { it == "." || it == ".." || it.startsWith(".") }) continue
            if (name.endsWith("/")) {
                parts.fold(into) { f, p -> File(f, p) }.mkdirs()
            } else if (name.endsWith(EXT)) {
                val dir = parts.dropLast(1).fold(into) { f, p -> File(f, p) }
                dir.mkdirs()
                val target = unique(dir, parts.last().removeSuffix(EXT), EXT)
                target.outputStream().use { z.sourceOf(name).copyTo(it) }
                count++
            }
        }
        val all = styles
        for ((r, style) in kept) {
            val f = r.split("/").fold(into) { d, p -> File(d, p) }
            if (f.isDirectory && contains(f) && all[rel(f)] == null && style is Map<*, *>) all[rel(f)] = style
        }
        styles = all
        saveMeta()
        return count
    }

    fun cacheSize(): Long = archiveDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Really delete what was deleted before. Nothing in the cloud is touched. */
    fun clearCache() {
        archiveDir.deleteRecursively()
        meta["archived"] = LinkedHashMap<String, Any?>()
        saveMeta()
    }

    companion object {
        const val ARCHIVE_META = "inkpad-library.json"

        /** A name not taken in [folder]: "name", then "name 2", "name 3"... */
        fun unique(folder: File, name0: String, ext: String = ""): File {
            // A name is made one the library can show: no slash (it would be a folder), no dot to begin with (a hidden file, which the library never shows).
            val name = name0.trim().replace('/', '-').trimStart('.').trim().ifEmpty { tr("未命名") }
            var f = File(folder, name + ext)
            var n = 2
            while (f.exists()) {
                f = File(folder, "$name $n$ext")
                n++
            }
            return f
        }

        /** Deleted items carry a date prefix ("20261007-164500 name") so they never collide. */
        fun archivedName(name: String): String {
            val parts = name.split(" ", limit = 2)
            if (parts.size == 2 && parts[0].length == 15 && parts[0].take(8).all { it.isDigit() }) return parts[1]
            return name
        }
    }
}

fun loadJson(f: File): MutableMap<String, Any?> = try {
    LinkedHashMap(Json.parse(f.readText()).obj())
} catch (e: Exception) {
    LinkedHashMap()
}

fun saveJson(f: File, value: Any?) {
    val tmp = File(f.path + ".tmp")
    tmp.writeText(JsonOut.write(value))
    if (!tmp.renameTo(f)) {
        tmp.copyTo(f, overwrite = true)
        tmp.delete()
    }
}
