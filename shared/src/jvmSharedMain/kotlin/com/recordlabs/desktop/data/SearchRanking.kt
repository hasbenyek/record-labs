package com.recordlabs.desktop.data

import java.text.Normalizer

private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/** Lower-case words of [text] without accents or punctuation ("10:36" -> "10 36"). */
internal fun searchWords(text: String): List<String> =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .lowercase()
        .split(NON_WORD)
        .filter(String::isNotEmpty)

/**
 * How closely a song matches what was typed: 3 = the title itself (or the
 * title with its artist, either order), 2 = every typed word is in the title
 * and artist, 1 = the title starts with the query, 0 = anything else.
 */
internal fun songMatchScore(query: String, track: Track): Int {
    val typed = searchWords(query)
    if (typed.isEmpty()) return 0
    val title = searchWords(track.title)
    val artist = searchWords(track.artist)
    val typedJoined = typed.joinToString(" ")
    val titleJoined = title.joinToString(" ")
    return when {
        titleJoined == typedJoined -> 3
        typedJoined == (title + artist).joinToString(" ") || typedJoined == (artist + title).joinToString(" ") -> 3
        title.isNotEmpty() && (title + artist).containsAll(typed) && typed.containsAll(title) -> 3
        (title + artist).containsAll(typed) -> 2
        titleJoined.startsWith(typedJoined) -> 1
        else -> 0
    }
}

/**
 * Songs ordered so the ones matching the query come first; otherwise YouTube's
 * order is kept. The same song listed twice (same title and artist under two
 * ids) shows once.
 */
fun rankSongsForQuery(query: String, tracks: List<Track>): List<Track> =
    tracks.distinctBy { it.id }
        .distinctBy { searchWords(it.title) to searchWords(it.artist) }
        .withIndex()
        .sortedWith(compareByDescending<IndexedValue<Track>> { songMatchScore(query, it.value) }.thenBy { it.index })
        .map { it.value }

/** True when [name] is exactly what was typed (ignoring case, accents and punctuation). */
fun searchNameMatches(query: String, name: String): Boolean {
    val typed = searchWords(query)
    return typed.isNotEmpty() && typed == searchWords(name)
}
