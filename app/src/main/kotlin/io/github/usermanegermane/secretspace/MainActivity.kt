package io.github.usermanegermane.secretspace

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import io.github.usermanegermane.secretspace.ui.Root
import io.github.usermanegermane.secretspace.ui.SecretSpaceTheme

/** A FragmentActivity: the fingerprint prompt (androidx.biometric) needs one. */
class MainActivity : FragmentActivity() {
    private val state get() = (application as SecretSpaceApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, and a blank card in the recent-apps list. Only a debug build started by the lab
        // with ``--ez lab_screenshots true`` may be photographed (to check the look); a release never.
        val labShots = BuildConfig.DEBUG && intent.getBooleanExtra("lab_screenshots", false)
        if (!labShots) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent { SecretSpaceTheme { Root(state) } }
    }

    override fun onStart() {
        super.onStart()
        state.cameBack()
    }

    override fun onStop() {
        super.onStop()
        state.wentAway()
    }
}
