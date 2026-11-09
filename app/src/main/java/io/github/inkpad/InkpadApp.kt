package io.github.inkpad

import android.app.Application
import io.github.inkpad.render.AndroidPlatform

class InkpadApp : Application() {
    /** The app's one model: the library, the open notebook and sync. */
    val model: AppModel by lazy { AppModel(this) }

    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.install(this)
    }
}
