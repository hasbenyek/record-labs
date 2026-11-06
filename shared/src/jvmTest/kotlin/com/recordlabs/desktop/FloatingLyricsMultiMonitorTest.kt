package com.recordlabs.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FloatingLyricsMultiMonitorTest {

    @Test
    fun testScreenTopologySignatureNotEmpty() {
        val sig = getScreenTopologySignature()
        assertNotNull(sig)
    }

    @Test
    fun testGetAllUsableScreenBoundsNotEmpty() {
        val bounds = getAllUsableScreenBounds()
        assertFalse(bounds.isEmpty(), "Should find at least 1 screen bound")
        val primary = bounds.first()
        assertTrue(primary.width > 0)
        assertTrue(primary.height > 0)
    }

    @Test
    fun testClampPositionWhenWindowIsFarOffScreenRecoversToSafeScreen() {
        // Assume coordinates far off into the void (e.g. from an unplugged secondary monitor)
        val farX = -99999
        val farY = -99999
        val (safeX, safeY) = clampPositionToScreens(farX, farY, 720, 175)

        val usableScreens = getAllUsableScreenBounds()
        val windowRect = java.awt.Rectangle(safeX, safeY, 720, 175)
        val intersectsAny = usableScreens.any { !it.intersection(windowRect).isEmpty }
        assertTrue(intersectsAny, "Recovered position must be inside an active screen")
    }

    @Test
    fun testClampPositionWhenWithinPrimaryScreenBoundsPreservesPosition() {
        val primary = getPrimaryUsableScreenBounds()
        val targetX = primary.x + 50
        val targetY = primary.y + 50

        val (clampedX, clampedY) = clampPositionToScreens(targetX, targetY, 720, 175)
        assertEquals(targetX, clampedX)
        assertEquals(targetY, clampedY)
    }

    @Test
    fun testEnsureWindowTranslucencyDoesNotThrow() {
        val f = javax.swing.JFrame()
        f.isUndecorated = true
        f.background = java.awt.Color(0, 0, 0, 0)
        f.setSize(200, 100)
        ensureWindowTranslucency(f)
        f.dispose()
    }

    @Test
    fun testWindowTranslucencyAcrossMultiMonitors() {
        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screens = ge.screenDevices
        if (screens.size < 2) return

        val f = javax.swing.JFrame()
        f.isUndecorated = true
        f.background = java.awt.Color(0, 0, 0, 0)
        f.setSize(300, 200)
        f.setLocation(100, 100)

        val propListener = java.beans.PropertyChangeListener { evt ->
            if (evt.propertyName == "graphicsConfiguration") {
                ensureWindowTranslucency(f)
            }
        }
        f.addPropertyChangeListener("graphicsConfiguration", propListener)
        f.isVisible = true

        Thread.sleep(800)
        assertTrue(f.graphicsConfiguration?.isTranslucencyCapable() == true, "Screen 0 must be translucent initially")

        // Move to screen 1
        val screen1Bounds = screens[1].defaultConfiguration.bounds
        f.setLocation(screen1Bounds.x + 100, screen1Bounds.y + 100)
        Thread.sleep(1000)

        assertTrue(f.graphicsConfiguration?.isTranslucencyCapable() == true, "Screen 1 must be translucent after move")

        // Move back to screen 0
        f.setLocation(100, 100)
        Thread.sleep(1000)

        assertTrue(f.graphicsConfiguration?.isTranslucencyCapable() == true, "Screen 0 must be translucent after moving back")
        f.dispose()
    }
}
