package fyi.justfreesoftware.justcapture

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import fyi.justfreesoftware.justcapture.ui.JustCaptureApp
import fyi.justfreesoftware.justcapture.ui.theme.JustCaptureTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as JustCaptureApplication).graph
        setContent {
            JustCaptureTheme {
                JustCaptureApp(graph)
            }
        }
    }
}
