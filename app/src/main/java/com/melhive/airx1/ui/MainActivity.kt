package com.melhive.airx1.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirX1Theme {
                Scaffold { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        Text("AirX1", style = MaterialTheme.typography.headlineLarge)
                        Text("Your Hands Are the Interface.")
                    }
                }
            }
        }
    }
}
