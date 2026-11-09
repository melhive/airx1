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

private val CURSOR = listOf(
    GestureHelp("✊", "Fist, hold 1 s", "Arm / disarm. Nothing is clicked while disarmed (dim cursor)."),
    GestureHelp("☝️", "Point and move", "Moves the cursor. It locks onto buttons (Smart Snap) and the ring fills as you pinch."),
    GestureHelp("👌", "Quick pinch", "Tap"),
    GestureHelp("👌", "Pinch and hold", "Long press"),
    GestureHelp("👌", "Pinch and move", "Drag, scroll a page, swipe, or glide-type on Gboard"),
    GestureHelp("✌️", "Two fingers, hold", "Back (also closes the launcher / AirKeys)"),
    GestureHelp("🤟", "Three fingers, hold", "Home"),
    GestureHelp("🖖", "Four fingers (thumb folded), hold", "Recents"),
    GestureHelp("🖐️", "Open palm, hold 1 s", "Quick Launcher: apps, Alerts, Quick settings, Scroll mode, Keys"),
    GestureHelp("⏱️", "Hold the cursor still (optional)", "Dwell click, turn on in Settings"),
)

private val SCROLL = listOf(
    GestureHelp("⬆️", "Hand above the middle", "Scroll up. Further = faster."),
    GestureHelp("⬇️", "Hand below the middle", "Scroll down. Back to the middle to stop."),
    GestureHelp("💨", "Quick flick up / down", "Next / previous video or page"),
    GestureHelp("🖐️", "Open palm", "Quick Launcher → Cursor to switch back"),
)

private val TYPING = listOf(
    GestureHelp("⌨️", "AirKeys", "Opens automatically when you tap a text field while armed. Big keys; the cursor locks onto them."),
    GestureHelp("〰️", "Glide typing", "Tap Gboard on AirKeys, then pinch on the first letter, slide through the word, release."),
    GestureHelp("🎤", "Voice", "Tap Gboard, then pinch the mic key (works offline with Gboard's offline speech pack)."),
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
        Text("Cursor mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        CURSOR.forEach { Item(it) }
        HorizontalDivider()
        Text("Scroll mode (feeds, reading)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SCROLL.forEach { Item(it) }
        HorizontalDivider()
        Text("Typing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TYPING.forEach { Item(it) }
        Text("Tip: hold each pose until the ring around the cursor fills. Quick in-between shapes are ignored.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("Stopping", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "• Fist for 1 s disarms instantly.\n" +
                "• The notification's Stop button turns the camera off.\n" +
                "• AirX1 disarms by itself after 10 s without seeing a hand, and when the screen turns off.\n" +
                "• Quick Settings tile: add \"AirX1\" to your quick settings to turn it on/off from anywhere.",
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
