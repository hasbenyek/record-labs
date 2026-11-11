package com.recordlabs.desktop
import com.recordlabs.desktop.data.LocalMusicBridge
import com.recordlabs.desktop.data.AudioCacheBridge
import com.recordlabs.desktop.data.BackupBridge
import com.recordlabs.desktop.data.OfflineBridge
import com.recordlabs.desktop.data.DiscordDefaults

import com.recordlabs.desktop.platform.*

import androidx.compose.material.icons.filled.*
import com.recordlabs.desktop.ui.LineIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.AppInfo
import com.recordlabs.desktop.data.AppDataEvents
import com.recordlabs.desktop.data.AppDataRequest
import com.recordlabs.desktop.data.AppSettings
import com.recordlabs.desktop.data.WindowsLyricsProviderRegistry
import com.recordlabs.desktop.data.WindowsLyricsTranslator
import kotlinx.coroutines.launch
import com.recordlabs.desktop.data.DiscordConnectionState
import com.recordlabs.desktop.data.DiscordPlayback
import com.recordlabs.desktop.data.DiscordRichPresence
import com.recordlabs.desktop.data.LastFmScrobbler
import com.recordlabs.desktop.data.LyricsTranslationSettings
import com.recordlabs.desktop.data.WindowsAnimatedArtworkRepository
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Building blocks shared by every settings page (and Profile / Listen Together).
// ---------------------------------------------------------------------------

/** Rows inside a [SettingsGroup] draw a hairline above themselves (the first one is clipped away). */
private val LocalInSettingsGroup = androidx.compose.runtime.staticCompositionLocalOf { false }
private val SettingsDividerColor = Color.White.copy(alpha = 0.06f)

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        if (title.isNotBlank()) {
            Text(title, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        Surface(
            color = SurfaceColor,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalInSettingsGroup provides true) {
                // Shifted up by one pixel so the first row's divider falls outside the card.
                Column(
                    Modifier.layout { measurable, constraints ->
                        val px = 1.dp.roundToPx()
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
                    },
                ) { content() }
            }
        }
    }
}

/** The hairline between rows of a group, indented to the text (past a leading icon). */
@Composable
private fun Modifier.settingsDivider(inset: androidx.compose.ui.unit.Dp): Modifier {
    if (!LocalInSettingsGroup.current) return this
    return drawBehind {
        val start = inset.toPx()
        drawRect(SettingsDividerColor, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = androidx.compose.ui.geometry.Size(size.width - start, 1.dp.toPx()))
    }
}

/**
 * The leading icon of rows and page headers: a thin line icon on its own, in
 * the style of iOS (no coloured tile behind it). It keeps the tile's room so
 * the rows stay lined up.
 */
@Composable
internal fun SettingsIconTile(icon: ImageVector, size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(icon.asLineIcon(), null, tint = Color.White.copy(alpha = 0.92f), modifier = Modifier.size(size * 0.68f))
    }
}

/** The thin line version of a Material icon used in Settings (the icon itself when there is none). */
internal fun ImageVector.asLineIcon(): ImageVector = when (this) {
    Icons.Default.Palette -> LineIcons.Paintbrush
    Icons.Default.GraphicEq -> LineIcons.Waveform
    Icons.Default.Cloud -> LineIcons.Cloud
    Icons.Default.Language -> LineIcons.Globe
    Icons.Default.Translate -> LineIcons.Translate
    Icons.Default.Extension -> LineIcons.Puzzle
    Icons.Default.Lock -> LineIcons.Lock
    Icons.Default.Storage -> LineIcons.Drive
    Icons.Default.Backup -> LineIcons.Backup
    Icons.Default.Info -> LineIcons.Info
    Icons.Default.Settings -> LineIcons.Gear
    Icons.Default.Headset -> LineIcons.Headphones
    Icons.Default.History -> LineIcons.History
    Icons.Default.Group -> LineIcons.People
    Icons.Default.Person -> LineIcons.Person
    Icons.Default.ChevronRight -> LineIcons.ChevronRight
    Icons.Default.Check -> LineIcons.Check
    Icons.Default.Download -> LineIcons.Download
    Icons.Default.Search -> LineIcons.Search
    Icons.Default.Refresh -> LineIcons.Refresh
    Icons.Default.Edit -> LineIcons.Pencil
    Icons.Default.ContentCopy -> LineIcons.Copy
    Icons.Default.Share -> LineIcons.Share
    Icons.Default.Notifications -> LineIcons.Bell
    Icons.Default.Timer -> LineIcons.Timer
    Icons.Default.Speed -> LineIcons.Gauge
    Icons.Default.Tune -> LineIcons.Sliders
    Icons.Default.MusicNote -> LineIcons.MusicNote
    Icons.Default.Album -> LineIcons.Album
    Icons.Default.Add -> LineIcons.Plus
    Icons.Default.Close -> LineIcons.Xmark
    else -> this
}

/** Title, short description and icon at the top of a settings page. */
@Composable
internal fun SettingsPageHeader(title: String, subtitle: String, icon: ImageVector?, compact: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = if (compact) 14.dp else 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null && !compact) {
            SettingsIconTile(icon, 48.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column {
            Text(title, color = Color.White, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = SecondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun settingsSwitchColors(): androidx.compose.material.SwitchColors {
    // In the Flat style the accent is near white: give the thumb contrast there.
    val accentIsLight = Accent.luminance() > 0.6f
    return androidx.compose.material.SwitchDefaults.colors(
        checkedThumbColor = if (accentIsLight) Color(0xFF26282E) else Color.White,
        checkedTrackColor = Accent,
        checkedTrackAlpha = 1f,
        uncheckedThumbColor = Color(0xFFCFD0D4),
        uncheckedTrackColor = Color.White,
        uncheckedTrackAlpha = 0.18f,
        disabledCheckedThumbColor = Color(0xFF8A8B90),
        disabledCheckedTrackColor = Accent.copy(alpha = 0.4f),
        disabledUncheckedThumbColor = Color(0xFF6E6F74),
        disabledUncheckedTrackColor = Color.White.copy(alpha = 0.1f),
    )
}

@Composable
internal fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    leading: ImageVector? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .settingsDivider(if (leading != null) 64.dp else 16.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            SettingsIconTile(leading)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, color = SecondaryText, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, colors = settingsSwitchColors())
    }
}

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    icon: ImageVector? = Icons.Default.ChevronRight,
    enabled: Boolean = true,
    value: String? = null,
    badge: Int = 0,
    leading: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .settingsDivider(if (leading != null) 64.dp else 16.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .alpha(if (enabled) 1f else .45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            SettingsIconTile(leading)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, color = SecondaryText, fontSize = 12.sp)
        }
        if (value != null) {
            Text(
                value,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (badge > 0) {
            UpdateBadge(badge)
            Spacer(Modifier.width(8.dp))
        }
        if (icon != null) {
            // The navigation chevron stays quiet; other trailing icons keep the accent.
            val chevron = icon == Icons.Default.ChevronRight
            Icon(icon.asLineIcon(), null, tint = if (chevron) Color.White.copy(alpha = 0.35f) else Accent, modifier = Modifier.size(if (chevron) 18.dp else 22.dp))
        }
    }
}

/** A slider with its current value; [onValueChange] runs while dragging (cheap: state only). */
@Composable
internal fun SettingSlider(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    subtitle: String = "",
    onReset: (() -> Unit)? = null,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().settingsDivider(16.dp).padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else .45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (subtitle.isNotBlank()) Text(subtitle, color = SecondaryText, fontSize = 12.sp)
            }
            Text(
                valueLabel,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.sp,
                modifier = Modifier.background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            if (onReset != null) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "Reset",
                    color = if (enabled) Accent else SecondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(enabled = enabled, onClick = onReset)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Slider(
            value = value.coerceIn(range),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Accent,
                activeTrackColor = Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/** A row showing the chosen option; clicking it opens a list of [options] (value to label). */
@Composable
internal fun <T> SettingChoice(
    title: String,
    subtitle: String = "",
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(title, subtitle, enabled = enabled, value = options.firstOrNull { it.first == selected }?.second) { open = true }
    if (open) SettingChoiceDialog(title, options, selected, onDismiss = { open = false }) { onSelect(it); open = false }
}

@Composable
internal fun <T> SettingChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp).dialogScrollFix()) {
                items(options) { (value, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f), fontWeight = if (value == selected) FontWeight.Bold else FontWeight.Normal)
                        if (value == selected) Icon(Icons.Default.Check, null, tint = Accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** A button-like row that asks for confirmation first (for anything that deletes data). */
@Composable
internal fun SettingConfirmAction(
    title: String,
    subtitle: String,
    confirmTitle: String,
    confirmText: String,
    confirmLabel: String = "Delete",
    enabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    SettingRow(title, subtitle, icon = null, enabled = enabled) { asking = true }
    if (asking) AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(confirmTitle) },
        text = { Text(confirmText, color = SecondaryText) },
        dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { asking = false; onConfirm() }) { Text(confirmLabel, color = Color(0xFFFF6B6B)) } },
    )
}

/** A short note inside a settings group (not clickable). */
@Composable
internal fun SettingNote(text: String) {
    Text(text, color = SecondaryText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
}

// ---------------------------------------------------------------------------
// Profile > Settings
// ---------------------------------------------------------------------------

@Composable
internal fun SettingsPage(
    compact: Boolean,
    subpage: String?,
    onSubpage: (String?) -> Unit,
    onOpenIntegrations: () -> Unit,
) {
    if (subpage != null) {
        SettingsSubpage(subpage, compact)
        return
    }
    val pageScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().pageScrollbar(pageScroll).verticalScroll(pageScroll)) {
        // Compact windows already show "Settings" with their back button above.
        if (!compact) SettingsPageHeader("Settings", "Make Record Labs look, play and connect the way you like", Icons.Default.Settings, compact)
        @Composable
        fun CategoryRow(key: String, value: String? = null, badge: Int = 0, onClick: () -> Unit = { onSubpage(key) }) {
            val category = SettingsCategories.of(key)
            SettingRow(category.title, category.subtitle, leading = category.icon, value = value, badge = badge, onClick = onClick)
        }
        SettingsGroup("General") {
            CategoryRow("Appearance")
            CategoryRow("Player and audio")
            CategoryRow("Stream sources")
            CategoryRow("Content")
            CategoryRow(
                "Lyrics translation",
                value = if (LyricsTranslationSettings.enabled) LyricsTranslationSettings.nameOf(LyricsTranslationSettings.targetLanguage) else "Off",
            )
        }
        SettingsGroup("Connections") {
            CategoryRow("Integrations", onClick = onOpenIntegrations)
        }
        SettingsGroup("Privacy & data") {
            CategoryRow("Privacy")
            CategoryRow("Storage")
            CategoryRow("Backup & restore")
        }
        SettingsGroup("About") {
            CategoryRow("About", value = AppInfo.version, badge = updateBadgeCount)
        }
        // Room at the end so the floating mini player never covers the last item.
        Spacer(Modifier.height(MiniPlayerClearance + LocalBottomBarInset.current))
    }
}

/** The settings categories: one title, description and icon for the list and each page's header. */
internal object SettingsCategories {
    data class Category(val title: String, val subtitle: String, val icon: ImageVector)
    private val all = listOf(
        Category("Appearance", "Style, mini player, artwork and lyrics", Icons.Default.Palette),
        Category("Player and audio", "Playback, queue and audio quality", Icons.Default.GraphicEq),
        Category("Stream sources", "Where songs are streamed from", Icons.Default.Cloud),
        Category("Content", "Region, explicit songs, lyrics providers", Icons.Default.Language),
        Category("Lyrics translation", "Translate lyrics while they play", Icons.Default.Translate),
        Category("Integrations", "Discord, Last.fm and Listen Together", Icons.Default.Extension),
        Category("Privacy", "Listening and search history", Icons.Default.Lock),
        Category("Storage", "Downloads, cache and local folders", Icons.Default.Storage),
        Category("Backup & restore", "Export or restore Record Labs data", Icons.Default.Backup),
        Category("About", "Version, updates and licenses", Icons.Default.Info),
    )
    fun of(title: String): Category = all.firstOrNull { it.title == title } ?: Category(title, "", Icons.Default.Settings)
}

@Composable
private fun SettingsSubpage(title: String, compact: Boolean) {
    val pageScroll = rememberScrollState()
    Column(Modifier.fillMaxSize().pageScrollbar(pageScroll).verticalScroll(pageScroll)) {
        val category = SettingsCategories.of(title)
        SettingsPageHeader(category.title, category.subtitle, category.icon, compact)
        when (title) {
            "Appearance" -> AppearanceSettings()
            "Player and audio" -> PlayerSettings()
            "Stream sources" -> StreamSourceSettings()
            "Content" -> ContentSettings()
            "Lyrics translation" -> TranslationSettings()
            "Privacy" -> PrivacySettings()
            "Storage" -> StorageSettings()
            "Backup & restore" -> BackupSettings()
            "About" -> AboutSettings()
            else -> Text("This page is not available.", color = SecondaryText)
        }
        // Room at the end so the floating mini player never covers the last item.
        Spacer(Modifier.height(MiniPlayerClearance + LocalBottomBarInset.current))
    }
}

@Composable
internal fun AppearanceSettings() {
    var animatedArtwork by AppSettings.animatedArtwork
    var portraitSize by AppSettings.lyricsTextSizePortrait
    var landscapeSize by AppSettings.lyricsTextSizeLandscape
    var offset by AppSettings.lyricsOffsetMs
    if (PlatformDisplayScale.supported) {
        val displayScale = PlatformDisplayScale.mode.value
        SettingsGroup("Display") {
            SettingChoice(
                title = "Display scale",
                subtitle = "Automatic detects optimal scale for your screen (125% on 14\" laptops, 100% on 15.6\"). Changes apply immediately.",
                options = PlatformDisplayScale.options,
                selected = displayScale,
            ) {
                PlatformDisplayScale.saveMode(it)
            }
            SettingRow(
                title = "Restart now",
                subtitle = "Restart Record Labs to apply display scale to native window decorations",
                icon = null,
                onClick = PlatformDisplayScale::restart,
            )
        }
    }
    // The iPhone always has it; Android 13+ and the desktop can turn it on.
    if (!AppPlatform.isIos && liquidGlassSupported) {
        var liquidGlass by AppSettings.liquidGlassMaterial
        SettingsGroup("Material") {
            SettingToggle(
                "Liquid Glass Material",
                "The iPhone's liquid glass for the navigation and mini player, menus, dialogs, round buttons and search suggestions. Uses more of the graphics card; turn it off on slower devices.",
                liquidGlass,
            ) { liquidGlass = it }
        }
    }
    SettingsGroup("Artwork") {
        SettingToggle("Animated artwork", "Show animated album artwork and music videos when available", animatedArtwork) {
            animatedArtwork = it
            if (it) WindowsAnimatedArtworkRepository.clearAllMisses()
        }
        if (AppPlatform.isWindows || AppPlatform.isLinux) {
            var pauseInBackground by AppSettings.pauseVisualsInBackground
            SettingToggle(
                "Pause visuals in the background",
                "While you use another window (Record Labs on a second screen, say), the music video, animated artwork and the moving background colours pause to save the graphics card. The music and lyrics keep going; everything moves again when you click back into Record Labs.",
                pauseInBackground,
            ) { pauseInBackground = it }
        }
    }
    SettingsGroup("Style") {
        SettingNote("How icons, highlights, the mini player, and the queue are coloured.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ThemeStyleOption("accent", "Accent colours", "Icons and highlights follow the song's artwork", Modifier.weight(1f))
            ThemeStyleOption("flatMini", "Flat mini player", "Accent colours, but no colour behind the mini player", Modifier.weight(1f))
            ThemeStyleOption("flat", "Flat", "Neutral white icons, no colour behind the mini player and queue", Modifier.weight(1f))
        }
    }
    // Portrait always has the rounded dock, so the shape is for landscape only.
    SettingsGroup("Mini player (Landscape mode)") {
        SettingNote("The shape of the mini player at the bottom of every page.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniPlayerShapeOption("pill", "Rounded ends", "A pill with round ends", Modifier.weight(1f))
            MiniPlayerShapeOption("rounded", "Box", "Square sides with slightly rounded corners", Modifier.weight(1f))
        }
    }
    SettingsGroup("Sliders") {
        SettingNote("The look of the song position bar and the volume bar on the music player and lyrics pages, each chosen on its own.")
        Text("Song position", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SliderStyleOption(AppSettings.seekBarStyle, "modern", "Modern", "Thin rounded bar, no knob", volume = false, Modifier.weight(1f))
            SliderStyleOption(AppSettings.seekBarStyle, "classic", "Classic", "The earlier slider with a round knob", volume = false, Modifier.weight(1f))
        }
        Text("Volume", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SliderStyleOption(AppSettings.volumeBarStyle, "modern", "Modern", "Thin rounded bar, no knob", volume = true, Modifier.weight(1f))
            SliderStyleOption(AppSettings.volumeBarStyle, "classic", "Classic", "The earlier slider with a round knob", volume = true, Modifier.weight(1f))
        }
    }
    SettingsGroup("Lyrics") {
        SettingNote("What the lyrics show while waiting for the next line (an intro or an instrumental part).")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LyricsWaitStyleOption("note", "Music note", "Fills with glowing white; the lyrics come when it is full", Modifier.weight(1f))
            LyricsWaitStyleOption("ring", "Ring", "A progress ring around the waiting time", Modifier.weight(1f))
        }
        SettingSlider(
            "Lyrics text size (wide window)",
            "${landscapeSize.roundToInt()}",
            landscapeSize,
            20f..68f,
            onReset = { landscapeSize = WindowsPreferencesDefaults.lyricsTextSize() },
        ) { landscapeSize = it.roundToInt().toFloat() }
        SettingSlider(
            "Lyrics text size (narrow window)",
            "${portraitSize.roundToInt()}",
            portraitSize,
            20f..68f,
            onReset = { portraitSize = WindowsPreferencesDefaults.lyricsTextSize() },
        ) { portraitSize = it.roundToInt().toFloat() }
        SettingSlider(
            "Lyrics timing offset",
            when {
                offset > 0L -> "Delay +$offset ms"
                offset < 0L -> "Advance ${-offset} ms"
                else -> "In sync"
            },
            offset.toFloat(),
            -5_000f..5_000f,
            subtitle = "Moves every lyric line earlier or later",
            onReset = { offset = 0L },
        ) { offset = ((it / 50f).roundToInt() * 50).toLong() }
    }
}

/**
 * One choice of Settings > Appearance > Style, with a small picture of the
 * app in that style (side bar, a song list, the queue and the mini player).
 */
@Composable
private fun ThemeStyleOption(key: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    var style by AppSettings.themeStyle
    val selected = style == key
    val flatIcons = key == "flat"
    val flatMini = key == "flat" || key == "flatMini"
    val flatQueue = key == "flat"
    // The preview always shows its own style: the real accent while an accent
    // style is chosen, the app's red otherwise (and white for Flat).
    val accent = if (flatIcons) FlatAccent else if (AppSettings.flatTheme) Color(0xFFFFFFFF) else MaterialTheme.colors.primary
    val artwork = DiscordRichPresence.playback.collectAsState().value?.track?.artworkUrl
    val roundedMini = AppSettings.miniPlayerShape.value == "rounded"
    PreviewCard(title, subtitle, selected, modifier, onClick = { style = key }) {
        AppPreview(accent, artwork, flatMini = flatMini, flatQueue = flatQueue, roundedMini = roundedMini)
    }
}

/** One choice of Settings > Appearance > Lyrics waiting indicator, with its animation playing. */
@Composable
private fun LyricsWaitStyleOption(key: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    var style by AppSettings.lyricsWaitStyle
    PreviewCard(title, subtitle, style == key, modifier, onClick = { style = key }) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .06f)),
            contentAlignment = Alignment.Center,
        ) { LyricsWaitPreview(key) }
    }
}

/** One choice of Settings > Appearance > Sliders, with a small picture of that slider. */
@Composable
private fun SliderStyleOption(
    setting: AppSettings.Setting<String>,
    key: String,
    title: String,
    subtitle: String,
    volume: Boolean,
    modifier: Modifier = Modifier,
) {
    var style by setting
    PreviewCard(title, subtitle, style == key, modifier, onClick = { style = key }) {
        Box(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .06f)).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            val active = if (volume || key == "modern") Color.White.copy(alpha = if (key == "modern") .85f else 1f) else MaterialTheme.colors.primary
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(16.dp)) {
                val y = size.height / 2f
                val fraction = .58f
                if (key == "modern") {
                    val h = 6.dp.toPx()
                    val r = androidx.compose.ui.geometry.CornerRadius(h / 2f)
                    drawRoundRect(Color.White.copy(alpha = .22f), topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2f), size = androidx.compose.ui.geometry.Size(size.width, h), cornerRadius = r)
                    drawRoundRect(active, topLeft = androidx.compose.ui.geometry.Offset(0f, y - h / 2f), size = androidx.compose.ui.geometry.Size(size.width * fraction, h), cornerRadius = r)
                } else {
                    val h = 4.dp.toPx()
                    drawLine(Color.White.copy(alpha = .3f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(active, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width * fraction, y), h, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(active, 8.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * fraction, y))
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerShapeOption(key: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    var shape by AppSettings.miniPlayerShape
    val artwork = DiscordRichPresence.playback.collectAsState().value?.track?.artworkUrl
    PreviewCard(title, subtitle, shape == key, modifier, onClick = { shape = key }) {
        AppPreview(
            MaterialTheme.colors.primary,
            artwork,
            flatMini = AppSettings.flatMiniPlayer,
            flatQueue = AppSettings.flatQueue,
            roundedMini = key == "rounded",
        )
    }
}

@Composable
private fun PreviewCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val borderColor by androidx.compose.animation.animateColorAsState(
        if (selected) Color.White else Color.White.copy(alpha = .14f),
        androidx.compose.animation.core.tween(250),
    )
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(if (selected) 2.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        preview()
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (selected) Icon(Icons.Default.Check, null, tint = Color.White)
        }
        Text(subtitle, color = SecondaryText, fontSize = 12.sp)
    }
}

/** A small picture of the app: side bar, a song list, the queue and the mini player. */
@Composable
private fun AppPreview(accent: Color, artwork: String?, flatMini: Boolean, flatQueue: Boolean, roundedMini: Boolean) {
    val miniCorner by androidx.compose.animation.core.animateIntAsState(if (roundedMini) 22 else 50, androidx.compose.animation.core.tween(380))
    val miniShape = RoundedCornerShape(miniCorner)
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        Row(Modifier.fillMaxSize()) {
            // Side bar: the selected item uses the style's colour.
            Column(Modifier.width(30.dp).fillMaxHeight().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(12.dp).background(accent, RoundedCornerShape(3.dp)))
                repeat(3) { Box(Modifier.size(12.dp).background(Color.White.copy(alpha = .35f), RoundedCornerShape(3.dp))) }
            }
            // Songs.
            Column(Modifier.weight(1f).padding(top = 12.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Box(Modifier.width(44.dp).height(6.dp).background(accent, RoundedCornerShape(3.dp)))
                repeat(4) { index ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Elevated, RoundedCornerShape(3.dp)))
                        Spacer(Modifier.width(5.dp))
                        Box(Modifier.weight(1f).height(5.dp).background(Color.White.copy(alpha = if (index == 0) .8f else .35f), RoundedCornerShape(3.dp)))
                    }
                }
            }
            // Queue panel: the artwork's colour behind it, or plain.
            Box(Modifier.width(62.dp).fillMaxHeight().padding(4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF111111))) {
                if (!flatQueue) {
                    if (artwork != null) Artwork(artwork, Modifier.fillMaxSize().blur(18.dp).alpha(.55f), RoundedCornerShape(8.dp))
                    else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = .55f), Color.Transparent))))
                }
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.width(26.dp).height(5.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    repeat(4) { Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = .4f), RoundedCornerShape(2.dp))) }
                }
            }
        }
        // Mini player.
        Box(
            Modifier.align(Alignment.BottomStart).padding(start = 34.dp, end = 70.dp, bottom = 8.dp).fillMaxWidth().height(26.dp)
                .clip(miniShape).background(SurfaceColor).border(1.dp, Color.White.copy(alpha = .18f), miniShape),
        ) {
            if (!flatMini) {
                if (artwork != null) Artwork(artwork, Modifier.fillMaxSize().blur(14.dp).alpha(.5f), miniShape)
                else Box(Modifier.fillMaxSize().background(accent.copy(alpha = .30f)))
            }
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(Elevated, RoundedCornerShape(miniCorner)))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.weight(1f).height(4.dp).background(Color.White.copy(alpha = .7f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(5.dp))
                Box(Modifier.size(8.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
            }
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(.45f).height(2.dp).background(accent))
        }
    }
}

/** Defaults the settings pages reset to. */
internal object WindowsPreferencesDefaults {
    fun lyricsTextSize(): Float = if (AppPlatform.isPhone) 30f else runCatching {
        when (screenLongSidePixels() ?: error("unknown screen")) {
            in 0..1_599 -> 36f
            in 1_600..2_199 -> 46f
            else -> 53f
        }
    }.getOrDefault(46f)
}

@Composable
private fun TranslationSettings() {
    var choosing by remember { mutableStateOf(false) }
    SettingsGroup("Lyrics translation") {
        SettingToggle(
            "Translate lyrics",
            "Show a translation under each lyric line (same as the translate button on the lyrics page)",
            LyricsTranslationSettings.enabled,
        ) { if (it != LyricsTranslationSettings.enabled) LyricsTranslationSettings.toggle() }
        SettingRow(
            "Translate to",
            "Lines already in this language are not translated",
            value = LyricsTranslationSettings.nameOf(LyricsTranslationSettings.targetLanguage),
        ) { choosing = true }
        SettingRow("Translation provider", "Google Translate, with MyMemory as a fallback · no API key needed", icon = null, enabled = false) {}
    }
    if (choosing) LyricsTranslationLanguageDialog(onDismiss = { choosing = false })
}

@Composable
private fun ContentSettings() {
    var pronunciation by AppSettings.lyricsPronunciation
    var region by AppSettings.contentRegion
    var hideExplicit by AppSettings.hideExplicit
    // Read so the switches below update when a provider is toggled.
    val disabledProviders = AppSettings.disabledLyricsProviders.value
    SettingsGroup("General") {
        SettingChoice(
            "Country or region",
            "Home, charts, and search follow this region · pages stay in English",
            ContentRegions.options,
            region,
        ) {
            region = it
            AppDataEvents.request(AppDataRequest.ReloadHome)
        }
        SettingToggle("Hide explicit songs", "Leave songs marked E out of Home, search, albums, artists, and radio", hideExplicit) {
            hideExplicit = it
            AppDataEvents.request(AppDataRequest.ReloadHome)
        }
    }
    SettingsGroup("Lyrics providers") {
        SettingNote("Lyrics are taken from the first provider that has them, in this order.")
        WindowsLyricsProviderRegistry.allProviders.forEachIndexed { index, provider ->
            val enabled = provider !in disabledProviders.split(',').map(String::trim)
            SettingToggle(
                "${index + 1}. $provider",
                when (provider) {
                    "Paxsenix" -> "Apple Music lyrics with word-by-word timing"
                    "BetterLyrics" -> "Word-by-word timed lyrics"
                    "Musixmatch" -> "Word-by-word or line-timed lyrics"
                    "KuGou" -> "Line-timed lyrics"
                    "LRCLIB" -> "Line-timed and plain lyrics"
                    else -> "Lyrics from YouTube Music itself"
                },
                enabled,
            ) { AppSettings.setLyricsProviderEnabled(provider, it) }
        }
    }
    SettingsGroup("Romanization") {
        SettingToggle(
            "Show pronunciation",
            "Show romanized text under Japanese, Korean, Chinese, and other non-Latin lyrics",
            pronunciation,
        ) { pronunciation = it }
    }
}

/** YouTube Music regions offered in Settings > Content. */
private object ContentRegions {
    val options: List<Pair<String, String>> = listOf(
        "US", "ID", "GB", "JP", "KR", "MY", "SG", "PH", "TH", "VN", "IN", "AU", "CA", "DE", "FR", "NL", "ES", "IT",
        "BR", "MX", "AR", "TR", "SA", "AE", "TW", "HK", "ZA", "NG", "SE", "NO", "PL", "RU",
    ).map { code -> code to "${countryName(code)} ($code)" }
        .sortedBy { it.second }
}

@Composable
private fun PlayerSettings() {
    var quality by AppSettings.audioQuality
    var autoplay by AppSettings.autoplay
    var loadMore by AppSettings.autoLoadMore
    var skipOnError by AppSettings.autoSkipOnError
    var rememberShuffle by AppSettings.rememberShuffleRepeat
    var rememberSpeed by AppSettings.rememberSpeedPitch
    var rememberVolume by AppSettings.rememberVolume
    var historySeconds by AppSettings.historyMinSeconds
    SettingsGroup("Audio") {
        SettingChoice(
            "Audio quality",
            "Used from the next song",
            listOf("high" to "High", "low" to "Low (saves data)"),
            quality,
        ) { quality = it }
        SettingToggle("Normalize volume", "Coming soon on Windows", false, enabled = false) {}
        SettingToggle("Crossfade", "Coming soon on Windows", false, enabled = false) {}
    }
    SettingsGroup("Queue") {
        SettingToggle("Autoplay", "Play the next song when one ends", autoplay) { autoplay = it }
        SettingToggle("Load more automatically", "Add similar songs when the queue is about to end", loadMore) { loadMore = it }
        SettingToggle("Skip songs that can't play", "Go to the next song when one fails to load", skipOnError) { skipOnError = it }
        SettingToggle("Remember volume", "Start with the volume you used last time", rememberVolume) { rememberVolume = it }
        SettingToggle("Remember shuffle and repeat", "Keep them as they were when Record Labs opens again", rememberShuffle) { rememberShuffle = it }
        SettingToggle("Remember speed and pitch", "Keep the tempo and pitch set in the player", rememberSpeed) { rememberSpeed = it }
    }
    SettingsGroup("Listening history") {
        SettingSlider(
            "Count a listen after",
            "$historySeconds s",
            historySeconds.toFloat(),
            1f..100f,
            subtitle = "How long a song must play to count in My Top 50, Weekly and Monthly Most",
            onReset = { historySeconds = 30 },
        ) { historySeconds = it.roundToInt() }
    }
}

@Composable
private fun StreamSourceSettings() {
    // Read so the switches update when one changes.
    AppSettings.disabledStreamSources.value
    val enabledCount = AppSettings.streamSourceNames.count(AppSettings::isStreamSourceEnabled)
    @Composable
    fun source(name: String, title: String, subtitle: String) {
        val enabled = AppSettings.isStreamSourceEnabled(name)
        // The last source left on cannot be turned off.
        SettingToggle(title, subtitle, enabled, enabled = !(enabled && enabledCount == 1)) { AppSettings.setStreamSourceEnabled(name, it) }
    }
    SettingsGroup("YouTube clients") {
        SettingNote("Every song asks the sources that are on at the same time and plays the first one that answers.")
        source("VISIONOS", "visionOS", "Fast and reliable for most songs")
        source("WEB_REMIX", "YouTube Music web", "The same source as music.youtube.com")
        source("TVHTML5_SIMPLY_EMBEDDED_PLAYER", "TV (embedded)", "Helps with some age-restricted songs")
        source("ANDROID_VR", "Android VR", "A backup for songs other clients refuse")
        source("IOS", "iOS", "Another backup client")
    }
    SettingsGroup("Fallbacks") {
        source("NEWPIPE", "NewPipe", "A separate extractor used next to the clients")
        source("YTDLP", "yt-dlp", "Slowest, used only when the others take too long")
    }
}

@Composable
private fun PrivacySettings() {
    var pauseListen by AppSettings.pauseListenHistory
    var pauseSearch by AppSettings.pauseSearchHistory
    SettingsGroup("Listening history") {
        SettingToggle("Pause listening history", "New plays are not added to Recently played, My Top 50, Weekly or Monthly Most", pauseListen) { pauseListen = it }
        SettingConfirmAction(
            "Clear listening history",
            "Removes Recently played and your listening stats · liked songs and playlists stay",
            confirmTitle = "Clear listening history?",
            confirmText = "Recently played, My Top 50, Weekly Most and Monthly Most start over. This can't be undone.",
            confirmLabel = "Clear",
        ) { AppDataEvents.request(AppDataRequest.ClearListenHistory) }
    }
    SettingsGroup("Search history") {
        SettingToggle("Pause search history", "New searches are not added to Recent searches", pauseSearch) { pauseSearch = it }
        SettingConfirmAction(
            "Clear search history",
            "Removes every recent search on this PC",
            confirmTitle = "Clear search history?",
            confirmText = "All recent searches are removed. This can't be undone.",
            confirmLabel = "Clear",
        ) { AppDataEvents.request(AppDataRequest.ClearSearchHistory) }
    }
}

private data class StorageSizes(
    val downloadsBytes: Long,
    val downloadsCount: Int,
    val songCacheBytes: Long,
    val imageCacheBytes: Long,
    val lyricsCacheBytes: Long,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".fmt(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".fmt(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun StorageSettings() {
    val generation = AppDataEvents.storageGeneration
    var sizes by remember { mutableStateOf<StorageSizes?>(null) }
    var cacheLimit by AppSettings.songCacheLimitMb
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(generation) {
        sizes = kotlinx.coroutines.withContext(IoDispatcher) {
            StorageSizes(
                downloadsBytes = AudioCacheBridge.downloadBytes() + directoryBytes(File(AppStorage.directory, "offline/artwork")),
                downloadsCount = AudioCacheBridge.downloadCount(),
                songCacheBytes = AudioCacheBridge.cacheBytes(),
                imageCacheBytes = OfflineBridge.colorCacheBytes(),
                lyricsCacheBytes = WindowsLyricsProviderRegistry.cacheBytes(),
            )
        }
    }
    fun measuring(value: StorageSizes.() -> String) = sizes?.value() ?: "Measuring…"
    fun inBackground(action: () -> Unit) {
        scope.launch {
            kotlinx.coroutines.withContext(IoDispatcher) { runCatching(action) }
            AppDataEvents.storageGeneration++
        }
    }
    SettingsGroup("Downloads") {
        SettingRow("Downloaded songs", "Songs saved for offline listening", icon = null, value = measuring { "$downloadsCount · ${formatBytes(downloadsBytes)}" }) {}
        SettingConfirmAction(
            "Remove all downloads",
            "The songs stay in your library and play online",
            confirmTitle = "Remove all downloads?",
            confirmText = "Every downloaded song is deleted from this PC (the song playing now is kept). They can be downloaded again.",
            confirmLabel = "Remove",
            enabled = (sizes?.downloadsCount ?: 0) > 0,
        ) { AppDataEvents.request(AppDataRequest.DeleteDownloads) }
    }
    SettingsGroup("Song cache") {
        SettingRow("Cached songs", "Songs kept after playing so they start instantly next time", icon = null, value = measuring { formatBytes(songCacheBytes) }) {}
        SettingChoice(
            "Maximum cache size",
            "The oldest cached songs are removed first",
            listOf(-1 to "No limit", 256 to "256 MB", 512 to "512 MB", 1024 to "1 GB", 2048 to "2 GB", 4096 to "4 GB", 8192 to "8 GB"),
            cacheLimit,
        ) {
            cacheLimit = it
            AppDataEvents.request(AppDataRequest.ApplySongCacheLimit)
        }
        SettingConfirmAction(
            "Clear song cache",
            "Downloads are not affected",
            confirmTitle = "Clear song cache?",
            confirmText = "Cached songs are deleted (the song playing now is kept). They are cached again when played.",
            confirmLabel = "Clear",
        ) { AppDataEvents.request(AppDataRequest.ClearSongCache) }
    }
    SettingsGroup("Other caches") {
        SettingConfirmAction(
            "Clear image cache",
            measuring { "Artwork and player colours · ${formatBytes(imageCacheBytes)}" },
            confirmTitle = "Clear image cache?",
            confirmText = "Artwork is downloaded again when it is shown. Artwork of downloaded songs is kept.",
            confirmLabel = "Clear",
        ) { inBackground { OfflineBridge.clearColorCaches() } }
        SettingConfirmAction(
            "Clear lyrics cache",
            measuring { "Saved lyrics and translations · ${formatBytes(lyricsCacheBytes)}" },
            confirmTitle = "Clear lyrics cache?",
            confirmText = "Lyrics and translations are looked up again. Lyrics you picked by hand are reset to the automatic choice.",
            confirmLabel = "Clear",
        ) {
            inBackground {
                WindowsLyricsProviderRegistry.clearAllCaches()
                WindowsLyricsTranslator.clearMemory()
            }
        }
    }
    if (!AppPlatform.isPhone) SettingsGroup("Local music folders") {
        val folders = remember(generation) { LocalMusicBridge.folders() }
        if (folders.isEmpty()) SettingNote("No folders. Add one to see its songs in Library > Local Music.")
        folders.forEach { folder ->
            SettingRow(folder.name.ifBlank { folder.path }, folder.path, icon = null, value = "Remove") {
                inBackground { LocalMusicBridge.removeFolder(folder) }
            }
        }
        SettingRow("Add folder", "Choose a folder with music files") {
            pickMusicFolder("Add a music folder") { folder ->
                inBackground { LocalMusicBridge.addFolder(folder) }
            }
        }
    }
    SettingsGroup("Location") {
        SettingRow("Record Labs data", AppStorage.directory.absolutePath, value = "Open folder") {
            runCatching { openFolder(AppStorage.directory) }
        }
    }
}

private fun directoryBytes(directory: File): Long =
    directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

@Composable
private fun BackupSettings() {
    var includeAccounts by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var restoredNeedsRestart by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingsGroup("Backup") {
        SettingToggle(
            "Include sign-ins",
            "Also save your Google and Last.fm sign-ins in the backup (keep the file private)",
            includeAccounts,
        ) { includeAccounts = it }
        SettingRow("Create backup", "Settings, library, playlists, listening stats, and recent searches") {
            pickBackupSaveFile(BackupBridge.defaultFileName()) { chosen ->
                val target = chosen.let { if (it.extension.equals("zip", true)) it else File(it.path + ".zip") }
                val accounts = includeAccounts
                scope.launch {
                    message = kotlinx.coroutines.withContext(IoDispatcher) {
                        runCatching { BackupBridge.export(target, accounts) }
                            .fold({ "Backup saved to ${target.absolutePath}" }, { "Backup failed: ${it.message}" })
                    }
                }
            }
        }
    }
    SettingsGroup("Restore") {
        SettingRow("Restore from backup", "Replaces your current settings and library with the backup's") {
            pickBackupOpenFile { source ->
                scope.launch {
                    val result = kotlinx.coroutines.withContext(IoDispatcher) { runCatching { BackupBridge.restore(source) } }
                    result.onSuccess { restored ->
                        AppDataEvents.request(AppDataRequest.RestoreLibrary(restored.library, restored.searchHistory))
                        restoredNeedsRestart = true
                        message = "Backup restored (${restored.settingsCount} settings)."
                    }.onFailure { message = "Could not restore: ${it.message ?: "the file is damaged"}" }
                }
            }
        }
        SettingNote("Downloads and caches are not part of a backup.")
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Backup & restore") },
            text = {
                Text(
                    if (restoredNeedsRestart) "$text Close and reopen Record Labs to apply every setting." else text,
                    color = SecondaryText,
                )
            },
            confirmButton = { TextButton(onClick = { message = null; restoredNeedsRestart = false }) { Text("OK") } },
        )
    }
}

@Composable
internal fun UpdateCard(
    notes: String,
    title: String,
    subtitle: String,
    progress: Float? = null,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = SecondaryText, fontSize = 12.sp)
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            androidx.compose.material.LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colors.primary,
                backgroundColor = Color.White.copy(alpha = 0.12f),
            )
        }
        if (notes.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("What's new", color = Color.White.copy(alpha = 0.86f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(
                notes.replace("\r", "").lines().joinToString("\n") { it.trimStart('#', ' ').trimEnd() }.trim(),
                color = SecondaryText,
                fontSize = 12.sp,
                maxLines = 10,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

@Composable
private fun AboutSettings() {
    var showLicenses by remember { mutableStateOf(false) }
    SettingsGroup("About Record Labs") {
        val platformName = when {
            AppPlatform.isAndroid -> "Android"
            AppPlatform.isIos -> "iPhone"
            AppPlatform.isLinux -> "Linux"
            else -> "Windows"
        }
        SettingRow("Version", "Record Labs for $platformName", icon = null, value = AppInfo.version) {}
        SettingRow("Made by", "Byonkiee", icon = null) {}
        SettingRow("Open source licenses", "Libraries Record Labs is built with") { showLicenses = true }
    }
    if (desktopUpdatesConfigured && !AppPlatform.isAndroid && !AppPlatform.isIos) SettingsGroup("Updates") { DesktopUpdateSection() }
    // Android: new releases also come as a banner; here they can always be installed
    // (after "Not now" or "Always ignore" too), like on the desktop.
    if (AppPlatform.isAndroid) SettingsGroup("Updates") { AndroidUpdateSection() }
    if (showLicenses) AlertDialog(
        onDismissRequest = { showLicenses = false },
        title = { Text("Open source licenses") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp).dialogScrollFix()) {
                items(OpenSourceLicenses.entries) { (name, license) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(name, color = Color.White, modifier = Modifier.weight(1f))
                        Text(license, color = SecondaryText, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
    )
}

private object OpenSourceLicenses {
    val entries = listOf(
        "Kotlin & kotlinx (coroutines, serialization)" to "Apache 2.0",
        "Compose Multiplatform" to "Apache 2.0",
        "Ktor" to "Apache 2.0",
        "OkHttp" to "Apache 2.0",
        "Coil" to "Apache 2.0",
        "JNA" to "Apache 2.0 / LGPL 2.1",
        "vlcj" to "GPL 3.0",
        "OpenJFX" to "GPL 2.0 + Classpath Exception",
        "NewPipe Extractor (Metrolist)" to "GPL 3.0",
        "yt-dlp" to "Unlicense",
        "Deno" to "MIT",
        "ICU4J" to "Unicode License",
        "Kuromoji" to "Apache 2.0",
    )
}

// ---------------------------------------------------------------------------
// Profile > Integrations
// ---------------------------------------------------------------------------

@Composable
internal fun DiscordIntegrationPage() {
    val connection by DiscordRichPresence.state.collectAsState()
    val playback by DiscordRichPresence.playback.collectAsState()
    var enabled by AppSettings.discordEnabled
    var advanced by AppSettings.discordAdvanced
    var details by AppSettings.discordDetailsTemplate
    var stateText by AppSettings.discordStateTemplate
    var buttonEnabled by AppSettings.discordButtonEnabled
    var buttonLabel by AppSettings.discordButtonLabel
    var buttonUrl by AppSettings.discordButtonUrl
    var display by AppSettings.discordStatusDisplay
    // Every change is sent to Discord right away.
    fun changed() = DiscordRichPresence.settingsChanged()
    val pageScroll = rememberScrollState()
    Column(Modifier.pageScrollbar(pageScroll).verticalScroll(pageScroll)) {
        SettingsPageHeader("Discord Rich Presence", "Show the song you are listening to on your Discord profile", Icons.Default.Headset, compact = false)
        SettingsGroup("Connection") {
            // Android has no Discord desktop app to talk to: it uses a Discord login instead.
            val viaLogin = DiscordRichPresence.needsLogin
            val (statusTitle, statusText) = when (val state = connection) {
                DiscordConnectionState.Off -> "Off" to "Turn on Rich Presence below to connect to Discord"
                DiscordConnectionState.Connecting -> "Connecting…" to (if (viaLogin) "Connecting to Discord with your account" else "Looking for the Discord desktop app")
                DiscordConnectionState.DiscordNotRunning -> "Discord is not running" to "Open the Discord desktop app on this PC; Record Labs connects by itself"
                DiscordConnectionState.SignedOut -> "Not logged in" to "Log in with Discord below to show your music on your profile"
                is DiscordConnectionState.Connected -> "Connected" to (state.userName?.let { "Signed in to Discord as $it" } ?: "Connected to the Discord desktop app")
                is DiscordConnectionState.Failed -> "Connection problem" to state.message
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val avatar = (connection as? DiscordConnectionState.Connected)?.avatarUrl
                if (avatar != null) Artwork(avatar, Modifier.size(40.dp), androidx.compose.foundation.shape.CircleShape)
                else Box(Modifier.size(40.dp).background(Elevated, androidx.compose.foundation.shape.CircleShape))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(statusTitle, color = if (connection is DiscordConnectionState.Connected) Accent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(statusText, color = SecondaryText, fontSize = 12.sp)
                }
            }
            SettingToggle(
                "Enable Rich Presence",
                if (viaLogin) "Shows the song on your Discord profile through your Discord account"
                else "Uses the Discord desktop app on this PC · no Discord login needed in Record Labs",
                enabled,
            ) {
                enabled = it; changed()
            }
            if (viaLogin) {
                // Read again whenever the connection changes (logged in, connected, logged out).
                val loggedIn = remember(connection) { DiscordRichPresence.loggedIn }
                if (!loggedIn) {
                    SettingRow(
                        "Log in with Discord",
                        "Opens Discord's login page · Record Labs only asks to show your activity",
                        icon = null,
                        value = "Log in",
                    ) {
                        if (!enabled) enabled = true
                        changed()
                        DiscordRichPresence.login()
                    }
                } else {
                    SettingRow(
                        "Log out of Discord",
                        "Removes your Discord account from Record Labs",
                        icon = null,
                        value = "Log out",
                    ) { DiscordRichPresence.logout() }
                }
            }
            SettingToggle("Advanced mode", "Customize the activity text and button", advanced, enabled = enabled) {
                advanced = it; changed()
            }
        }
        if (enabled && advanced) {
            SettingsGroup("Presence") {
                DiscordTemplateRow("Details (first line)", details, DiscordDefaults.DETAILS) { details = it; changed() }
                DiscordTemplateRow("State (second line)", stateText, DiscordDefaults.STATE) { stateText = it; changed() }
                SettingChoice(
                    "Shown in the member list",
                    "What Discord shows next to your name",
                    listOf("name" to "Listening to Record Labs", "state" to "The artist", "details" to "The song title"),
                    display,
                ) { display = it; changed() }
            }
        }
        if (enabled) SettingsGroup("Button") {
            SettingToggle("Show a button", "A \"Listen on YouTube Music\" link other people can open from your profile", buttonEnabled) { buttonEnabled = it; changed() }
            if (buttonEnabled && advanced) {
                DiscordTemplateRow("Button label", buttonLabel, DiscordDefaults.BUTTON_LABEL, maxLength = 32) { buttonLabel = it; changed() }
                DiscordTemplateRow("Button link", buttonUrl, DiscordDefaults.BUTTON_URL, maxLength = 512) { buttonUrl = it; changed() }
            }
        }
        SettingsGroup("Preview") {
            DiscordPresencePreview(playback)
        }
        // Room at the end so the floating mini player never covers the last item.
        Spacer(Modifier.height(MiniPlayerClearance + LocalBottomBarInset.current))
    }
}

@Composable
private fun DiscordTemplateRow(
    title: String,
    value: String,
    default: String,
    maxLength: Int = 200,
    placeholders: Boolean = true,
    initialText: String = value,
    onSave: (String) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    SettingRow(title, value.ifBlank { default.ifBlank { "Not set" } }) { editing = true }
    if (editing) {
        var text by remember { mutableStateOf(initialText.ifBlank { default }) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column {
                    GlassTextField(
                        value = text,
                        onValueChange = { text = it.replace("\n", " ").take(maxLength) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                    )
                    if (placeholders) Spacer(Modifier.height(10.dp))
                    if (placeholders) Text("Insert", color = SecondaryText, fontSize = 12.sp)
                    if (placeholders) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DiscordRichPresence.PLACEHOLDERS.forEach { placeholder ->
                            Text(
                                placeholder,
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Elevated)
                                    .clickable { text = (text + placeholder).take(maxLength) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { text = default }) { Text("Reset") }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(text.trim()); editing = false }) { Text("Save") } },
        )
    }
}

/** Roughly how the activity looks on a Discord profile, from the current song. */
@Composable
private fun DiscordPresencePreview(playback: DiscordPlayback?) {
    val content = playback?.let { DiscordRichPresence.content(it) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (content == null) {
            Text("Play a song to see the preview.", color = SecondaryText, fontSize = 13.sp)
            return@Column
        }
        Text("LISTENING TO RECORD LABS", color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(playback.track?.artworkUrl, Modifier.size(72.dp), RoundedCornerShape(10.dp))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(content.details, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(content.state, color = Color.White.copy(alpha = .85f), fontSize = 13.sp, maxLines = 1)
                content.largeText?.let { Text(it, color = SecondaryText, fontSize = 12.sp, maxLines = 1) }
                Text(if (playback.isPlaying) "Playing · progress bar shown" else "Paused · cleared after 1 minute", color = SecondaryText, fontSize = 11.sp)
            }
        }
        if (content.buttonLabel != null) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Elevated).padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(content.buttonLabel, color = Color.White, fontSize = 13.sp) }
        }
    }
}

@Composable
internal fun LastFmIntegrationPage() {
    var apiKey by AppSettings.lastFmApiKey
    var secret by AppSettings.lastFmSecret
    val username by AppSettings.lastFmUsername
    val sessionKey by AppSettings.lastFmSessionKey
    var scrobbling by AppSettings.lastFmScrobbling
    var nowPlaying by AppSettings.lastFmNowPlaying
    var sendLikes by AppSettings.lastFmSendLikes
    var minDuration by AppSettings.lastFmMinDuration
    var delayPercent by AppSettings.lastFmDelayPercent
    var delaySeconds by AppSettings.lastFmDelaySeconds
    val lastResult by LastFmScrobbler.lastResult.collectAsState()
    val signedIn = sessionKey.isNotBlank() && apiKey.isNotBlank() && secret.isNotBlank()
    var showLogin by remember { mutableStateOf(false) }
    val pageScroll = rememberScrollState()
    Column(Modifier.pageScrollbar(pageScroll).verticalScroll(pageScroll)) {
        SettingsPageHeader("Last.fm", "Scrobble your listening history to Last.fm", Icons.Default.History, compact = false)
        SettingsGroup("API account") {
            SettingNote("Last.fm needs an API account. Create one for free at last.fm/api/account/create, then paste its API key and shared secret here.")
            DiscordTemplateRow("API key", apiKey, "", maxLength = 64, placeholders = false) { apiKey = it }
            DiscordTemplateRow("Shared secret", if (secret.isBlank()) "" else "••••••••", "", maxLength = 64, placeholders = false, initialText = secret) { secret = it }
            SettingRow("Get an API account", "Opens last.fm in your browser") {
                AppPlatform.openUrl("https://www.last.fm/api/account/create")
            }
        }
        SettingsGroup("Account") {
            SettingRow(
                if (signedIn) username.ifBlank { "Signed in" } else "Not signed in",
                if (signedIn) "Scrobbles go to this Last.fm account" else "Sign in with your Last.fm username and password",
                icon = null,
                value = if (signedIn) "Sign out" else "Sign in",
                enabled = signedIn || (apiKey.isNotBlank() && secret.isNotBlank()),
            ) { if (signedIn) LastFmScrobbler.signOut() else showLogin = true }
            lastResult?.let { SettingNote(it) }
        }
        SettingsGroup("Options") {
            SettingToggle("Scrobbling", "Send the songs you listen to to Last.fm", scrobbling, enabled = signedIn) { scrobbling = it }
            SettingToggle("Now playing", "Show the current song on your Last.fm profile", nowPlaying, enabled = signedIn && scrobbling) { nowPlaying = it }
            SettingToggle("Send likes", "Liking a song in Record Labs loves it on Last.fm", sendLikes, enabled = signedIn) { sendLikes = it }
        }
        SettingsGroup("When to scrobble") {
            SettingSlider("Shortest song scrobbled", "$minDuration s", minDuration.toFloat(), 10f..60f, onReset = { minDuration = 30 }) { minDuration = it.roundToInt() }
            SettingSlider(
                "After this part of a song",
                "${(delayPercent * 100).roundToInt()}%",
                delayPercent,
                0.3f..0.95f,
                onReset = { delayPercent = 0.5f },
            ) { delayPercent = (it * 100).roundToInt() / 100f }
            SettingSlider(
                "Or after",
                "${delaySeconds / 60}:${(delaySeconds % 60).toString().padStart(2, '0')}",
                delaySeconds.toFloat(),
                30f..360f,
                subtitle = "Whichever comes first",
                onReset = { delaySeconds = 180 },
            ) { delaySeconds = (it / 10f).roundToInt() * 10 }
        }
        // Room at the end so the floating mini player never covers the last item.
        Spacer(Modifier.height(MiniPlayerClearance + LocalBottomBarInset.current))
    }
    if (showLogin) LastFmLoginDialog { showLogin = false }
}

@Composable
private fun LastFmLoginDialog(onDismiss: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Sign in to Last.fm") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth().trackTextInputFocus())
                GlassTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().trackTextInputFocus(),
                )
                error?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                Text("Your password is only used to sign in and is not saved.", color = SecondaryText, fontSize = 12.sp)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = {
            TextButton(
                enabled = !busy && user.isNotBlank() && password.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        val problem = LastFmScrobbler.signIn(user, password)
                        busy = false
                        if (problem == null) onDismiss() else error = problem
                    }
                },
            ) { Text(if (busy) "Signing in…" else "Sign in") }
        },
    )
}

