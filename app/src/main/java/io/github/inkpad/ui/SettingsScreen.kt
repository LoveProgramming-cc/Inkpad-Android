package io.github.inkpad.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.inkpad.AppModel
import io.github.inkpad.Files
import io.github.inkpad.core.CloudDeleted
import io.github.inkpad.core.LibrarySync
import io.github.inkpad.core.Store
import io.github.inkpad.core.int
import io.github.inkpad.core.str
import io.github.inkpad.core.strings
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import io.github.inkpad.io.Exporter
import io.github.inkpad.io.Importer
import io.github.inkpad.library.fmtDate
import io.github.inkpad.render.I18n
import io.github.inkpad.sync.Connect
import io.github.inkpad.sync.GoogleAccount

// The settings (B:prefs.py PrefsDialog; W:src/ui/prefs.js), as a full-screen page.

private val TOOL_ROWS = listOf("lasso" to "套索", "eraser" to "橡皮擦", "hl" to "荧光笔", "shape" to "形状", "text" to "文本", "note" to "便签",
    "tape" to "胶带", "laser" to "激光笔", "image" to "插入图片", "ruler" to "尺子")
private val EVERY = listOf(5 to "5 秒", 10 to "10 秒", 15 to "15 秒", 30 to "30 秒", 60 to "1 分钟", 120 to "2 分钟", 300 to "5 分钟")

fun sizeText(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB")
    var n = bytes.toDouble()
    var i = 0
    while (n >= 1024 && i < units.size - 1) {
        n /= 1024
        i++
    }
    return (if (i > 0) "%.1f".format(n) else n.toLong().toString()) + " " + units[i]
}

@Composable
fun SettingsScreen(app: AppModel, close: () -> Unit) {
    app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val st = app.settings
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    tick.let { }
    app.settingsTick.intValue
    var managing by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf<Triple<String, String, () -> Unit>?>(null) }
    fun set(key: String, v: Any?) {
        st[key] = v
        app.saveSettings()
        tick++
    }
    BackHandler { if (managing) managing = false else close() }
    Box(Modifier.fillMaxSize().background(p.windowBg)) {
        if (managing) {
            StorageManager(app) { managing = false }
        } else Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clip(CircleShape).clickable(onClick = close).padding(8.dp)) { Icon("back") }
                Spacer(Modifier.width(8.dp))
                Label(tr("设置"), 18f, FontWeight.Bold)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                    SettingGroup(tr("外观")) {
                        SettingRow(tr("外观"), tr("页面本身始终保持纸张颜色")) {
                            DropDown(listOf(tr("跟随系统"), tr("浅色"), tr("深色")), listOf("system", "light", "dark").indexOf(st.str("theme")).coerceAtLeast(0)) {
                                set("theme", listOf("system", "light", "dark")[it])
                            }
                        }
                        SettingRow(tr("语言")) {
                            val langs = listOf("auto" to tr("自动"), "zh" to "中文", "en" to "English", "ja" to "日本語")
                            DropDown(langs.map { it.second }, langs.indexOfFirst { it.first == st.str("language") }.coerceAtLeast(0)) {
                                set("language", langs[it].first)
                                I18n.use(context, langs[it].first)
                                app.refreshLibrary()
                            }
                        }
                    }
                    SettingGroup(tr("书写")) {
                        for ((key, title, sub) in listOf(
                            Triple("scribble_erase", "涂画以擦除", null),
                            Triple("scribble_loose", "宽松的涂抹删除", "使用旧的判定方式：更容易删掉，但连笔写字时可能被当成涂抹"),
                            Triple("hold_shape", "长按绘制规则形状", "画完停住片刻，笔迹变成直线、矩形或椭圆"),
                            Triple("finger_draw", "用手指书写", "打开后单指书写，双指滚动和缩放"),
                            Triple("crosshair", "显示十字光标", null),
                            Triple("mouse_hand", "有手写笔时鼠标不书写", "用过手写笔之后，鼠标只用来移动页面"),
                            Triple("scrollbars", "显示滚动条", null),
                        )) {
                            SettingRow(tr(title), sub?.let { tr(it) }) { Switch(st[key] == true) { set(key, it) } }
                        }
                        SettingRow(tr("翻页方向")) {
                            DropDown(listOf(tr("上下滚动"), tr("左右翻页")), if (st.str("paging") == "horizontal") 1 else 0) { set("paging", if (it == 1) "horizontal" else "vertical") }
                        }
                        SettingRow(tr("最大放大倍数")) {
                            val zooms = listOf(6.0, 10.0, 20.0, 40.0)
                            DropDown(zooms.map { "${(it * 100).toInt()}%" }, zooms.indexOfFirst { kotlin.math.abs(it - ((st["max_zoom"] as? Number)?.toDouble() ?: 6.0)) < 0.01 }.coerceAtLeast(0)) { set("max_zoom", zooms[it]) }
                        }
                    }
                    GoogleGroup(app) { tick++ }
                    SettingGroup(tr("储存空间")) {
                        SettingRow(tr("用量"), sizeText(app.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }))
                        SettingRow(tr("导出整个文稿库…"), onClick = { Exporter.exportLibrary(app) }) { Icon("share") }
                        SettingRow(tr("导入存档（.zip）…"), onClick = { Importer.importArchive(app, app.root) }) { Icon("documents") }
                        SettingRow(tr("管理储存空间…"), tr("恢复已删除的笔记"), onClick = { managing = true }) { Icon("chevron_right") }
                    }
                    SettingGroup(tr("工具栏"), ) {
                        val hidden = st.strings("hidden_tools")
                        for ((name, label) in TOOL_ROWS) {
                            SettingRow(tr(label)) {
                                Switch(name !in hidden) { on ->
                                    set("hidden_tools", if (on) hidden - name else hidden + name)
                                }
                            }
                        }
                    }
                    SettingGroup(tr("手写笔")) {
                        SettingRow(tr("按住笔上的按键时擦除"), tr("按键按住时笔变成橡皮擦")) { Switch(st["pen_button_erases"] != false) { set("pen_button_erases", it) } }
                    }
                    SettingGroup(tr("高级")) {
                        SettingRow(tr("防误触时间"), trf("笔离开屏幕后 %d 毫秒内忽略手指", st.int("palm_ms"))) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PillButton(onClick = { set("palm_ms", (st.int("palm_ms") - 10).coerceIn(0, 1000).toLong()) }) { Icon("minus", size = 16.dp) }
                                Label("${st.int("palm_ms")}", 14f, modifier = Modifier.widthIn(min = 40.dp))
                                PillButton(onClick = { set("palm_ms", (st.int("palm_ms") + 10).coerceIn(0, 1000).toLong()) }) { Icon("plus", size = 16.dp) }
                            }
                        }
                        SettingRow(tr("记录输入日志"), tr("记下笔和手指的每一个动作，用于排查书写问题")) { Switch(st["input_log"] == true) { set("input_log", it) } }
                        SettingRow(tr("下载日志"), onClick = {
                            val f = app.inputLogFile()
                            if (f.exists()) Exporter.save(app, f, "input.log", "text/plain") else app.show(tr("还没有日志"))
                        }) { Icon("share") }
                        SettingRow(tr("清空日志"), onClick = {
                            app.inputLogFile().delete()
                            app.show(tr("已清空"))
                        }) { Icon("trash") }
                    }
                    SettingGroup(tr("关于")) {
                        SettingRow("Inkpad", tr("手写笔记、白板与 PDF 批注。") + " GPL-3.0")
                    }
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
        ask?.let { (title, message, then) ->
            Confirm(title, message, tr("确定")) { ok ->
                ask = null
                if (ok) then()
            }
        }
    }
}

@Composable
private fun GoogleGroup(app: AppModel, changed: () -> Unit) {
    app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val context = LocalContext.current
    val p = palette
    var id by remember { mutableStateOf(GoogleAccount.clientId(context)) }
    var secret by remember { mutableStateOf(GoogleAccount.clientSecret(context)) }
    var folder by remember { mutableStateOf(GoogleAccount.folderId(context)) }
    var showFolder by remember { mutableStateOf(false) }
    var confirmFolder by remember { mutableStateOf<String?>(null) }
    val st = app.settings
    fun commit(): Boolean = try {
        if (id.isNotBlank()) GoogleAccount.saveClient(context, id, secret)
        app.connected = GoogleAccount.connected(context)
        true
    } catch (e: Exception) {
        app.alert = tr("无法保存") + "\n" + e.message
        false
    }
    SettingGroup(tr("Google Drive 同步")) {
        Label(tr("同步使用你自己的 Google Cloud OAuth 客户端：与桌面版相同的“桌面应用”类型客户端（或导入同一个 google_client.json）。应用只申请访问它自己创建的文件，所以各设备要用同一个客户端，才能看到同一批笔记。"),
            13f, color = p.ink(0.65f), modifier = Modifier.padding(16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(id, { id = it }, tr("客户端 ID"))
            Field(secret, { secret = it }, tr("客户端密钥"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(tr("保存凭据")) { if (commit()) app.show(tr("已保存")) }
                DialogButton(tr("从 JSON 导入")) {
                    Files.open(arrayOf("application/json", "*/*")) { uris ->
                        val uri = uris.firstOrNull() ?: return@open
                        try {
                            GoogleAccount.installClient(context, context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                            id = GoogleAccount.clientId(context)
                            secret = GoogleAccount.clientSecret(context)
                            folder = GoogleAccount.folderId(context)
                            app.connected = GoogleAccount.connected(context)
                            app.show(tr("已导入凭据"))
                        } catch (e: Exception) {
                            app.alert = tr("无法使用这个凭据文件") + "\n" + e.message
                        }
                    }
                }
                DialogButton(tr("导出为 JSON")) {
                    val f = java.io.File(Files.outbox(), "google_client.json")
                    f.writeText(GoogleAccount.exportClient(context))
                    Exporter.save(app, f, "google_client.json", "application/json")
                }
            }
        }
        val syncedTo = LibrarySync.cloudFolder(app.root)
        SettingRow(tr("当前同步文件夹"), syncedTo.ifEmpty { tr("尚未同步") }, onClick = if (syncedTo.isNotEmpty()) {
            { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/drive/folders/" + Uri.encode(syncedTo))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else null) { if (syncedTo.isNotEmpty()) Icon("share") }
        SettingRow(tr("如何把笔记放到云端硬盘的其它位置"), onClick = { showFolder = !showFolder }) { Icon(if (showFolder) "chevron" else "chevron_right") }
        if (showFolder) Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(tr("可先留空同步一次，把生成的“Inkpad”文件夹在 Google Drive 里移到想放的位置，再把它的 ID（或网址）填在这里。"), 13f, color = p.ink(0.65f))
            Field(folder, { folder = it }, tr("留空：“我的云端硬盘”里名为 Inkpad 的文件夹"))
            Box(Modifier.align(Alignment.End)) {
                DialogButton(tr("确定")) {
                    try {
                        val wanted = LibrarySync.parseFolder(folder)
                        if (wanted != GoogleAccount.folderId(context)) confirmFolder = wanted
                    } catch (e: Exception) {
                        app.alert = e.message
                    }
                }
            }
        }
        SettingRow(tr("打开的笔记本多久同步一次"), tr("每隔这么久查看一次其它设备写了什么；自己写的内容在停笔几秒后就会同步，与这里无关")) {
            DropDown(EVERY.map { tr(it.second) }, EVERY.indexOfFirst { it.first == st.int("sync_every") }.coerceAtLeast(3)) {
                st["sync_every"] = EVERY[it].first.toLong()
                app.saveSettings()
                app.watchOpen()
                changed()
            }
        }
        val status = when {
            !app.connected -> tr("尚未连接 Google Drive")
            app.syncing -> tr("正在同步…")
            app.syncError != null -> tr("上次同步失败：") + app.syncError
            app.lastSync != null -> tr("上次同步：") + fmtDate(tr("%-m月%-d日 %H:%M"), (app.lastSync!! * 1000).toLong())
            else -> tr("已连接，尚未同步")
        }
        SettingRow(tr("状态"), status)
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (app.connected) {
                DialogButton(tr("断开连接")) {
                    Thread {
                        GoogleAccount.disconnect(context)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            app.connected = false
                            app.syncError = null
                            app.show(tr("已断开 Google Drive；本机和云端的笔记都保留"))
                        }
                    }.start()
                }
                DialogButton(tr("立即同步"), suggested = true, enabled = !app.syncing) { app.sync(quiet = false) }
            } else {
                DialogButton(tr("连接 Google Drive…"), suggested = true) { if (commit()) Connect.start(app) }
            }
        }
        Label(tr("如果 Google 每 7 天要求重新授权一次，那是因为 Google Cloud 项目的 OAuth 同意屏幕处于“测试”状态；发布为“正式版”（个人使用无需审核）即可。"),
            12f, color = p.ink(0.55f), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
    }
    confirmFolder?.let { wanted ->
        Confirm(tr("更改同步文件夹？"), tr("这台设备会把文稿库与新的文件夹重新比对。云端和本机的笔记都不会被删除。"), tr("更改")) { ok ->
            confirmFolder = null
            if (ok) {
                try {
                    GoogleAccount.setFolderId(context, wanted)
                } catch (e: Exception) {
                    app.alert = e.message
                }
            } else folder = GoogleAccount.folderId(context)
        }
    }
}

/** What was deleted: kept on this device until the cache is cleared, and kept on Drive. */
@Composable
private fun StorageManager(app: AppModel, back: () -> Unit) {
    app.settingsTick.intValue   // the settings are a plain map: this is what redraws it
    val p = palette
    val store = app.store
    var tick by remember { mutableIntStateOf(0) }
    tick.let { }
    val cloud = remember { mutableStateListOf<CloudDeleted>() }
    val chosen = remember { mutableStateListOf<String>() }
    var loading by remember { mutableStateOf(false) }
    var clearAsk by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clip(CircleShape).clickable(onClick = back).padding(8.dp)) { Icon("back") }
            Spacer(Modifier.width(8.dp))
            Label(tr("管理储存空间"), 18f, FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                val archived = store.archived()
                SettingGroup(tr("本机已删除的笔记")) {
                    SettingRow(trf("共 %d 项，%s", archived.size, sizeText(store.cacheSize())))
                    for (e in archived) {
                        SettingRow(Store.archivedName(e.name), store.archivedOrigin(e.file).let { if (it == ".") tr("文稿") else it }) {
                            DialogButton(tr("恢复")) {
                                try {
                                    store.unarchive(e.file)
                                    app.refreshLibrary()
                                    tick++
                                } catch (x: Exception) {
                                    app.alert = x.message
                                }
                            }
                        }
                    }
                    if (archived.isNotEmpty()) Box(Modifier.padding(16.dp)) { DialogButton(tr("清理缓存…"), danger = true) { clearAsk = true } }
                }
                SettingGroup(tr("云端保留的已删除笔记")) {
                    if (!app.connected) SettingRow(tr("尚未连接 Google Drive"))
                    else {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DialogButton(tr("读取云端目录"), enabled = !loading) {
                                loading = true
                                var found: List<CloudDeleted> = emptyList()
                                app.withDrive({ drive, _ -> found = LibrarySync.cloudDeleted(app.root, drive, GoogleAccount.folderId(app.context)) }) { e ->
                                    loading = false
                                    if (e != null) app.alert = e.message
                                    cloud.clear()
                                    cloud.addAll(found)
                                    chosen.clear()
                                }
                            }
                            DialogButton(tr("下载所选"), suggested = true, enabled = chosen.isNotEmpty() && !app.syncing) {
                                val ids = chosen.toList()
                                var n = 0
                                app.withDrive({ drive, progress -> n = LibrarySync.cloudRestore(app.root, drive, ids, GoogleAccount.folderId(app.context), progress) }) { e ->
                                    if (e != null) app.alert = e.message else app.show(trf("已恢复 %d 本笔记", n))
                                    cloud.removeAll { it.id in ids }
                                    chosen.clear()
                                }
                            }
                        }
                        for (c in cloud) {
                            SettingRow(c.path, fmtDate(tr("%Y年%-m月%-d日 %H:%M"), (c.ts * 1000).toLong()), onClick = { if (c.id in chosen) chosen.remove(c.id) else chosen.add(c.id) }) {
                                Switch(c.id in chosen) { if (it) chosen.add(c.id) else chosen.remove(c.id) }
                            }
                        }
                    }
                }
            }
        }
    }
    if (clearAsk) Confirm(tr("清理缓存？"), tr("这些笔记将从这台设备上彻底删除。云端的副本不受影响。"), tr("清理"), danger = true) { ok ->
        clearAsk = false
        if (ok) {
            store.clearCache()
            tick++
        }
    }
}
