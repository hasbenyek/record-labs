package com.recordlabs.desktop

import javax.swing.SwingUtilities
import java.util.concurrent.CountDownLatch

fun main() {
    val latch = CountDownLatch(1)
    var executed = false
    SwingUtilities.invokeLater {
        executed = true
        latch.countDown()
    }
    latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
    println("Swing executed: " + executed)
}
