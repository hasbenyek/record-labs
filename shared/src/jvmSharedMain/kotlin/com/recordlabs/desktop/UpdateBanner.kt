package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.AndroidUpdates
import com.recordlabs.desktop.data.AppInfo
import com.recordlabs.desktop.data.Platform
import kotlinx.coroutines.delay

/**
 * Android: the update banner (a new release on GitHub, see [AndroidUpdates]),
 * at the top of the app over the page: Update, Not now, Always ignore. It is
 * the only way the app tells about updates (no notification).
 */
@Composable
internal fun AndroidUpdateBanner(modifier: Modifier = Modifier) {
    if (!AppPlatform.isAndroid) return
    // At launch, and again every few hours while the app stays open.
    LaunchedEffect(Unit) {
        while (true) {
            AndroidUpdates.checkInBackground()
            delay(60L * 60L * 1_000L)
        }
    }
    val state = AndroidUpdates.banner.value
    val uriHandler = LocalUriHandler.current
    val openPage: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    AnimatedVisibility(
        visible = state !is AndroidUpdates.Banner.Hidden && !AndroidUpdates.bannerDismissed.value,
        modifier = modifier,
        enter = fadeIn(tween(220)) + slideInVertically(tween(320)) { -it },
        exit = fadeOut(tween(180)) + slideOutVertically(tween(260)) { -it },
    ) {
        // The last shown content stays while the banner slides away.
        val shown = state.takeUnless { it is AndroidUpdates.Banner.Hidden } ?: return@AnimatedVisibility
        val release = when (shown) {
            is AndroidUpdates.Banner.Available -> shown.release
            is AndroidUpdates.Banner.Downloading -> shown.release
            is AndroidUpdates.Banner.Ready -> shown.release
            is AndroidUpdates.Banner.Failed -> shown.release
            AndroidUpdates.Banner.Hidden -> return@AnimatedVisibility
        }
        val shape = RoundedCornerShape(20.dp)
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
                    .clip(shape)
                    .background(Color(0xF21C1C1F))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                    .padding(start = 16.dp, end = 10.dp, top = 14.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Update available", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (shown) {
                                is AndroidUpdates.Banner.Downloading -> "Downloading Record Labs ${release.version}… ${(shown.progress * 100).toInt()}%"
                                is AndroidUpdates.Banner.Ready -> "Record Labs ${release.version} is ready to install"
                                is AndroidUpdates.Banner.Failed -> shown.message
                                else -> "Record Labs ${release.version} is out (you have ${AppInfo.version})"
                            },
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The first line of the release notes, when there are any.
                        release.notes.lineSequence().map { it.trim().trimStart('#', '-', '*', ' ') }.firstOrNull { it.isNotBlank() }
                            ?.takeIf { shown is AndroidUpdates.Banner.Available }
                            ?.let { line ->
                                Text(line, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                    }
                }
                if (shown is AndroidUpdates.Banner.Downloading) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = shown.progress,
                        modifier = Modifier.fillMaxWidth().padding(end = 6.dp).height(4.dp).clip(CircleShape),
                        color = Color.White,
                        backgroundColor = Color.White.copy(alpha = 0.15f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { AndroidUpdates.alwaysIgnore() }) {
                        Text("Always ignore", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
                    }
                    TextButton(onClick = { AndroidUpdates.notNow() }) {
                        Text("Not now", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                    }
                    if (shown !is AndroidUpdates.Banner.Downloading) {
                        Spacer(Modifier.width(4.dp))
                        GlassButton(onClick = { AndroidUpdates.update(openPage) }, prominent = true) {
                            Text(
                                when (shown) {
                                    is AndroidUpdates.Banner.Ready -> "Install"
                                    is AndroidUpdates.Banner.Failed -> "Try again"
                                    else -> "Update"
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}
