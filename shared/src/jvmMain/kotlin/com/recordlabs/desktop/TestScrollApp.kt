package com.recordlabs.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*

fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        Box(Modifier.fillMaxSize().mouseToTouchDrag()) {
            val state = rememberScrollState()
            Column(Modifier.fillMaxSize().verticalScroll(state)) {
                for (i in 1..50) {
                    Text("Item $i", Modifier.padding(16.dp))
                }
            }
        }
    }
}
