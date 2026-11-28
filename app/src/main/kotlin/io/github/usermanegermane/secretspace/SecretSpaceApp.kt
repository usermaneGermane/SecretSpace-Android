package io.github.usermanegermane.secretspace

import android.app.Application

class SecretSpaceApp : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        Prefs.load(this)
        state = AppState(this)
    }
}
