package com.melhive.airx1.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.melhive.airx1.data.AppSettings
import com.melhive.airx1.data.Apps
import com.melhive.airx1.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AppRow(val pkg: String, val label: String, val icon: ImageBitmap)

/** Pick up to 8 apps for the Quick Launcher (open palm). */
@Composable
fun FavoritesScreen() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx) }
    val scope = rememberCoroutineScope()
    val s by repo.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
    val apps by produceState<List<AppRow>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            Apps.launchable(ctx).map { AppRow(it.pkg, it.label, it.icon.toBitmap(96, 96).asImageBitmap()) }
        }
    }
    val max = 8
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.favorites.isEmpty()) "Using common apps. Pick up to $max." else "${s.favorites.size} of $max chosen",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { scope.launch { repo.update { it.copy(favorites = emptyList()) } } }) { Text("Reset") }
        }
        val list = apps
        if (list == null) {
            Text("Loading apps…", Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { app ->
                    val checked = app.pkg in s.favorites
                    val toggle = {
                        scope.launch {
                            repo.update {
                                val f = it.favorites
                                it.copy(favorites = if (app.pkg in f) f - app.pkg else if (f.size < max) f + app.pkg else f)
                            }
                        }
                        Unit
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(app.icon, null, Modifier.size(36.dp))
                        Text(app.label, Modifier.weight(1f).padding(start = 12.dp))
                        Checkbox(checked = checked, onCheckedChange = { toggle() }, enabled = checked || s.favorites.size < max)
                    }
                }
            }
        }
    }
}
