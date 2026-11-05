package fyi.justfreesoftware.justcapture

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fyi.justfreesoftware.justcapture.ui.JustCaptureApp
import fyi.justfreesoftware.justcapture.ui.theme.JustCaptureTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so keep light system-bar icons even when the
        // phone itself is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        val graph = (application as JustCaptureApplication).graph
        setContent {
            JustCaptureTheme {
                JustCaptureApp(graph)
            }
        }
    }
}
