package com.melhive.airx1.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class GestureHelp(val icon: String, val name: String, val what: String)

private val AVAILABLE = listOf(
    GestureHelp("✊", "Fist, hold 1 s", "Arm / disarm. Nothing is clicked while disarmed (dim cursor)."),
    GestureHelp("☝️", "Point and move", "Moves the cursor. The ring fills as your thumb approaches your finger."),
    GestureHelp("👌", "Quick pinch", "Tap where the cursor is."),
)

private val COMING = listOf(
    GestureHelp("👌", "Pinch and hold", "Long press"),
    GestureHelp("👌", "Pinch and move", "Drag, scroll, swipe, glide typing"),
    GestureHelp("✌️", "Two fingers, hold", "Back"),
    GestureHelp("🤟", "Three fingers, hold", "Home"),
    GestureHelp("🖖", "Four fingers (thumb folded), hold", "Recents"),
    GestureHelp("🖐️", "Open palm, hold still 1 s", "Quick Launcher"),
    GestureHelp("⏱️", "Hold cursor still", "Dwell click (optional)"),
)

@Composable
fun HowToScreen() {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "1. Prop the phone up (stand, books) with the front camera facing you.\n" +
                "2. Keep your hand 40–70 cm away, palm towards the camera, in good light.\n" +
                "3. Run Calibrate once. 4. Press Start, then make a fist for 1 s to arm.",
        )
        HorizontalDivider()
        Text("Gestures in this build", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AVAILABLE.forEach { Item(it) }
        HorizontalDivider()
        Text("Coming in the next builds", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        COMING.forEach { Item(it) }
        HorizontalDivider()
        Text("Stopping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "• Fist for 1 s disarms instantly.\n" +
                "• The notification's Stop button turns the camera off.\n" +
                "• AirX1 disarms by itself after 10 s without seeing a hand, and when the screen turns off.",
        )
    }
}

@Composable
private fun Item(g: GestureHelp) {
    Row {
        Text(g.icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(48.dp))
        Column {
            Text(g.name, style = MaterialTheme.typography.titleSmall)
            Text(g.what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
