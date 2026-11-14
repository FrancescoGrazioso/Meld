package com.meld.spotify.match

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TITLE_WEIGHT = 0.45
private const val ARTIST_WEIGHT = 0.30
private const val DURATION_WEIGHT = 0.20
private const val ALBUM_WEIGHT = 0.05

// A weighted sum alone lets one strong signal buy a match (same artist, other song), so each signal also has a floor.
private const val MIN_TITLE = 0.65
private const val MIN_TITLE_HIGH = 0.80
private const val MIN_ARTIST = 0.50
private const val DURATION_HIGH_LIMIT_MS = 15_000L
private const val DURATION_REJECT_LIMIT_MS = 45_000L

private const val ARTIST_NAME_MATCH = 0.85
private const val EXTRAS_MISMATCH = 0.10
private const val UNKNOWN_DURATION = 0.5
private const val DURATION_SCALE_MS = 12_000.0
private const val VIDEO_PENALTY = 0.05
private const val EXPLICIT_PENALTY = 0.05

/** Scores YouTube candidates against a Spotify track. Stateless, so safe to call from any thread. */
object TrackMatcher {
    /** Bump when scoring changes enough that stored automatic matches should be recomputed. */
    const val VERSION = 1

    /**
     * 0.80 is the acceptance mark of spotDL's matcher (80/100). With the weights above it takes a matching
     * title and artist plus a duration within about 20 s to get there. Between the two thresholds is where
     * the right song in another version lands (one-sided live or remix marker, drifting duration): playable,
     * but worth flagging. Below 0.65 at least one of title, artist or version is plainly wrong.
     */
    const val ACCEPT_THRESHOLD = 0.80
    const val LOW_CONFIDENCE_THRESHOLD = 0.65

    fun score(
        query: MatchQuery,
        candidate: MatchCandidate,
    ): MatchScore = PreparedQuery(query).score(candidate)

    /** Every candidate, best first; equal scores keep the search order. Rejected ones come last. */
    fun rank(
        query: MatchQuery,
        candidates: List<MatchCandidate>,
    ): List<ScoredCandidate> {
        val prepared = PreparedQuery(query)
        return candidates
            .map { ScoredCandidate(it, prepared.score(it)) }
            .sortedWith(compareBy<ScoredCandidate> { it.score.confidence }.thenByDescending { it.score.total })
    }

    /** The best acceptable candidate (HIGH or LOW confidence), or null when none is a match. */
    fun best(
        query: MatchQuery,
        candidates: List<MatchCandidate>,
    ): ScoredCandidate? = rank(query, candidates).firstOrNull()?.takeIf { it.score.confidence != MatchConfidence.NONE }
}

private class PreparedQuery(
    private val query: MatchQuery,
) {
    private val title = parseTitle(query.title)
    private val artists = (query.artists.flatMap(::splitArtists) + title.featured).distinct()
    private val album = query.album?.let { parseTitle(it).primary }.orEmpty()
    private val titleWords = markersIn(title.primary)

    fun score(candidate: MatchCandidate): MatchScore {
        val parsed = parseTitle(candidate.title, artists)
        // Video titles often carry the artist ("Queen | Bohemian Rhapsody"): credit it and compare the rest.
        val named = artists.filter { " ${parsed.primary} ".contains(" $it ") && !" ${title.primary} ".contains(" $it ") }
        val primary =
            named
                .fold(" ${parsed.primary} ") { text, name -> text.replace(" $name ", " ") }
                .trim()
                .ifEmpty { parsed.primary }
        val candidateArtists = (candidate.artists.flatMap(::splitArtists) + parsed.featured + named).distinct()

        val titleScore =
            max(
                similarity(join(title.primary, title.extras), join(primary, parsed.extras)),
                similarity(title.primary, primary) - if (title.extras == parsed.extras) 0.0 else EXTRAS_MISMATCH,
            )
        val artistScore = if (artists.isEmpty()) null else artistSimilarity(artists, candidateArtists)
        val deltaMs =
            query.durationMs?.takeIf { it > 0 }?.let { wanted ->
                candidate.durationMs?.takeIf { it > 0 }?.let { abs(wanted - it) }
            }
        val durationScore = deltaMs?.let { exp(-(it / DURATION_SCALE_MS) * (it / DURATION_SCALE_MS)) }
        val albumScore =
            candidate.album
                ?.let { parseTitle(it).primary }
                ?.takeIf { album.isNotEmpty() && it.isNotEmpty() }
                ?.let { similarity(album, it) }

        // A marker word inside the song name itself ("Live Forever") only counts when the other side lacks it.
        val candidateWords = markersIn(primary)
        val queryMarkers = title.markers.keys + (titleWords - candidateWords)
        val candidateMarkers = parsed.markers.keys + (candidateWords - titleWords)
        val oneSided =
            (queryMarkers - candidateMarkers) + (candidateMarkers - queryMarkers) +
                title.markers
                    .filter { (marker, detail) ->
                        val other = parsed.markers[marker].orEmpty()
                        detail.isNotEmpty() && other.isNotEmpty() && detail.none { it in other }
                    }.keys

        val signals =
            listOfNotNull(
                TITLE_WEIGHT to titleScore,
                artistScore?.let { ARTIST_WEIGHT to it },
                DURATION_WEIGHT to (durationScore ?: UNKNOWN_DURATION),
                albumScore?.let { ALBUM_WEIGHT to it },
            )
        val explicitMismatch = query.explicit != null && candidate.explicit != null && query.explicit != candidate.explicit
        val penalty =
            oneSided.sumOf { it.penalty } +
                (if (candidate.kind == CandidateKind.VIDEO) VIDEO_PENALTY else 0.0) +
                (if (explicitMismatch) EXPLICIT_PENALTY else 0.0)
        val total = (signals.sumOf { it.first * it.second } / signals.sumOf { it.first } - penalty).coerceIn(0.0, 1.0)

        val rejectReason =
            when {
                titleScore < MIN_TITLE -> RejectReason.TITLE
                artistScore != null && artistScore < MIN_ARTIST -> RejectReason.ARTIST
                deltaMs != null && deltaMs > DURATION_REJECT_LIMIT_MS -> RejectReason.DURATION
                total < TrackMatcher.LOW_CONFIDENCE_THRESHOLD -> RejectReason.SCORE
                else -> null
            }
        val verified = titleScore >= MIN_TITLE_HIGH && artistScore != null && deltaMs != null && deltaMs <= DURATION_HIGH_LIMIT_MS
        return MatchScore(
            total = total,
            confidence =
                when {
                    rejectReason != null -> MatchConfidence.NONE
                    verified && total >= TrackMatcher.ACCEPT_THRESHOLD -> MatchConfidence.HIGH
                    else -> MatchConfidence.LOW
                },
            title = titleScore,
            artist = artistScore,
            duration = durationScore,
            album = albumScore,
            oneSidedMarkers = oneSided,
            penalty = penalty,
            rejectReason = rejectReason,
        )
    }
}

private fun join(
    primary: String,
    extras: String,
): String = if (extras.isEmpty()) primary else "$primary $extras".trim()

// Order-insensitive. Either side may list only some of the artists, so the smaller list carries most of the weight.
private fun artistSimilarity(
    query: List<String>,
    candidate: List<String>,
): Double {
    if (candidate.isEmpty()) return 0.0
    val matched = query.count { name -> candidate.any { it == name || similarity(it, name) >= ARTIST_NAME_MATCH } }
    val score = 0.7 * matched / min(query.size, candidate.size) + 0.3 * matched / max(query.size, candidate.size)
    return score.coerceAtMost(1.0)
}
