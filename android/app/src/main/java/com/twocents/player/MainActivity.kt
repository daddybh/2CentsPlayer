package com.twocents.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.twocents.player.ui.theme.TwoCentsPlayerTheme
import com.twocents.player.ui.PlayerApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwoCentsPlayerTheme {
                PlayerApp()
            }
        }
    }
}
