package com.justapp.record

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.justapp.record.ui.JustApp
import com.justapp.record.ui.theme.JustTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as JustApplication).graph
        setContent {
            JustTheme {
                JustApp(graph)
            }
        }
    }
}
