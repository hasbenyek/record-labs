package com.recordlabs.desktop.data

import io.github.jaredmdobson.concentus.OpusDecoder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lines up a song with its music video by listening to both, the way YouTube
 * Music's own Song / Video switch keeps its place: a music video usually has
 * an intro (a scene, dialogue) before the song starts, sometimes a break in
 * the middle, and an outro. The two audio tracks are compared in short pieces
 * and the result is a list of parts of the song with where each one plays in
 * the video.
 */
internal object AudioAlignment {
    /** Samples per second the audio is decoded at (plenty to recognise music). */
    const val SAMPLE_RATE = 8_000
    private const val FFT_SIZE = 512
    /** One feature frame every 50 ms. */
    private const val HOP = 400
    private const val BANDS = 16
    /** Compared pieces: 6 s long, one every 3 s. */
    private const val WINDOW = 120
    private const val WINDOW_STEP = 60
    /** Offsets within 0.1 s count as the same. */
    private const val SAME_OFFSET = 2
    /** Cost of changing the offset between two pieces (a repeated chorus must not tempt it). */
    private const val CHANGE_PENALTY = 1.5f
    /** A music video does not play an earlier part of the song again in its place (a repeated chorus only sounds alike). */
    private const val BACKWARD_PENALTY = 50.0f
    /** Below this average match the two are treated as different recordings. */
    private const val MIN_AVERAGE_SCORE = 0.62f
    private const val CANDIDATES = 6

    private const val FRAME_MS = HOP * 1000L / SAMPLE_RATE

    /**
     * Parts of [song] (mono PCM at [SAMPLE_RATE]) and where they play in
     * [video], or null when they do not match well enough.
     */
    fun align(song: ShortArray, video: ShortArray): List<CounterpartSegment>? {
        val s = features(song)
        val v = features(video)
        if (s.size < WINDOW * 2 || v.size < WINDOW) return null
        val starts = (0..(s.size - WINDOW) step WINDOW_STEP).toList()
        // How well each piece matches at every video position.
        val scores = starts.map { start -> matchScores(s, start, v) }
        if (scores.any { it.isEmpty() }) return null
        // Candidates: each piece's own best offsets, plus every offset that is best
        // somewhere, so a piece that matches nothing well (a part the video
        // replaces with a scene) can still keep the offset around it.
        val own = starts.indices.map { i -> bestOffsets(scores[i], starts[i]) }
        val common = mutableListOf<Int>()
        own.flatten().sortedByDescending { it.second }.forEach { (offset, _) ->
            if (common.none { abs(it - offset) <= SAME_OFFSET }) common += offset
        }
        val candidates = starts.indices.map { i ->
            val list = own[i].toMutableList()
            common.forEach { offset ->
                val position = starts[i] + offset
                if (position in scores[i].indices && list.none { abs(it.first - offset) <= SAME_OFFSET }) {
                    list += offset to scores[i][position]
                }
            }
            list
        }
        // Choose one offset per piece: high scores, few changes (Viterbi).
        val n = starts.size
        val total = Array(n) { FloatArray(candidates[it].size) }
        val from = Array(n) { IntArray(candidates[it].size) }
        candidates[0].forEachIndexed { j, c -> total[0][j] = c.second }
        for (i in 1 until n) {
            candidates[i].forEachIndexed { j, (offset, score) ->
                var best = Float.NEGATIVE_INFINITY
                var bestK = 0
                candidates[i - 1].forEachIndexed { k, (previous, _) ->
                    val value = total[i - 1][k] - when {
                        abs(previous - offset) <= SAME_OFFSET -> 0f
                        offset < previous -> BACKWARD_PENALTY
                        else -> CHANGE_PENALTY
                    }
                    if (value > best) { best = value; bestK = k }
                }
                total[i][j] = best + score
                from[i][j] = bestK
            }
        }
        val path = IntArray(n)
        path[n - 1] = total[n - 1].indices.maxBy { total[n - 1][it] }
        for (i in n - 1 downTo 1) path[i - 1] = from[i][path[i]]
        val chosen = (0 until n).map { candidates[it][path[it]] }
        if (chosen.map { it.second }.average() < MIN_AVERAGE_SCORE) return null

        // Runs of pieces with the same offset become the parts; each part reaches
        // to where the next one starts (the first from the very beginning).
        data class Run(val firstPiece: Int, val offsets: MutableList<Int>)
        val runs = mutableListOf<Run>()
        chosen.forEachIndexed { i, (offset, _) ->
            val last = runs.lastOrNull()
            if (last != null && abs(last.offsets.sorted()[last.offsets.size / 2] - offset) <= SAME_OFFSET) last.offsets += offset
            else runs += Run(i, mutableListOf(offset))
        }
        // A run of only one or two pieces (a quiet start, a sound effect) is not a
        // real cut in the video: it takes the offset of the longer run next to it.
        var merged = true
        while (merged && runs.size > 1) {
            merged = false
            val shortest = runs.indices.minBy { runs[it].offsets.size }
            if (runs[shortest].offsets.size >= 3) break
            val neighbour = listOfNotNull(
                (shortest - 1).takeIf { it >= 0 },
                (shortest + 1).takeIf { it < runs.size },
            ).maxBy { runs[it].offsets.size }
            val keep = runs[neighbour]
            val gone = runs[shortest]
            val median = keep.offsets.sorted()[keep.offsets.size / 2]
            val joined = Run(minOf(keep.firstPiece, gone.firstPiece), (keep.offsets + List(gone.offsets.size) { median }).toMutableList())
            runs[minOf(shortest, neighbour)] = joined
            runs.removeAt(maxOf(shortest, neighbour))
            merged = true
        }
        // Neighbours that ended up with the same offset are one part.
        var i = 1
        while (i < runs.size) {
            val a = runs[i - 1].offsets.sorted()[runs[i - 1].offsets.size / 2]
            val b = runs[i].offsets.sorted()[runs[i].offsets.size / 2]
            if (abs(a - b) <= SAME_OFFSET) {
                runs[i - 1] = Run(runs[i - 1].firstPiece, (runs[i - 1].offsets + runs[i].offsets).toMutableList())
                runs.removeAt(i)
            } else i++
        }
        val songEndMs = song.size * 1000L / SAMPLE_RATE
        return runs.mapIndexed { index, run ->
            val offsetMs = run.offsets.sorted()[run.offsets.size / 2] * FRAME_MS
            val startMs = if (index == 0) 0L else starts[run.firstPiece] * FRAME_MS
            val endMs = runs.getOrNull(index + 1)?.let { starts[it.firstPiece] * FRAME_MS } ?: songEndMs
            // The very start of the song cannot be before the video starts.
            val fromMs = maxOf(startMs, -offsetMs)
            CounterpartSegment(fromMs, fromMs + offsetMs, (endMs - fromMs).coerceAtLeast(0L))
        }.filter { it.durationMs > 0L }
    }

    /** How well one song piece matches at each video position. */
    private fun matchScores(s: Array<FloatArray>, start: Int, v: Array<FloatArray>): FloatArray {
        val positions = v.size - WINDOW
        if (positions <= 0) return FloatArray(0)
        val scores = FloatArray(positions)
        for (p in 0 until positions) {
            var sum = 0f
            for (k in 0 until WINDOW) {
                val a = s[start + k]
                val b = v[p + k]
                for (band in 0 until BANDS) sum += a[band] * b[band]
            }
            scores[p] = sum / WINDOW
        }
        return scores
    }

    private fun bestOffsets(scores: FloatArray, start: Int): List<Pair<Int, Float>> {
        // Local peaks, at least 1 s apart.
        val picked = mutableListOf<Pair<Int, Float>>()
        val order = scores.indices.sortedByDescending { scores[it] }
        for (p in order) {
            if (picked.size >= CANDIDATES) break
            if (picked.any { abs(it.first - (p - start)) < 20 }) continue
            picked += (p - start) to scores[p]
        }
        return picked
    }

    /** Per 50 ms: log energy in 16 bands (100 Hz - 3.8 kHz), normalised (loudness does not count). */
    fun features(pcm: ShortArray): Array<FloatArray> {
        val frames = ((pcm.size - FFT_SIZE) / HOP).coerceAtLeast(0)
        val window = FloatArray(FFT_SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1))).toFloat() }
        val edges = IntArray(BANDS + 1) { band ->
            val hz = 100.0 * (3800.0 / 100.0).pow(band.toDouble() / BANDS)
            (hz * FFT_SIZE / SAMPLE_RATE).toInt().coerceIn(1, FFT_SIZE / 2)
        }
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        return Array(frames) { frame ->
            val offset = frame * HOP
            for (i in 0 until FFT_SIZE) {
                re[i] = pcm[offset + i] / 32768f * window[i]
                im[i] = 0f
            }
            fft(re, im)
            val bands = FloatArray(BANDS)
            for (band in 0 until BANDS) {
                var energy = 0.0
                val to = maxOf(edges[band + 1], edges[band] + 1)
                for (bin in edges[band] until to) energy += (re[bin] * re[bin] + im[bin] * im[bin]).toDouble()
                bands[band] = ln(energy + 1e-7).toFloat()
            }
            val mean = bands.average().toFloat()
            var norm = 0f
            for (band in 0 until BANDS) {
                bands[band] -= mean
                norm += bands[band] * bands[band]
            }
            norm = sqrt(norm) + 1e-6f
            for (band in 0 until BANDS) bands[band] /= norm
            bands
        }
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2 * PI / length
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until length / 2) {
                    val a = i + k
                    val b = a + length / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += length
            }
            length = length shl 1
        }
    }
}

/**
 * Decodes YouTube's WebM / Opus audio (itag 249-251) to mono PCM at
 * [AudioAlignment.SAMPLE_RATE]: a small WebM reader for the audio blocks,
 * and the Concentus Opus decoder (pure Java, the same on desktop and Android).
 */
internal object WebmOpusDecoder {
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_BLOCK_GROUP = 0xA0L
    private const val ID_SIMPLE_BLOCK = 0xA3L
    private const val ID_BLOCK = 0xA1L

    fun decode(webm: ByteArray): ShortArray {
        val decoder = OpusDecoder(AudioAlignment.SAMPLE_RATE, 1)
        val frame = ShortArray(AudioAlignment.SAMPLE_RATE * 120 / 1000)
        val out = ShortArrayBuilder(webm.size * 2)
        forEachBlock(webm) { start, length ->
            val samples = runCatching { decoder.decode(webm, start, length, frame, 0, frame.size, false) }.getOrDefault(0)
            if (samples > 0) out.add(frame, samples)
        }
        return out.toArray()
    }

    /** Calls [onFrame] with the position and length of each audio packet. */
    private fun forEachBlock(data: ByteArray, onFrame: (Int, Int) -> Unit) {
        fun readId(at: Int): Pair<Long, Int>? {
            if (at >= data.size) return null
            val first = data[at].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length !in 1..4 || at + length > data.size) return null
            var value = 0L
            for (i in 0 until length) value = (value shl 8) or (data[at + i].toLong() and 0xFF)
            return value to length
        }
        fun readSize(at: Int): Pair<Long, Int>? {
            if (at >= data.size) return null
            val first = data[at].toInt() and 0xFF
            val length = Integer.numberOfLeadingZeros(first) - 23
            if (length !in 1..8 || at + length > data.size) return null
            var value = (first and (0xFF shr length)).toLong()
            var allOnes = value == (0xFF shr length).toLong()
            for (i in 1 until length) {
                val byte = data[at + i].toLong() and 0xFF
                if (byte != 0xFFL) allOnes = false
                value = (value shl 8) or byte
            }
            return (if (allOnes) -1L else value) to length
        }
        fun walk(from: Int, to: Int) {
            var at = from
            while (at < to) {
                val (id, idLength) = readId(at) ?: return
                val (size, sizeLength) = readSize(at + idLength) ?: return
                val body = at + idLength + sizeLength
                val end = if (size < 0) to else (body + size).coerceAtMost(to.toLong()).toInt()
                when (id) {
                    ID_SEGMENT, ID_CLUSTER, ID_BLOCK_GROUP -> {
                        walk(body, end)
                        if (size < 0) return
                    }
                    ID_SIMPLE_BLOCK, ID_BLOCK -> {
                        val (_, trackLength) = readSize(body) ?: return
                        val header = body + trackLength + 3
                        val flags = data.getOrNull(header - 1)?.toInt() ?: 0
                        // YouTube's audio has one packet per block (no lacing).
                        if (flags and 0x06 == 0 && end > header) onFrame(header, end - header)
                    }
                }
                at = end
            }
        }
        walk(0, data.size)
    }

    private class ShortArrayBuilder(initial: Int) {
        private var buffer = ShortArray(initial.coerceAtLeast(1024))
        private var size = 0
        fun add(source: ShortArray, count: Int) {
            if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
            System.arraycopy(source, 0, buffer, size, count)
            size += count
        }
        fun toArray(): ShortArray = buffer.copyOf(size)
    }
}
