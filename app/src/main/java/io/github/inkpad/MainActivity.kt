package io.github.inkpad

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.inkpad.core.tr
import io.github.inkpad.editor.EditorScreen
import io.github.inkpad.library.LibraryScreen
import io.github.inkpad.render.I18n
import io.github.inkpad.ui.DialogButton
import io.github.inkpad.ui.DialogCard
import io.github.inkpad.ui.Icons
import io.github.inkpad.ui.InkpadTheme
import io.github.inkpad.ui.Label
import io.github.inkpad.ui.SettingsScreen
import io.github.inkpad.ui.Toast
import io.github.inkpad.ui.palette
import java.io.File

/** Picking files and handing files out, through the system's own pickers and share sheet. */
object Files {
    internal var activity: MainActivity? = null
    private var onOpen: ((List<Uri>) -> Unit)? = null
    private var onCreate: ((Uri?) -> Unit)? = null
    private var onTree: ((Uri?) -> Unit)? = null

    fun open(types: Array<String>, multiple: Boolean = false, then: (List<Uri>) -> Unit) {
        onOpen = then
        val a = activity ?: return
        if (multiple) a.openMany.launch(types) else a.openOne.launch(types)
    }

    fun create(name: String, mime: String, then: (Uri?) -> Unit) {
        onCreate = then
        activity?.let { a -> a.creators.getOrPut(mime) { error("no creator for $mime") }.launch(name) }
    }

    fun tree(then: (Uri?) -> Unit) {
        onTree = then
        activity?.openTree?.launch(null)
    }

    internal fun opened(uris: List<Uri>) = onOpen?.invoke(uris).also { onOpen = null }
    internal fun created(uri: Uri?) = onCreate?.invoke(uri).also { onCreate = null }
    internal fun treed(uri: Uri?) = onTree?.invoke(uri).also { onTree = null }

    /** A picked file's name. */
    fun name(uri: Uri): String {
        val a = activity ?: return uri.lastPathSegment ?: "file"
        a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** A picked file copied to the cache, where the rest of the app can read it as a file. */
    fun copyIn(uri: Uri): File {
        val a = activity!!
        val dir = File(a.cacheDir, "in").apply { mkdirs() }
        val f = File(dir, name(uri).replace("/", "-"))
        a.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    fun write(uri: Uri, from: File) {
        activity!!.contentResolver.openOutputStream(uri, "wt")!!.use { out -> from.inputStream().use { it.copyTo(out) } }
    }

    fun write(uri: Uri, bytes: ByteArray) {
        activity!!.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    /** Offer a file to other apps (the share sheet). */
    fun share(file: File, mime: String) {
        val a = activity ?: return
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, null))
    }

    fun shareMany(files: List<File>, mime: String) {
        val a = activity ?: return
        if (files.size == 1) return share(files[0], mime)
        val uris = ArrayList(files.map { FileProvider.getUriForFile(a, a.packageName + ".files", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, null))
    }

    /** A fresh folder in the cache for files about to be handed out. */
    fun outbox(): File = File(activity!!.cacheDir, "out/" + System.nanoTime()).apply { mkdirs() }
}

class MainActivity : ComponentActivity() {
    internal val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { Files.opened(listOfNotNull(it)) }
    internal val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { Files.opened(it) }
    internal val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { Files.treed(it) }
    internal val creators = linkedMapOf(
        "application/pdf" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { Files.created(it) },
        "image/png" to registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { Files.created(it) },
        "application/zip" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { Files.created(it) },
        "application/octet-stream" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { Files.created(it) },
        "application/json" to registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { Files.created(it) },
        "text/plain" to registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { Files.created(it) },
    )

    private val app get() = (application as InkpadApp).model

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferFastestRefresh()
        Files.activity = this
        Icons.load(this)
        I18n.use(this, app.settings["language"] as? String ?: "auto")
        setContent {
            val m = app
            m.settingsTick.intValue
            InkpadTheme(m.settings["theme"] as? String ?: "system") {
                val p = palette
                // Light status-bar icons on a dark window and over the editor's coloured header.
                val lightIcons = p.dark || (m.session != null && !m.showSettings)
                androidx.compose.runtime.SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !lightIcons
                }
                // A new language is a new interface: every text is looked up again.
                androidx.compose.runtime.key(m.settings["language"]) {
                Box(Modifier.fillMaxSize().background(p.viewBg)) {
                    val s = m.session
                    if (s != null) EditorScreen(m, s) else LibraryScreen(m)
                    if (m.showSettings) SettingsScreen(m) { m.showSettings = false }
                    m.alert?.let { text ->
                        DialogCard({ m.alert = null }) {
                            Label(tr("操作失败"), 17f, androidx.compose.ui.text.font.FontWeight.Bold)
                            Label(text, 14f)
                            Box(Modifier.align(Alignment.End)) { DialogButton(tr("好"), suggested = true) { m.alert = null } }
                        }
                    }
                    val toast = m.toast
                    AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 26.dp), enter = fadeIn(), exit = fadeOut()) {
                        Toast(toast ?: "", m.toastButton) {
                            m.toast = null
                            m.toastAction?.invoke()
                        }
                    }
                }
                }
            }
        }
        handle(intent)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (packageName.endsWith(".test") && ev.getToolType(ev.actionIndex) == android.view.MotionEvent.TOOL_TYPE_STYLUS) {
            val a = ev.actionMasked
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_POINTER_DOWN || a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                android.util.Log.i("InkpadInput", "window: stylus ${android.view.MotionEvent.actionToString(a)} at ${(ev.x / resources.displayMetrics.density).toInt()},${(ev.y / resources.displayMetrics.density).toInt()} pressure ${"%.3f".format(ev.pressure)} flags ${ev.flags}")
        }
        val handled = super.dispatchTouchEvent(ev)
        return handled
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (packageName.endsWith(".test") && intent.hasExtra("selftest_zoom")) {
            // Trial builds: adb shell am start -n …/io.github.inkpad.MainActivity --ef selftest_zoom 0.4 --el ms 2400
            io.github.inkpad.editor.PageCanvas.current?.get()?.selfTestZoom(intent.getFloatExtra("selftest_zoom", 0.4f).toDouble(), intent.getLongExtra("ms", 2400))
            return
        }
        handle(intent)
    }

    /** A pen wants the screen's fastest refresh (144 Hz on the vivo Pad5 Pro); some systems give an
     *  app only 60 unless it asks. The same resolution, the highest rate. */
    private fun preferFastestRefresh() {
        val d = display ?: return
        val now = d.mode
        val best = d.supportedModes.filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }.maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    /** A file handed to the app from outside: a notebook, a PDF or a Goodnotes document. */
    private fun handle(intent: Intent?) {
        val uri = (if (intent?.action == Intent.ACTION_VIEW) intent.data else null)
            ?: (if (intent?.action == Intent.ACTION_SEND) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else null)
            ?: return
        intent?.action = null
        io.github.inkpad.io.Importer.openExternal(app, uri)
    }

    override fun onStart() {
        super.onStart()
        app.phaseChanged(true)
    }

    override fun onStop() {
        super.onStop()
        app.phaseChanged(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Files.activity === this) Files.activity = null
    }
}
