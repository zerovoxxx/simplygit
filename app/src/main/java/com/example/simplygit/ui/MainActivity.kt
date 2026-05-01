package com.example.simplygit.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.simplygit.ui.home.HomeScreen
import com.example.simplygit.ui.theme.SimplygitTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SPEC §4.6 / §5.3: the whole window holds PAT input — block screenshots,
        // screen recording and recent-task thumbnails.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()
        setContent {
            SimplygitTheme {
                HomeScreen()
            }
        }
    }
}
