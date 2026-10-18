package com.keyling.hub.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.keyling.hub.dongle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeylingTheme {
                MainScreen(dongle)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dongle.refresh()
    }
}
