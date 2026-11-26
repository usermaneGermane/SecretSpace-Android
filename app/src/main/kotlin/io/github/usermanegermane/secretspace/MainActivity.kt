package io.github.usermanegermane.secretspace

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.usermanegermane.secretspace.ui.Root
import io.github.usermanegermane.secretspace.ui.SecretSpaceTheme

class MainActivity : ComponentActivity() {
    private val state get() = (application as SecretSpaceApp).state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, and a blank card in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
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
