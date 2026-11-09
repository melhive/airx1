package com.melhive.airx1.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

enum class Screen(val title: String) {
    HOME("AirX1"),
    CALIBRATE("Calibrate"),
    DEBUG("Debug view"),
    SETTINGS("Settings"),
    LOGS("Log"),
    HOWTO("How to use"),
    FAVORITES("Quick Launcher apps"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AirX1Theme { AirX1App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AirX1App() {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val back = { screen = if (screen == Screen.FAVORITES) Screen.SETTINGS else Screen.HOME }
    BackHandler(enabled = screen != Screen.HOME) { back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screen.title) },
                navigationIcon = {
                    if (screen != Screen.HOME) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(onNavigate = { screen = it })
                Screen.CALIBRATE -> CalibrationScreen(onDone = { screen = Screen.HOME })
                Screen.DEBUG -> DebugScreen()
                Screen.SETTINGS -> SettingsScreen(
                    onCalibrate = { screen = Screen.CALIBRATE },
                    onFavorites = { screen = Screen.FAVORITES },
                )
                Screen.FAVORITES -> FavoritesScreen()
                Screen.LOGS -> LogScreen()
                Screen.HOWTO -> HowToScreen()
            }
        }
    }
}
