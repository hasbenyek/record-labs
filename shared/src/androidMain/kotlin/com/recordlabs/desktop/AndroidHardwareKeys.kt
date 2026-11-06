package com.recordlabs.desktop

import android.view.KeyEvent

/**
 * Keys of a hardware keyboard that the app handles before the focused view: MainActivity
 * passes every key event here first.
 */
object AndroidHardwareKeys {
    /** True when the event was handled (MainActivity then stops it). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        // \ shows the header on the player pages (as on the desktop), unless a text field
        // is being typed in.
        if (event.keyCode == KeyEvent.KEYCODE_BACKSLASH && !DesktopTextInputFocus.active &&
            !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) PlayerHeaderRequest.request()
            return true
        }
        return false
    }
}
