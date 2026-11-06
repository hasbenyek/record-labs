package com.recordlabs.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * XWayland native scaling (Fedora GNOME default): the app must render at
 * mutter's integer X11 scale, and nothing must change where it isn't active.
 */
class DesktopUiScaleNativeScalingTest {

    /** Text in the shape `gdbus call ... GetCurrentState` prints. */
    private fun mutterState(
        connector: String,
        width: Int,
        height: Int,
        scale: String,
        layoutMode: Int,
        transform: Int = 0,
    ) = "(uint32 4, [(('$connector', 'BOE', '0x0a1c', '0x00000000'), " +
        "[('${width}x$height@60.000', $width, $height, 60.0, $scale, [1.0, 1.25, 1.5, 1.6666666269302368, 2.0], " +
        "{'is-current': <true>, 'is-preferred': <true>}), " +
        "('1280x800@60.000', 1280, 800, 60.0, 1.0, [1.0, 1.25], {})], " +
        "{'is-builtin': <true>, 'display-name': <'Built-in display'>})], " +
        "[(0, 0, $scale, uint32 $transform, true, [('$connector', 'BOE', '0x0a1c', '0x00000000')], @a{sv} {})], " +
        "{'layout-mode': <uint32 $layoutMode>, 'supports-changing-layout-mode': <true>, " +
        "'global-scale-required': <false>, 'legacy-ui-scaling-factor': <1>})"

    @Test
    fun parsesCurrentModeAndLogicalMonitor() {
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 2880, 1800, "1.6666666269302368", 1))
        assertEquals(1, state.layoutMode)
        assertEquals(2880 to 1800, state.currentModes["eDP-1"])
        assertEquals(1, state.logicalMonitors.size)
        assertEquals(1.6666666f, state.primaryScale!!, 0.0001f)
        assertEquals(1728, state.logicalLayoutWidth())
    }

    @Test
    fun fedora125PercentRendersAt2x() {
        // 1920x1080 at 125%: logical 1536 wide, Xwayland screen 1536 * 2 = 3072.
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 1920, 1080, "1.25", 1))
        val native = DesktopUiScale.decideXwaylandNativeScaling(state, 3072, null)
        assertEquals(DesktopUiScale.XwaylandNativeScaling(2, 1.25f), native)
        assertEquals(1.0f, DesktopUiScale.nativeScalingDensity("auto", native!!))
        assertEquals(1.0f, DesktopUiScale.nativeScalingDensity("1.25", native), 0.0001f)
        assertEquals(0.8f, DesktopUiScale.nativeScalingDensity("1.0", native), 0.0001f)
    }

    @Test
    fun fedora166PercentRendersAt2x() {
        // 2880x1800 at 166%: logical 1728 wide, Xwayland screen 1728 * 2 = 3456.
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 2880, 1800, "1.6666666269302368", 1))
        val native = DesktopUiScale.decideXwaylandNativeScaling(state, 3456, null)
        assertEquals(2, native?.x11Scale)
        assertEquals(1.0f, DesktopUiScale.nativeScalingDensity("auto", native!!))
    }

    @Test
    fun scale100PercentKeepsOriginalBehaviour() {
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 1920, 1080, "1.0", 1))
        assertNull(DesktopUiScale.decideXwaylandNativeScaling(state, 1920, "['scale-monitor-framebuffer', 'xwayland-native-scaling']"))
    }

    @Test
    fun compositorStretchedXwaylandKeepsOriginalBehaviour() {
        // Fractional scaling without native Xwayland scaling: X screen = logical size.
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 1920, 1080, "1.25", 1))
        assertNull(DesktopUiScale.decideXwaylandNativeScaling(state, 1536, "['scale-monitor-framebuffer']"))
    }

    @Test
    fun physicalLayoutKeepsOriginalBehaviour() {
        // Stock GNOME (e.g. Debian) at 200% uses the physical layout mode.
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 3840, 2160, "2.0", 2))
        assertNull(DesktopUiScale.decideXwaylandNativeScaling(state, 3840, "@as []"))
    }

    @Test
    fun fallsBackToGnomeSettingWhenXScreenUnknown() {
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 1920, 1080, "1.25", 1))
        assertEquals(
            DesktopUiScale.XwaylandNativeScaling(2, 1.25f),
            DesktopUiScale.decideXwaylandNativeScaling(state, null, "['scale-monitor-framebuffer', 'xwayland-native-scaling']"),
        )
        assertNull(DesktopUiScale.decideXwaylandNativeScaling(state, null, "['scale-monitor-framebuffer']"))
        assertNull(DesktopUiScale.decideXwaylandNativeScaling(state, null, null))
    }

    @Test
    fun rotatedMonitorUsesSwappedWidth() {
        // Portrait (transform 1): 1080 px wide at 125% -> 864 logical, X screen 1728.
        val state = DesktopUiScale.parseMutterState(mutterState("eDP-1", 1920, 1080, "1.25", 1, transform = 1))
        assertEquals(864, state.logicalLayoutWidth())
        assertEquals(2, DesktopUiScale.decideXwaylandNativeScaling(state, 1728, null)?.x11Scale)
    }

    @Test
    fun twoMonitorsUseWholeDesktopWidth() {
        val output = "(uint32 7, [" +
            "(('eDP-1', 'BOE', '0x1', '0x0'), [('1920x1080@60.000', 1920, 1080, 60.0, 1.25, [1.0, 1.25], {'is-current': <true>})], {}), " +
            "(('HDMI-1', 'DEL', '0x2', '0x0'), [('2560x1440@59.951', 2560, 1440, 59.951, 1.0, [1.0], {'is-current': <true>, 'is-preferred': <true>}), " +
            "('1920x1080@60.000', 1920, 1080, 60.0, 1.0, [1.0], {})], {})], " +
            "[(0, 0, 1.25, uint32 0, true, [('eDP-1', 'BOE', '0x1', '0x0')], @a{sv} {}), " +
            "(1536, 0, 1.0, uint32 0, false, [('HDMI-1', 'DEL', '0x2', '0x0')], @a{sv} {})], " +
            "{'layout-mode': <uint32 1>})"
        val state = DesktopUiScale.parseMutterState(output)
        assertEquals(2560 to 1440, state.currentModes["HDMI-1"])
        assertEquals(1536 + 2560, state.logicalLayoutWidth())
        val native = DesktopUiScale.decideXwaylandNativeScaling(state, (1536 + 2560) * 2, null)
        assertEquals(DesktopUiScale.XwaylandNativeScaling(2, 1.25f), native)
    }
}
