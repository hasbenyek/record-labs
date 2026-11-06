package com.recordlabs.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopUiScaleTest {

    @Test
    fun testDetectScaleForDisplay14InchFhd() {
        // Standard 14.0" 16:9 1080p laptop display (DPI ~ 157.35)
        val scale14Fhd = DesktopUiScale.detectScaleForDisplay(14.0, 1920, 1080)
        assertEquals(1.25f, scale14Fhd, "14-inch Full HD screen should scale to 125%")

        // 14.0" 16:10 1200p laptop display (1920x1200, DPI ~ 161.7)
        val scale14Fhd1610 = DesktopUiScale.detectScaleForDisplay(14.0, 1920, 1200)
        assertEquals(1.25f, scale14Fhd1610, "14-inch 16:10 1200p screen should scale to 125%")

        // 13.3" 1080p laptop display (DPI ~ 165.6)
        val scale133Fhd = DesktopUiScale.detectScaleForDisplay(13.3, 1920, 1080)
        assertEquals(1.25f, scale133Fhd, "13.3-inch Full HD screen should scale to 125%")
    }

    @Test
    fun testDetectScaleForDisplay156InchFhd() {
        // Standard 15.6" 1080p laptop display (DPI ~ 141.2)
        val scale156Fhd = DesktopUiScale.detectScaleForDisplay(15.6, 1920, 1080)
        assertEquals(1.0f, scale156Fhd, "15.6-inch Full HD screen should stay at 100%")

        // 15.3" 1080p laptop display (e.g. 34x19 cm)
        val scale153Fhd = DesktopUiScale.detectScaleForDisplay(15.3, 1920, 1080)
        assertEquals(1.0f, scale153Fhd, "15.3-inch Full HD screen should stay at 100%")
    }

    @Test
    fun testDetectScaleForDesktopMonitors() {
        // 24" 1080p desktop monitor (DPI ~ 91.8)
        val scale24Fhd = DesktopUiScale.detectScaleForDisplay(24.0, 1920, 1080)
        assertEquals(1.0f, scale24Fhd, "24-inch Full HD monitor should stay at 100%")

        // 27" 1440p QHD desktop monitor (DPI ~ 108.8)
        val scale27Qhd = DesktopUiScale.detectScaleForDisplay(27.0, 2560, 1440)
        assertEquals(1.0f, scale27Qhd, "27-inch QHD monitor should stay at 100%")
    }

    @Test
    fun testDetectScaleFor4kDisplays() {
        // 27" 4K UHD monitor (DPI ~ 163.2, 3840x2160)
        val scale4k = DesktopUiScale.detectScaleForDisplay(15.6, 3840, 2160)
        assertEquals(2.0f, scale4k, "15.6-inch 4K screen should scale to 200%")
    }

    @Test
    fun testExplicitScaleSelection() {
        DesktopUiScale.saveMode("1.25")
        assertEquals(1.25f, DesktopUiScale.effectiveDensityScale(), "Explicit scale 1.25 should be honored")

        DesktopUiScale.saveMode("1.5")
        assertEquals(1.5f, DesktopUiScale.effectiveDensityScale(), "Explicit scale 1.5 should be honored")

        DesktopUiScale.saveMode("0.9")
        assertEquals(0.9f, DesktopUiScale.effectiveDensityScale(), "Explicit scale 0.9 should be honored")

        // Reset to auto
        DesktopUiScale.saveMode("auto")
        assertEquals("auto", DesktopUiScale.selectedMode())
    }

    @Test
    fun testMutterScaleRegexParsing() {
        val regex = Regex("""\(\s*\d+\s*,\s*\d+\s*,\s*([0-9.]+)\s*,\s*uint32\s+\d+\s*,\s*(true|false)""")
        
        // Simulating GNOME Mutter with 125% scale
        val sample125 = "[(0, 0, 1.25, uint32 0, true, [('eDP-1', 'AUO', '0x7ead', '0x00007ead')], @a{sv} {})]"
        val match125 = regex.find(sample125)
        assertEquals("1.25", match125?.groupValues?.get(1))
        assertEquals("true", match125?.groupValues?.get(2))

        // Simulating GNOME Mutter with 166% scale
        val sample166 = "[(0, 0, 1.6666666269302368, uint32 0, true, [('eDP-1', ...)])]"
        val match166 = regex.find(sample166)
        assertEquals("1.6666666269302368", match166?.groupValues?.get(1))
        assertEquals("true", match166?.groupValues?.get(2))
    }
}
