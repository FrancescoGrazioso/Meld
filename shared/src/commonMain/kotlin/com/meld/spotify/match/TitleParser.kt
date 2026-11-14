package com.meld.spotify.match

private const val SAME_RECORDING = 0.03
private const val OTHER_EDIT = 0.10
private const val OTHER_RECORDING = 0.25
private const val NOT_THE_SONG = 0.45

/**
 * A version word found in a title. [penalty] is subtracted when only one side carries the marker: small for
 * the same performance, enough to leave the HIGH band for another recording, enough to reject for audio
 * that is not the song at all.
 */
enum class VersionMarker(
    internal val penalty: Double,
    pattern: String,
) {
    REMASTER(SAME_RECORDING, "remaster\\S*"),
    MONO(SAME_RECORDING, "mono"),
    STEREO(SAME_RECORDING, "stereo"),
    EDIT(OTHER_EDIT, "radio edit|radio version|single version|single edit|album version|edit"),
    EXTENDED(OTHER_EDIT, "extended|long version"),
    LIVE(OTHER_RECORDING, "live|en vivo|en directo|ao vivo|unplugged|ライブ|라이브"),
    REMIX(OTHER_RECORDING, "remix|rmx|bootleg|mashup"),
    ACOUSTIC(OTHER_RECORDING, "acoustic|acustic[oa]|acoustique|akustik|stripped"),
    DEMO(OTHER_RECORDING, "demo"),
    RERECORDED(OTHER_RECORDING, "re recorded|rerecorded|taylors version"),
    INSTRUMENTAL(NOT_THE_SONG, "instrumental"),
    KARAOKE(NOT_THE_SONG, "karaoke|backing track|カラオケ"),
    COVER(NOT_THE_SONG, "cover|tribute|originally performed by|made famous by|in the style of"),
    SPED_UP(NOT_THE_SONG, "sped up|spedup|speed up|nightcore"),
    SLOWED(NOT_THE_SONG, "slowed|chopped|screwed"),
    EIGHT_D(NOT_THE_SONG, "8d"),
    ;

    // Whole words of normalised text, so "Alive" is not LIVE.
    internal val regex = Regex("(?:^| )(?:$pattern)(?= |$)")
}

internal fun markersIn(normalized: String): Set<VersionMarker> =
    VersionMarker.entries.filterTo(mutableSetOf()) {
        it.regex.containsMatchIn(normalized)
    }

/**
 * A title split into the part that names the song and its decorations. [extras] is unclassified bracket
 * text ("Part 2"); [markers] maps each version marker to what else its bracket or dash suffix said
 * ("wembley" for "Live at Wembley"), so two different remixes or concerts can be told apart.
 */
internal class ParsedTitle(
    val primary: String,
    val extras: String,
    val featured: List<String>,
    val markers: Map<VersionMarker, Set<String>>,
)

private val BRACKETS = Regex("""[(\[【]([^()\[\]【】]*)[)\]】]""")
private val DASH = Regex("""\s+[-–—]\s+""")
private val FEAT_DESCRIPTOR = Regex("""^(?:feat\.?|ft\.?|featuring|with)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val FEAT_INLINE = Regex("""\s(?:feat\.?|ft\.?|featuring)\s+(.+)$""", RegexOption.IGNORE_CASE)
private val ARTIST_SEPARATORS =
    Regex("""\s*[,&+/;、]\s*|\s+(?:x|and|feat\.?|ft\.?|featuring|with|vs\.?)\s+""", RegexOption.IGNORE_CASE)
private val NOISE =
    Regex(
        "(?:^| )(?:official|audio|video|videoclip|lyrics?|visuali[sz]er|hd|hq|4k|mv|bonus|deluxe|explicit|clean|from|" +
            "original mix|original version)(?= |$)",
    )
private val FILLER_WORDS = setOf("a", "at", "in", "on", "of", "from", "the", "version", "mix", "edit")

/**
 * [leadingArtists] are normalised names that may prefix a video title ("Oasis - Live Forever"); such a
 * prefix is credited as an artist instead of being read as the title.
 */
internal fun parseTitle(
    raw: String,
    leadingArtists: Collection<String> = emptyList(),
): ParsedTitle {
    val folded = foldWidth(raw)
    val primaryParts = mutableListOf<String>()
    val extras = mutableListOf<String>()
    val featured = mutableListOf<String>()
    val markers = mutableMapOf<VersionMarker, Set<String>>()

    fun classify(
        text: String,
        isBracket: Boolean,
    ) {
        val normalized = normalizeText(text)
        val found = markersIn(normalized)
        val feat = FEAT_DESCRIPTOR.find(text.trim())
        when {
            feat != null -> {
                featured += splitArtists(feat.groupValues[1])
            }

            found.isNotEmpty() -> {
                val rest = found.fold(NOISE.replace(normalized, " ")) { acc, marker -> marker.regex.replace(acc, " ") }
                val detail = rest.split(' ').filterTo(mutableSetOf()) { it.isNotEmpty() && it !in FILLER_WORDS }
                found.forEach { markers[it] = markers[it].orEmpty() + detail }
            }

            NOISE.containsMatchIn(normalized) -> {}

            isBracket -> {
                extras += normalized
            }

            // "Symphony No. 5 - Allegro": a dash suffix without a version word is part of the title.
            else -> {
                primaryParts += text
            }
        }
    }

    var segments = BRACKETS.replace(folded, " ").split(DASH)
    val prefix = splitArtists(segments.first())
    if (segments.size > 1 && prefix.isNotEmpty() && leadingArtists.containsAll(prefix)) {
        featured += prefix
        segments = segments.drop(1)
    }
    val inlineFeat = FEAT_INLINE.find(segments.first())
    primaryParts += inlineFeat?.let { segments.first().substring(0, it.range.first) } ?: segments.first()
    inlineFeat?.let { featured += splitArtists(it.groupValues[1]) }
    BRACKETS.findAll(folded).forEach { classify(it.groupValues[1], isBracket = true) }
    segments.drop(1).forEach { classify(it, isBracket = false) }

    return ParsedTitle(
        primary = normalizeText(primaryParts.joinToString(" ")),
        extras = extras.filter(String::isNotEmpty).joinToString(" "),
        featured = featured,
        markers = markers,
    )
}

/** Normalised artist names from one credit, which may itself be "A & B", "A feat. B" or "A, B". */
internal fun splitArtists(credit: String): List<String> =
    ARTIST_SEPARATORS
        .split(foldWidth(credit))
        .map { name ->
            val normalized = normalizeText(name)
            // "QueenVEVO", "Queen - Topic" and "Queen Official" are YouTube channel names for the artist.
            normalized
                .removePrefix("the ")
                .removeSuffix(" topic")
                .removeSuffix(" official")
                .removeSuffix("vevo")
                .trim()
                .ifEmpty { normalized }
        }.filter(String::isNotEmpty)
