package io.github.inkpad.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.inkpad.MainActivity
import io.github.inkpad.core.Drive
import io.github.inkpad.core.DriveError
import io.github.inkpad.core.DriveFile
import io.github.inkpad.core.Json
import io.github.inkpad.core.JsonOut
import io.github.inkpad.core.LibrarySync
import io.github.inkpad.core.loadJson
import io.github.inkpad.core.obj
import io.github.inkpad.core.saveJson
import io.github.inkpad.core.tr
import io.github.inkpad.core.trf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// Google sign-in and Drive, as the desktop app does them (B:gdrive.py): the user's own OAuth client
// of type "Desktop app" (the same one every device uses, so that all see the same files under the
// drive.file permission), a one-shot loopback server for the sign-in, and Drive's REST interface.
// The client file has the desktop app's shape, so the one exported there can be imported here.

object GoogleAccount {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val AUTH_URI = "https://accounts.google.com/o/oauth2/auth"
    const val TOKEN_URI = "https://oauth2.googleapis.com/token"

    private fun dir(c: Context) = File(c.noBackupFilesDir, "google").apply { mkdirs() }
    fun clientFile(c: Context) = File(dir(c), "google_client.json")
    fun tokenFile(c: Context) = File(dir(c), "google_token.json")

    /** {client_id, client_secret, auth_uri, token_uri}, or null. */
    fun client(c: Context): Map<String, Any?>? {
        val data = loadJson(clientFile(c))
        return (data["installed"] ?: data["web"]) as? Map<String, Any?>
    }

    fun connected(c: Context) = tokenFile(c).exists() && client(c) != null

    fun clientId(c: Context) = client(c)?.get("client_id") as? String ?: ""
    fun clientSecret(c: Context) = client(c)?.get("client_secret") as? String ?: ""

    /** The Drive folder the library is kept in, or "" for a folder named Inkpad at the top. It is
     *  kept with the client credentials, so that the one file carries everything another device needs. */
    fun folderId(c: Context) = loadJson(clientFile(c))["inkpad_folder"] as? String ?: ""

    fun setFolderId(c: Context, value: String) {
        val data = loadJson(clientFile(c))
        if (data.isEmpty()) throw DriveError(tr("请先填写并保存凭据"))
        data["inkpad_folder"] = value
        saveJson(clientFile(c), data)
    }

    /** Use an OAuth client typed in by hand. Changing the client drops the current sign-in. */
    fun saveClient(c: Context, id0: String, secret0: String) {
        val id = id0.trim()
        val secret = secret0.trim()
        if (!id.endsWith(".apps.googleusercontent.com")) throw DriveError(tr("客户端 ID 应以 .apps.googleusercontent.com 结尾"))
        if (clientId(c) != id) tokenFile(c).delete()
        saveJson(clientFile(c), linkedMapOf("inkpad_folder" to folderId(c), "installed" to linkedMapOf(
            "client_id" to id, "client_secret" to secret, "auth_uri" to AUTH_URI, "token_uri" to TOKEN_URI)))
    }

    /** Adopt a client file: one downloaded from the Google Cloud console, or exported by any Inkpad. */
    fun installClient(c: Context, text: String) {
        val data = LinkedHashMap(Json.parse(text).obj())
        val client = (data["installed"] ?: data["web"]) as? Map<*, *>
        if (client == null || client["client_id"] !is String || client["token_uri"] !is String) {
            throw DriveError(tr("这不是“桌面应用”类型的 OAuth 客户端凭据文件"))
        }
        // A file exported from Inkpad names the folder to sync with; one from Google does not, and
        // leaves that as it is here.
        if ("inkpad_folder" !in data) data["inkpad_folder"] = folderId(c)
        if (clientId(c) != client["client_id"]) tokenFile(c).delete()
        saveJson(clientFile(c), data)
    }

    /** The client credentials and the folder id, for setting up another device. */
    fun exportClient(c: Context): String = JsonOut.write(loadJson(clientFile(c)))

    internal fun post(url: String, fields: Map<String, String>): Map<String, Any?> {
        val body = fields.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                outputStream.use { it.write(body.toByteArray()) }
            }
        } catch (e: IOException) {
            throw DriveError(trf("无法连接 Google：%s", e.message))
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            val detail = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(300) ?: ""
            throw DriveError(trf("Google 拒绝了请求（%d）：%s", code, detail), code)
        }
        return Json.parse(conn.inputStream.readBytes().toString(Charsets.UTF_8)).obj()
    }

    private fun random(n: Int): String {
        val b = ByteArray(n)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /**
     * Run the browser consent flow and store the tokens. Blocks (call it on a thread of its own);
     * the browser is opened on the main thread by [open].
     */
    /** The port the sign-in in progress waits on for the browser; closing it gives that attempt up. */
    @Volatile private var waiting: ServerSocket? = null

    fun cancelWaiting() {
        runCatching { waiting?.close() }
        waiting = null
    }

    fun authorize(c: Context, open: (Uri) -> Unit, timeoutMs: Int = 300_000) {
        val client = client(c) ?: throw DriveError(tr("尚未提供 Google 客户端凭据"))
        val clientId = client["client_id"] as String
        val secret = client["client_secret"] as? String ?: ""
        val verifier = random(64)
        val state = random(16)
        val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            waiting = server
            server.soTimeout = timeoutMs
            val redirect = "http://127.0.0.1:${server.localPort}"
            val q = linkedMapOf("client_id" to clientId, "redirect_uri" to redirect, "response_type" to "code", "scope" to SCOPE,
                "access_type" to "offline", "prompt" to "consent", "state" to state, "code_challenge" to challenge, "code_challenge_method" to "S256")
            open(Uri.parse((client["auth_uri"] as? String ?: AUTH_URI) + "?" + q.entries.joinToString("&") { it.key + "=" + URLEncoder.encode(it.value, "UTF-8") }))
            val deadline = System.currentTimeMillis() + timeoutMs
            var result: Map<String, String>? = null
            while (result == null) {
                if (System.currentTimeMillis() > deadline) throw DriveError(tr("等待授权超时"))
                val socket = try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    throw DriveError(tr("等待授权超时"))
                }
                socket.use { s ->
                    val head = readLine(s.getInputStream())
                    val path = head.split(" ").getOrNull(1) ?: ""
                    val query = path.substringAfter('?', "").split("&").filter { it.contains('=') }.associate {
                        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                    }
                    val done = "code" in query || "error" in query
                    val text = if ("code" in query) tr("授权完成，可以关闭此页面并回到 Inkpad。") else if (done) tr("授权被取消。") else "Inkpad"
                    val body = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Inkpad</title>" +
                        "<body style=\"font-family:sans-serif;text-align:center;margin-top:18vh\"><h2>$text</h2></body>"
                    val bytes = body.toByteArray()
                    s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
                    s.getOutputStream().flush()
                    if (done) result = query
                }
            }
            // Back to the app, over the browser.
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            val r = result!!
            if (r["code"] == null || r["state"] != state) throw DriveError(tr("授权未完成"))
            val token = post(client["token_uri"] as? String ?: TOKEN_URI, mapOf(
                "client_id" to clientId, "client_secret" to secret, "code" to r["code"]!!, "code_verifier" to verifier,
                "grant_type" to "authorization_code", "redirect_uri" to redirect))
            if (token["refresh_token"] !is String) throw DriveError(tr("Google 没有返回刷新令牌，请重试"))
            saveJson(tokenFile(c), linkedMapOf("refresh_token" to token["refresh_token"], "access_token" to token["access_token"],
                "expires_at" to System.currentTimeMillis() / 1000.0 + ((token["expires_in"] as? Number)?.toDouble() ?: 3600.0) - 60))
        }
    }

    private fun readLine(input: InputStream): String {
        val b = StringBuilder()
        while (true) {
            val ch = input.read()
            if (ch < 0 || ch == '\n'.code) break
            if (ch != '\r'.code) b.append(ch.toChar())
        }
        return b.toString()
    }

    /** Open the sign-in page in a browser tab (Google refuses sign-ins in a web view). */
    fun openBrowser(c: Context, uri: Uri) {
        try {
            CustomTabsIntent.Builder().build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(c, uri)
        } catch (e: Exception) {
            c.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Forget the account: revoke the grant (best effort) and delete the stored tokens. */
    fun disconnect(c: Context) {
        val token = loadJson(tokenFile(c))["refresh_token"] as? String
        if (token != null) runCatching { post("https://oauth2.googleapis.com/revoke", mapOf("token" to token)) }
        tokenFile(c).delete()
    }
}

/** Drive's REST interface (B:gdrive.py Drive; A:Sync/Drive.swift). Every call blocks. */
class GoogleDrive(private val context: Context) : Drive {
    private val api = "https://www.googleapis.com/drive/v3"
    private val upload = "https://www.googleapis.com/upload/drive/v3/files"
    private val folderMime = "application/vnd.google-apps.folder"
    private val client = GoogleAccount.client(context) ?: throw DriveError(tr("尚未连接 Google Drive"))
    private val token: MutableMap<String, Any?> = loadJson(GoogleAccount.tokenFile(context)).also {
        if (it["refresh_token"] !is String) throw DriveError(tr("尚未连接 Google Drive"))
    }
    override var onBytes: ((Long, Long) -> Unit)? = null

    @Synchronized
    private fun access(): String {
        if (System.currentTimeMillis() / 1000.0 >= ((token["expires_at"] as? Number)?.toDouble() ?: 0.0)) {
            val fresh = GoogleAccount.post(client["token_uri"] as? String ?: GoogleAccount.TOKEN_URI, mapOf(
                "client_id" to client["client_id"] as String, "client_secret" to (client["client_secret"] as? String ?: ""),
                "refresh_token" to token["refresh_token"] as String, "grant_type" to "refresh_token"))
            token["access_token"] = fresh["access_token"]
            token["expires_at"] = System.currentTimeMillis() / 1000.0 + ((fresh["expires_in"] as? Number)?.toDouble() ?: 3600.0) - 60
            saveJson(GoogleAccount.tokenFile(context), token)
        }
        return token["access_token"] as String
    }

    private fun open(method: String, url: String, headers: Map<String, String> = emptyMap(), body: ((java.io.OutputStream) -> Unit)? = null, length: Long = -1): HttpURLConnection {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            // HttpURLConnection knows no PATCH; Google takes it in this header instead.
            if (method == "PATCH") {
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                conn.requestMethod = method
            }
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer " + access())
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            if (body != null) {
                conn.doOutput = true
                if (length >= 0) conn.setFixedLengthStreamingMode(length) else conn.setChunkedStreamingMode(0)
                conn.outputStream.use(body)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val text = conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) ?: ""
                val detail = runCatching { Json.parse(text).obj()["error"].obj()["message"] as String }.getOrElse { text.take(300) }
                throw DriveError(trf("Google Drive 错误（%d）：%s", code, detail), code)
            }
            return conn
        } catch (e: IOException) {
            throw DriveError(trf("无法连接 Google Drive：%s", e.message))
        }
    }

    private fun json(method: String, url: String, body: Any? = null): Map<String, Any?> {
        val bytes = body?.let { JsonOut.write(it).toByteArray() }
        val conn = open(method, url, if (bytes != null) mapOf("Content-Type" to "application/json") else emptyMap(),
            bytes?.let { b -> { o: java.io.OutputStream -> o.write(b) } }, bytes?.size?.toLong() ?: -1)
        return Json.parse(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).obj()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A name in a query, with \ and ' escaped. */
    private fun quoted(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    override fun alive(fileId: String): Boolean = try {
        json("GET", "$api/files/$fileId?fields=trashed")["trashed"] != true
    } catch (e: DriveError) {
        if (e.code == 404) false else throw e
    }

    override fun children(parent: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var page: String? = null
        while (true) {
            var q = "$api/files?q=${enc("'${quoted(parent)}' in parents and trashed = false")}&pageSize=1000&fields=${enc("nextPageToken, files(id, name, mimeType, md5Checksum)")}"
            if (page != null) q += "&pageToken=${enc(page)}"
            val data = json("GET", q)
            for (f in (data["files"] as? List<*>) ?: emptyList<Any?>()) {
                val m = f as Map<*, *>
                out.add(DriveFile(m["id"] as String, m["name"] as? String ?: "", m["md5Checksum"] as? String))
            }
            page = data["nextPageToken"] as? String ?: return out
        }
    }

    override fun md5(fileId: String) = json("GET", "$api/files/$fileId?fields=md5Checksum")["md5Checksum"] as? String

    override fun root(): String {
        val q = "name = '${LibrarySync.ROOT_NAME}' and mimeType = '$folderMime' and 'root' in parents and trashed = false"
        val found = json("GET", "$api/files?q=${enc(q)}&fields=${enc("files(id)")}")["files"] as? List<*>
        val first = found?.firstOrNull() as? Map<*, *>
        return first?.get("id") as? String
            ?: json("POST", "$api/files?fields=id", linkedMapOf("name" to LibrarySync.ROOT_NAME, "mimeType" to folderMime, "parents" to listOf("root")))["id"] as String
    }

    override fun find(name: String, parent: String): String? {
        val q = "name = '${quoted(name)}' and '${quoted(parent)}' in parents and trashed = false"
        val found = json("GET", "$api/files?q=${enc(q)}&fields=${enc("files(id)")}")["files"] as? List<*>
        return (found?.firstOrNull() as? Map<*, *>)?.get("id") as? String
    }

    override fun getJSON(fileId: String): Map<String, Any?> {
        val conn = open("GET", "$api/files/$fileId?alt=media")
        return Json.parse(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).obj()
    }

    override fun putJSON(name: String, value: Map<String, Any?>, parent: String, fileId: String?): String {
        val boundary = "inkpad" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val meta: Map<String, Any?> = if (fileId != null) emptyMap() else linkedMapOf("name" to name, "parents" to listOf(parent))
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JsonOut.write(meta)}\r\n" +
            "--$boundary\r\nContent-Type: application/json\r\n\r\n${JsonOut.write(value)}\r\n--$boundary--").toByteArray()
        val url = upload + (if (fileId != null) "/$fileId" else "") + "?uploadType=multipart&fields=id"
        val conn = open(if (fileId != null) "PATCH" else "POST", url, mapOf("Content-Type" to "multipart/related; boundary=$boundary"),
            { it.write(body) }, body.size.toLong())
        return Json.parse(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).obj()["id"] as String
    }

    override fun rename(fileId: String, name: String) {
        json("PATCH", "$api/files/$fileId?fields=id", linkedMapOf("name" to name))
    }

    /** Create or replace a file with a resumable upload; returns (id, md5). */
    override fun upload(file: File, name: String, parent: String, fileId: String?): Pair<String, String?> {
        val (url, method, meta) = if (fileId != null) Triple("$upload/$fileId?uploadType=resumable&fields=id,md5Checksum", "PATCH", emptyMap<String, Any?>())
        else Triple("$upload?uploadType=resumable&fields=id,md5Checksum", "POST", linkedMapOf("name" to name, "parents" to listOf(parent)))
        val size = file.length()
        val metaBytes = JsonOut.write(meta).toByteArray()
        val start = open(method, url, mapOf("Content-Type" to "application/json; charset=UTF-8", "X-Upload-Content-Type" to "application/zip",
            "X-Upload-Content-Length" to size.toString()), { it.write(metaBytes) }, metaBytes.size.toLong())
        val session = start.getHeaderField("Location") ?: throw DriveError(tr("Google Drive 没有给出上传地址"))
        start.inputStream.close()
        val conn = open("PUT", session, mapOf("Content-Type" to "application/zip"), { out ->
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 18)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onBytes?.invoke(sent, size)
                }
            }
        }, size)
        val data = Json.parse(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)).obj()
        return Pair(data["id"] as String, data["md5Checksum"] as? String)
    }

    override fun download(fileId: String, dest: File) {
        val conn = open("GET", "$api/files/$fileId?alt=media")
        val total = conn.contentLengthLong
        val part = File(dest.path + ".part")
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 18)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    if (total > 0) onBytes?.invoke(got, total)
                }
            }
        }
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
    }
}
