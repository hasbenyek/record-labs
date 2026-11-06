package com.recordlabs.desktop.data

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsRecordLabsConnectIntegrationTest {
    @Test
    fun `remote controller queue move reaches Windows host with stable track id`() {
        val accountKey = "email:queue-move-host-test@recordlabs.invalid"
        val account = fingerprint(accountKey)
        val androidId = "queue-controller-android"
        val command = AtomicReference<Triple<String, Long?, Track?>?>()

        WindowsRecordLabsConnect.start(
            accountKeys = listOf(accountKey),
            deviceName = "Queue Host Windows",
            playbackProvider = {
                ConnectPlaybackState(
                    WindowsRecordLabsConnect.localDeviceId,
                    Track("current", "Current", "Record Labs", album = "Tests", artworkUrl = null, durationMs = 60_000L),
                    1_000L,
                    60_000L,
                    true,
                    System.currentTimeMillis(),
                )
            },
            onRemoteState = {},
            onCommand = { name, position, track -> command.set(Triple(name, position, track)) },
        )

        try {
            connectEventually().use { socket ->
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                writer.write(hello(androidId, account))
                writer.newLine()
                writer.write(queueMoveCommand(androidId, account, "move-me", 3))
                writer.newLine()
                writer.flush()

                await { command.get()?.first == "queue_move" }
                assertEquals(3L, command.get()?.second)
                assertEquals("move-me", command.get()?.third?.id)
            }
        } finally {
            WindowsRecordLabsConnect.stop()
        }
    }

    @Test
    fun `Windows controller sends queue move and prepared acknowledgement to Android host`() {
        val accountKey = "email:queue-move-controller-test@recordlabs.invalid"
        val account = fingerprint(accountKey)
        val androidId = "queue-host-android"

        WindowsRecordLabsConnect.start(
            accountKeys = listOf(accountKey),
            deviceName = "Queue Controller Windows",
            playbackProvider = { null },
            onRemoteState = {},
            onCommand = { _, _, _ -> },
        )

        try {
            connectEventually().use { socket ->
                socket.soTimeout = 4_000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.write(hello(androidId, account))
                writer.newLine()
                writer.write(state(androidId, account))
                writer.newLine()
                writer.flush()
                await { WindowsRecordLabsConnect.remoteOwnerId.value == androidId }

                assertTrue(WindowsRecordLabsConnect.sendQueueMove("move-me", 2))
                val move = generateSequence { reader.readLine() }
                    .first { it.contains("\"command\":\"queue_move\"") }
                assertTrue(move.contains("\"trackId\":\"move-me\""), move)
                assertTrue(move.contains("\"positionMs\":2"), move)

                WindowsRecordLabsConnect.sendTrackReady(androidId, "remote-track")
                val ready = generateSequence { reader.readLine() }
                    .first { it.contains("\"command\":\"track_ready\"") }
                assertTrue(ready.contains("\"trackId\":\"remote-track\""), ready)
            }
        } finally {
            WindowsRecordLabsConnect.stop()
        }
    }

    @Test
    fun `controller requested handoff makes Windows remote before Android is audible`() {
        val accountKey = "email:controller-handoff-test@recordlabs.invalid"
        val account = fingerprint(accountKey)
        val androidId = "controller-android-peer"
        val track = Track("host-track", "Host", "Record Labs", album = "Tests", artworkUrl = null, durationMs = 60_000L)

        WindowsRecordLabsConnect.start(
            accountKeys = listOf(accountKey),
            deviceName = "Handoff Windows",
            playbackProvider = {
                ConnectPlaybackState(
                    WindowsRecordLabsConnect.localDeviceId,
                    track,
                    10_000L,
                    track.durationMs,
                    true,
                    System.currentTimeMillis(),
                )
            },
            onRemoteState = {},
            onCommand = { _, _, _ -> },
        )

        try {
            connectEventually().use { socket ->
                socket.soTimeout = 4_000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                writer.write(hello(androidId, account))
                writer.newLine()
                writer.flush()
                await { WindowsRecordLabsConnect.devices.value.any { it.id == androidId } }

                // This is Android's "This Android" tap while Windows owns output.
                writer.write(transferPlaybackCommand(androidId, account, androidId))
                writer.newLine()
                writer.flush()

                await { WindowsRecordLabsConnect.remoteOwnerId.value == androidId }
                val handoff = generateSequence { reader.readLine() }
                    .first { it.contains("\"command\":\"take_ownership\"") }
                assertTrue(handoff.contains("\"target\":\"$androidId\""), handoff)
            }
        } finally {
            WindowsRecordLabsConnect.stop()
        }
    }

    @Test
    fun `paused restored Android state cannot replace active Windows playback`() {
        val accountKey = "email:active-owner-test@recordlabs.invalid"
        val account = fingerprint(accountKey)
        val androidId = "restored-android-peer"
        val windowsTrack = Track(
            id = "revolving-door",
            title = "Revolving door",
            artist = "Record Labs",
            album = "Tests",
            artworkUrl = null,
            durationMs = 180_000L,
        )
        val latestRemoteState = AtomicReference<ConnectPlaybackState?>()

        WindowsRecordLabsConnect.start(
            accountKeys = listOf(accountKey),
            deviceName = "Active Windows",
            playbackProvider = {
                ConnectPlaybackState(
                    ownerId = WindowsRecordLabsConnect.localDeviceId,
                    track = windowsTrack,
                    positionMs = 30_000L,
                    durationMs = windowsTrack.durationMs,
                    isPlaying = true,
                    sentAtMs = System.currentTimeMillis(),
                )
            },
            onRemoteState = latestRemoteState::set,
            onCommand = { _, _, _ -> },
        )

        try {
            connectEventually().use { socket ->
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                writer.write(hello(androidId, account))
                writer.newLine()
                writer.flush()
                await { WindowsRecordLabsConnect.devices.value.any { it.id == androidId } }

                // This simulates Android reopening with Track 10 restored but paused.
                writer.write(state(androidId, account, ownerTerm = 999L, playing = false))
                writer.newLine()
                writer.flush()
                Thread.sleep(400L)
                assertFalse(WindowsRecordLabsConnect.hasRemotePlaybackDevice())
                assertEquals(null, latestRemoteState.get())

                // An explicit user-selected transfer must still be accepted.
                assertTrue(WindowsRecordLabsConnect.selectPlaybackDevice(androidId))
                writer.write(state(androidId, account, ownerTerm = 1_000L, playing = true))
                writer.newLine()
                writer.flush()
                await { latestRemoteState.get()?.track?.id == "remote-track" }
                assertTrue(WindowsRecordLabsConnect.hasRemotePlaybackDevice())
            }
        } finally {
            WindowsRecordLabsConnect.stop()
        }
    }

    @Test
    fun `tcp peer is discovered and paused local playback does not lock ownership`() {
        val accountKey = "email:connect-test@recordlabs.invalid"
        val account = fingerprint(accountKey)
        val androidId = "integration-android-peer"
        val pausedTrack = Track(
            id = "paused-track",
            title = "Paused",
            artist = "Record Labs",
            album = "Tests",
            artworkUrl = null,
            durationMs = 60_000L,
        )
        val latestRemoteState = AtomicReference<ConnectPlaybackState?>()

        WindowsRecordLabsConnect.start(
            accountKeys = listOf(accountKey),
            deviceName = "Integration Windows",
            playbackProvider = {
                ConnectPlaybackState(
                    ownerId = WindowsRecordLabsConnect.localDeviceId,
                    track = pausedTrack,
                    positionMs = 5_000L,
                    durationMs = 60_000L,
                    isPlaying = false,
                    sentAtMs = System.currentTimeMillis(),
                )
            },
            onRemoteState = latestRemoteState::set,
            onCommand = { _, _, _ -> },
        )

        try {
            connectEventually().use { socket ->
                socket.soTimeout = 4_000
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                writer.write(hello(androidId, account))
                writer.newLine()
                writer.flush()

                await { WindowsRecordLabsConnect.devices.value.any { it.id == androidId } }
                assertEquals("android", WindowsRecordLabsConnect.devices.value.single { it.id == androidId }.platform)
                assertTrue(WindowsRecordLabsConnect.discoveryStatus.value.receivedPackets >= 1L)

                // The old implementation removed this peer after eight
                // seconds even though its reverse TCP socket was still open.
                Thread.sleep(8_500L)
                assertTrue(WindowsRecordLabsConnect.devices.value.any { it.id == androidId })

                val reply = generateSequence { reader.readLine() }
                    .first { it.contains("\"device\":\"${WindowsRecordLabsConnect.localDeviceId}\"") }
                assertTrue(reply.contains("\"type\":\"hello\""), reply)

                writer.write(state(androidId, account))
                writer.newLine()
                writer.flush()
                await { WindowsRecordLabsConnect.hasRemotePlaybackDevice() }
                await { latestRemoteState.get()?.track?.id == "remote-track" }

                // Android selecting This Windows PC must transfer ownership
                // back over the same TCP link and make Windows the output.
                writer.write(takeOwnershipCommand(androidId, account))
                writer.newLine()
                writer.flush()
                await { !WindowsRecordLabsConnect.hasRemotePlaybackDevice() }
                val ownershipReply = generateSequence { reader.readLine() }
                    .first { it.contains("\"type\":\"ownership\"") }
                assertTrue(ownershipReply.contains("\"owner\":\"${WindowsRecordLabsConnect.localDeviceId}\""), ownershipReply)

                // Pausing must not silently release the session owner. The
                // controller still needs a state heartbeat so it remains a
                // full remote and can resume/skip the Windows player later.
                val pausedOwnerState = generateSequence { reader.readLine() }
                    .first { it.contains("\"type\":\"state\"") }
                assertTrue(pausedOwnerState.contains("\"owner\":\"${WindowsRecordLabsConnect.localDeviceId}\""), pausedOwnerState)
                assertTrue(pausedOwnerState.contains("\"playing\":false"), pausedOwnerState)
                assertFalse(WindowsRecordLabsConnect.hasRemotePlaybackDevice())

                // A later Android state must still be accepted and exposed to
                // the Windows mini player after the reverse handoff.
                writer.write(state(androidId, account, ownerTerm = 101L))
                writer.newLine()
                writer.flush()
                await { WindowsRecordLabsConnect.hasRemotePlaybackDevice() }
                writer.write(hello(androidId, account))
                writer.newLine()
                writer.flush()
                // UDP/TCP can reorder a presence heartbeat behind the state
                // packet. A stale hello must not steal output back to Windows.
                Thread.sleep(250L)
                assertTrue(WindowsRecordLabsConnect.hasRemotePlaybackDevice())
            }
        } finally {
            WindowsRecordLabsConnect.stop()
        }
    }

    private fun hello(device: String, account: String) =
        """{"v":3,"type":"hello","account":"$account","accounts":["$account"],"device":"$device","sentAt":${System.currentTimeMillis()},"name":"Integration Android","platform":"android"}"""

    private fun state(device: String, account: String, ownerTerm: Long = 99L, playing: Boolean = true) =
        """{"v":3,"type":"state","account":"$account","accounts":["$account"],"device":"$device","sentAt":${System.currentTimeMillis()},"name":"Integration Android","platform":"android","owner":"$device","ownerTerm":$ownerTerm,"trackId":"remote-track","title":"Remote","artist":"Record Labs","album":"Tests","artwork":"","positionMs":1000,"durationMs":60000,"playing":$playing}"""

    private fun takeOwnershipCommand(device: String, account: String) =
        """{"v":3,"type":"command","account":"$account","accounts":["$account"],"device":"$device","sentAt":${System.currentTimeMillis()},"target":"${WindowsRecordLabsConnect.localDeviceId}","command":"take_ownership"}"""

    private fun transferPlaybackCommand(device: String, account: String, outputDevice: String) =
        """{"v":3,"type":"command","account":"$account","accounts":["$account"],"device":"$device","sentAt":${System.currentTimeMillis()},"target":"${WindowsRecordLabsConnect.localDeviceId}","command":"transfer_playback","outputDevice":"$outputDevice"}"""

    private fun queueMoveCommand(device: String, account: String, trackId: String, toIndex: Int) =
        """{"v":3,"type":"command","account":"$account","accounts":["$account"],"device":"$device","sentAt":${System.currentTimeMillis()},"target":"${WindowsRecordLabsConnect.localDeviceId}","command":"queue_move","trackId":"$trackId","positionMs":$toIndex}"""

    private fun fingerprint(accountKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest("record-labs-connect-v2:${accountKey.trim().lowercase()}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun await(timeoutMs: Long = 4_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25L)
        assertTrue(condition(), "condition was not met within ${timeoutMs}ms")
    }

    private fun connectEventually(timeoutMs: Long = 4_000L): Socket {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return Socket("127.0.0.1", 45_923)
            } catch (failure: Throwable) {
                lastFailure = failure
                Thread.sleep(25L)
            }
        }
        throw AssertionError("TCP listener did not become ready", lastFailure)
    }
}
