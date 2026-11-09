package io.github.inkpad.sync

import android.os.Handler
import android.os.Looper
import io.github.inkpad.AppModel
import io.github.inkpad.core.tr

/** Signing in to Google: the browser opens, the app waits for it on a thread of its own. */
object Connect {
    private var attempt = 0   // main thread only: the latest sign-in; earlier ones were given up

    fun start(app: AppModel) {
        val c = app.context
        if (GoogleAccount.client(c) == null) {
            app.alert = tr("请先填写并保存凭据")
            app.showSettings = true
            return
        }
        // Pressed again while an earlier sign-in still waits (its browser closed, say): that one
        // is given up and a new one begins.
        GoogleAccount.cancelWaiting()
        val mine = ++attempt
        val main = Handler(Looper.getMainLooper())
        Thread {
            var failure: String? = null
            try {
                GoogleAccount.authorize(c, { uri -> main.post { GoogleAccount.openBrowser(c, uri) } })
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
            }
            main.post {
                if (mine != attempt) return@post   // given up for a newer one: nothing to say
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
