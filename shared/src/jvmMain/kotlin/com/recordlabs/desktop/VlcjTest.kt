package com.recordlabs.desktop

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.nio.ByteBuffer
import java.io.File
import com.sun.jna.NativeLibrary

fun main() {
    val vlcPath = File("src/main/resources/vlc").absolutePath
    NativeLibrary.addSearchPath("libvlc", vlcPath)
    NativeLibrary.addSearchPath("libvlccore", vlcPath)
    System.setProperty("VLC_PLUGIN_PATH", File(vlcPath, "plugins").absolutePath)

    val factory = MediaPlayerFactory("--no-video-title-show", "--no-audio", "--avcodec-hw=none")
    val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
    val surface = factory.videoSurfaces().newVideoSurface(
        object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                println("Format requested: " + sourceWidth + "x" + sourceHeight)
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {
                println("Buffers allocated")
            }
        },
        object : RenderCallback {
            var count = 0
            override fun display(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                if (count++ < 10) println("Frame received!")
            }
        },
        true
    )
    player.videoSurface().set(surface)
    
    val cacheDir = File(System.getProperty("user.home"), "AppData/Roaming/Record Labs/animated-artwork")
    val files = cacheDir.listFiles()
    if (files != null && files.isNotEmpty()) {
        val testFile = files.first { it.length() > 10000 }.absolutePath
        println("Playing: " + testFile)
        player.media().play(testFile)
    } else {
        println("No files in cache")
    }
    Thread.sleep(5000)
    player.release()
    factory.release()
}
