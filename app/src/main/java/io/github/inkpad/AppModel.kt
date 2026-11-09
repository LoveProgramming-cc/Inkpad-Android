package io.github.inkpad

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.inkpad.core.Changed
import io.github.inkpad.core.Document
import io.github.inkpad.core.Event
import io.github.inkpad.core.LibrarySync
import io.github.inkpad.core.Settings
import io.github.inkpad.core.Store
import io.github.inkpad.core.SyncSummary
import io.github.inkpad.core.fileLock
import io.github.inkpad.core.fileSign
import io.github.inkpad.core.merge
import io.github.inkpad.core.notebookId
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.editor.PageCanvas
import io.github.inkpad.editor.cancel
import io.github.inkpad.editor.clearSelection
import io.github.inkpad.sync.GoogleAccount
import io.github.inkpad.sync.GoogleDrive
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.withLock

/** One notebook open in the editor: the document, its canvas and its autosave (A:AppModel.swift EditorSession). */
class EditorSession(file: File, val app: AppModel) {
    var doc: Document = Document.load(file)
        private set
    var file: File = file
        private set
    var canvas: PageCanvas? = null
    val tick = mutableIntStateOf(0)   // bumped whenever pages, bookmarks or the undo state change
    var onEdit: (() -> Unit)? = null  // something was written, drawn, erased or otherwise changed
    var onReplaced: (() -> Unit)? = null

    private var unlisten: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val saveTimer = Runnable { save() }
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "save").apply { isDaemon = true } }
    /** What the file looked like when this app last wrote or read it (looked at only with fileLock held). */
    private var signature: String? = fileLock.withLock { fileSign(file) }
    private var refused = 0   // saves in a row that found another file than the one expected

    init {
        attach()
    }

    val title get() = file.nameWithoutExtension

    private fun attach() {
        unlisten = doc.listen { e ->
            if (e is Event.State || e is Event.Pages || e is Event.Meta) {
                main.post {
                    tick.intValue++
                    if (doc.modified) {
                        scheduleSave()
                        onEdit?.invoke()
                    }
                }
            }
        }
    }

    private fun scheduleSave() {
        main.removeCallbacks(saveTimer)
        main.postDelayed(saveTimer, 1500)
    }

    /** Write the notebook if it changed. The file is written off the main thread unless [wait]
     *  asks for it to be on disk before this returns. */
    fun save(wait: Boolean = false) {
        main.removeCallbacks(saveTimer)
        if (!doc.modified) {
            if (wait) writer.submit {}.get()
            return
        }
        if (wait) writer.submit {}.get()   // so that what was being written is known to be this app's
        // Never write over what a sync brought: fold it in first.
        if (changedOnDisk() && canvas?.let { it.isBusy() } != true) runCatching { absorb() }
        val snap = doc.snapshot()
        val target = file
        val d = doc
        val force = refused >= 3
        d.savedRev = snap.rev
        val write: () -> String? = {
            fileLock.withLock {
                try {
                    // Only over the file as this app knows it; a sync may have written it since.
                    // (After a few tries it is written all the same, rather than never.)
                    val sign = Document.writeSnapshot(target, snap, if (target == file && !force) signature ?: fileSign(target) else null)
                    if (target == file) signature = sign
                    null
                } catch (e: Changed) {
                    "changed"
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
        }
        if (wait) {
            val result = writer.submit<String?> { write() }.get()
            finishSave(d, result)
            if (result == "changed" && canvas?.isBusy() != true && refused <= 3) save(true)
        } else {
            writer.execute {
                val result = write()
                main.post { finishSave(d, result) }
            }
        }
    }

    private fun finishSave(d: Document, result: String?) {
        when (result) {
            null -> refused = 0
            "changed" -> {
                // Nothing was written. What the sync brought is taken in, and then this is saved again.
                refused++
                d.savedRev = -1
                if (d === doc) scheduleSave()
            }
            else -> {
                d.savedRev = -1   // still to be written
                app.show(trf("自动保存失败：%s", result))
            }
        }
    }

    fun close() {
        canvas?.cancel()
        save(wait = true)
        canvas?.viewMemo()?.let { app.rememberView(doc.id, it) }
        unlisten?.invoke()
        canvas?.release()
        writer.shutdown()
    }

    /** The file was renamed or moved (by the user here, or by a sync). */
    fun moved(to: File, sameContent: Boolean = true) = fileLock.withLock {
        file = to
        doc.file = to
        signature = if (sameContent) fileSign(to) else null
    }

    /** Whether something other than this editor has written the file. */
    fun changedOnDisk(): Boolean = fileLock.withLock { fileSign(file) != signature }

    /** Take in what a sync wrote to the file: load it again, or, when there are edits here that
     *  it does not have, fold it into them item by item. */
    fun absorb() {
        val loaded = fileLock.withLock {
            Document.load(file).also { signature = fileSign(file) }
        }
        if (doc.modified) {
            canvas?.cancel()
            canvas?.clearSelection()
            merge(doc, loaded)
            doc.emit(Event.Pages)
            for (page in doc.pages) doc.emit(Event.Dirty(page, null))
            tick.intValue++
            scheduleSave()
            return
        }
        unlisten?.invoke()
        val memo = canvas?.viewMemo()
        doc = loaded
        canvas?.setDocument(loaded)
        memo?.let { canvas?.restoreMemo(it) }
        attach()
        tick.intValue++
        onReplaced?.invoke()
    }
}

private fun PageCanvas.isBusy() = busy()

/** The app: the library, the open notebook, sync, and what is shown over everything. */
class AppModel(val context: Context) {
    val root: File = File(context.filesDir, "Inkpad").apply { mkdirs() }
    val store = Store(root)
    private val settingsFile = File(context.filesDir, "settings.json")
    val settings: MutableMap<String, Any?> = Settings.load(settingsFile)
    val settingsTick = mutableIntStateOf(0)
    private val main = Handler(Looper.getMainLooper())

    var session by mutableStateOf<EditorSession?>(null)
    val libraryTick = mutableIntStateOf(0)
    var toast by mutableStateOf<String?>(null)
    var toastButton by mutableStateOf<String?>(null)
    var toastAction: (() -> Unit)? = null
    var alert by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var connected by mutableStateOf(GoogleAccount.connected(context))
    var syncing by mutableStateOf(false)
    var syncFraction by mutableStateOf(0.0)
    var syncText by mutableStateOf("")
    var syncError by mutableStateOf<String?>(null)
    var lastSync by mutableStateOf(LibrarySync.lastSync(root))

    private val hideToast = Runnable { toast = null }
    private var syncThread: Thread? = null
    private var syncChecked = false                       // whether a thorough sync has run since the app started
    private var syncNext: Pair<Boolean, Boolean>? = null  // a sync asked for while one was running: (quiet, only)
    private val editTimer = Runnable { editTimerFired() }
    private var editPending = false
    private val watchTimer = object : Runnable {
        override fun run() {
            if (session == null) return
            if (!editPending && !syncing && foreground) syncOpen()
            main.postDelayed(this, (settings.syncEvery() * 1000).toLong())
        }
    }
    private var openSynced = 0L                           // when the open notebook was last synced,
    private var openTook = 0L                             // and how long that took (ms)
    var foreground = true

    init {
        // Every ten minutes while the library is showing, as the desktop does.
        main.postDelayed(object : Runnable {
            override fun run() {
                if (session == null && foreground) sync(quiet = true)
                main.postDelayed(this, 600_000)
            }
        }, 600_000)
    }

    fun saveSettings() {
        Settings.save(settingsFile, settings)
        settingsTick.intValue++
    }

    // The input log (settings: "record input log"): lines are gathered and written a little later,
    // so that logging costs the pen nothing; the last 300 KB are kept.
    private val logLines = ArrayList<String>()
    private var logPending = false
    fun inputLogFile() = File(context.filesDir, "input.log")

    fun logInput(line: String) {
        synchronized(logLines) { logLines.add(line) }
        if (logPending) return
        logPending = true
        main.postDelayed({
            logPending = false
            val lines = synchronized(logLines) { ArrayList(logLines).also { logLines.clear() } }
            Thread {
                val f = inputLogFile()
                val old = if (f.exists()) f.readText() else ""
                f.writeText((old + lines.joinToString("\n") + "\n").takeLast(300_000))
            }.start()
        }, 1000)
    }

    fun rememberView(id: String, memo: List<Double>) {
        @Suppress("UNCHECKED_CAST")
        val views = (settings["views"] as? MutableMap<String, Any?>) ?: LinkedHashMap<String, Any?>().also { settings["views"] = it }
        views.remove(id)
        views[id] = memo
        while (views.size > 300) views.remove(views.keys.first())
        saveSettings()
    }

    fun viewOf(id: String): List<Double>? = ((settings["views"] as? Map<*, *>)?.get(id) as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() }

    fun show(text: String, button: String? = null, action: (() -> Unit)? = null) {
        toast = text
        toastButton = button
        toastAction = action
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, if (button == null) 3000 else 5000)
    }

    fun refreshLibrary() {
        libraryTick.intValue++
    }

    // -- notebooks ----------------------------------------------------------------------------

    fun open(f: File) {
        try {
            val s = EditorSession(f, this)
            s.onEdit = { syncAfterEdit() }
            session = s
            syncOpen()   // so that it shows what other devices wrote in it
            main.removeCallbacks(watchTimer)
            main.postDelayed(watchTimer, (settings.syncEvery() * 1000).toLong())
        } catch (e: Exception) {
            alert = tr("无法打开笔记本") + "\n" + (e.message ?: e.toString())
        }
    }

    fun closeEditor() {
        session?.close()
        session = null
        main.removeCallbacks(watchTimer)
        main.removeCallbacks(editTimer)
        editPending = false
        refreshLibrary()
        sync(quiet = true)
    }

    // -- sync ---------------------------------------------------------------------------------

    /** Merge the library with Google Drive. The open notebook syncs like any other: it is saved
     *  first, and what the sync then does to its file is taken into the editor when the sync is
     *  over (and before any later save). */
    fun sync(quiet: Boolean, only: Boolean = false) {
        connected = GoogleAccount.connected(context)
        if (!connected) {
            if (!quiet) alert = tr("尚未连接 Google Drive")
            return
        }
        if (syncing) {
            // Not forgotten: it runs when this one is over. The whole library outranks one notebook.
            val next = syncNext
            syncNext = Pair(quiet && (next?.first ?: true), only && (next?.second ?: true))
            return
        }
        val one = only && session != null
        val began = System.currentTimeMillis()
        syncing = true
        syncFraction = 0.0
        syncText = ""
        syncError = null
        val s = session
        if (s != null && s.canvas?.isBusy() != true) s.save(wait = true)
        val which = if (one) s?.let { listOf(it.file) } else null
        // Thorough when the user asks for a sync, and once when the app has started; quick otherwise
        // (the sync decides for itself when it is time to look again).
        val thorough: Boolean? = if (!quiet || !syncChecked) true else null
        if (!one) syncChecked = true
        var lastReport = 0L
        syncThread = Thread {
            var summary: SyncSummary? = null
            var failure: String? = null
            try {
                summary = LibrarySync.sync(root, GoogleDrive(context), only = which, folder = GoogleAccount.folderId(context), thorough = thorough) { fraction, text ->
                    // A transfer reports every few kilobytes; the screen need not hear of each.
                    val now = System.currentTimeMillis()
                    if (now - lastReport >= 100 || fraction >= 1.0) {
                        lastReport = now
                        main.post {
                            syncFraction = fraction
                            syncText = text
                        }
                    }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            main.post {
                syncing = false
                syncThread = null
                lastSync = LibrarySync.lastSync(root)
                if (!one) {
                    store.reloadMeta()
                    refreshLibrary()
                }
                followOpenNotebook(!one)
                openSynced = System.currentTimeMillis()
                if (one) openTook = openSynced - began
                syncNext?.let { next ->
                    syncNext = null
                    main.post { sync(next.first, next.second) }
                }
                if (failure != null) {
                    syncError = failure
                    if (!quiet) alert = trf("同步失败：%s", failure)
                } else if (summary != null && !quiet) {
                    show(if (summary.quiet) tr("已是最新") else trf("上传 %d，下载 %d，合并 %d", summary.up, summary.down, summary.merged))
                }
            }
        }.apply {
            name = "sync"
            isDaemon = true
            start()
        }
    }

    // A notebook that is open is one that is being worked on: it is synced by itself when it is
    // opened, a moment after every pause in writing, and looked up on Drive every half minute (or as
    // often as the settings say) for what other devices wrote. Only that notebook is touched.

    fun syncOpen() {
        if (session != null && GoogleAccount.connected(context)) sync(quiet = true, only = true)
    }

    /** Sync the open notebook once the pen has rested: after the autosave, and not sooner after the
     *  last sync than that one took, so that a large notebook is not sent non-stop. */
    fun syncAfterEdit() {
        if (!connected) return
        main.removeCallbacks(editTimer)
        val wait = maxOf(6000L, openSynced + 4 * openTook - System.currentTimeMillis())
        editPending = true
        main.postDelayed(editTimer, wait)
    }

    private fun editTimerFired() {
        editPending = false
        val s = session ?: return
        if (s.canvas?.isBusy() == true || syncing) syncAfterEdit() else syncOpen()
    }

    /** Started again when the interval is changed in the settings. */
    fun watchOpen() {
        main.removeCallbacks(watchTimer)
        if (session != null) main.postDelayed(watchTimer, (settings.syncEvery() * 1000).toLong())
    }

    /** After a sync the open notebook may have been replaced, merged, moved or deleted. */
    private fun followOpenNotebook(announce: Boolean) {
        val s = session ?: return
        if (s.canvas?.isBusy() == true) {
            // In the middle of a stroke; look again in a moment.
            main.postDelayed({ followOpenNotebook(announce) }, 400)
            return
        }
        if (!s.file.exists()) {
            val id = s.doc.id
            val found = LibrarySync.scan(root).first.values.firstOrNull { runCatching { notebookId(it) }.getOrNull() == id }
            if (found != null) {
                s.moved(found, sameContent = false)   // renamed or moved on another device
            } else {
                // Deleted on another device: it is in the trash here now.
                session = null
                show(tr("已删除"))
                return
            }
        }
        if (s.changedOnDisk()) {
            try {
                s.absorb()
                if (announce) show(tr("此笔记本已在其他设备上更改，已重新载入"))
            } catch (e: Exception) {
                alert = tr("无法打开笔记本") + "\n" + (e.message ?: e.toString())
            }
        }
    }

    /** Run a job that talks to Drive while nothing else may sync (the storage manager's). */
    fun withDrive(job: (GoogleDrive, (Double, String) -> Unit) -> Unit, done: (Exception?) -> Unit) {
        if (syncing) {
            done(Exception(tr("正在同步，请等同步结束后再试")))
            return
        }
        syncing = true
        syncFraction = 0.0
        Thread {
            var failure: Exception? = null
            try {
                job(GoogleDrive(context)) { f, t -> main.post { syncFraction = f; syncText = t } }
            } catch (e: Exception) {
                failure = e
            }
            main.post {
                syncing = false
                refreshLibrary()
                done(failure)
            }
        }.start()
    }

    fun phaseChanged(visible: Boolean) {
        foreground = visible
        if (!visible) {
            session?.save(wait = true)
            sync(quiet = true)
        } else if (connected && !syncing && System.currentTimeMillis() / 1000.0 - (lastSync ?: 0.0) > 120) {
            sync(quiet = true)
        }
    }
}

fun Map<String, Any?>.syncEvery() = ((this["sync_every"] as? Number)?.toDouble() ?: 30.0).coerceIn(5.0, 300.0)
