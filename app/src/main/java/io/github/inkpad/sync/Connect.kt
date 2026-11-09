package io.github.inkpad.sync

import android.os.Handler
import android.os.Looper
import io.github.inkpad.AppModel
import io.github.inkpad.core.tr

/** Signing in to Google: the browser opens, the app waits for it on a thread of its own. */
object Connect {
    @Volatile private var running = false

    fun start(app: AppModel) {
        val c = app.context
        if (GoogleAccount.client(c) == null) {
            app.alert = tr("请先填写并保存凭据")
            app.showSettings = true
            return
        }
        if (running) return
        running = true
        val main = Handler(Looper.getMainLooper())
        Thread {
            var failure: String? = null
            try {
                GoogleAccount.authorize(c, { uri -> main.post { GoogleAccount.openBrowser(c, uri) } })
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            main.post {
                running = false
                app.connected = GoogleAccount.connected(c)
                if (failure != null) app.alert = failure
                else {
                    app.show(tr("已连接"))
                    app.sync(quiet = false)
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
