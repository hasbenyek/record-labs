package com.recordlabs.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeUIViewController
import com.recordlabs.desktop.ui.Heroicons

/**
 * The iPhone app's window (called from Swift, iosApp). For now a first screen that
 * proves the shared code runs on iOS; the full app follows as the port goes on.
 */
fun MainViewController() = ComposeUIViewController { RecordLabsIosApp() }

@Composable
private fun RecordLabsIosApp() {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Heroicons.PlaySolid, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Text("Record Labs", color = Color.White, fontSize = 28.sp, modifier = Modifier.padding(top = 16.dp))
        Text("iOS", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
    }
}
