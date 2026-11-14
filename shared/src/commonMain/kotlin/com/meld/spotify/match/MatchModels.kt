package com.meld.spotify.match

/** The track to find. [explicit] is null when the source does not know, which disables the parity check. */
data class MatchQuery(
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
    val isrc: String? = null,
)

enum class CandidateKind { SONG, VIDEO }

data class MatchCandidate(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    val explicit: Boolean? = null,
    val kind: CandidateKind = CandidateKind.SONG,
)

/** HIGH: play it. LOW: usable, but flag it to the user. NONE: not a match. */
enum class MatchConfidence { HIGH, LOW, NONE }

enum class RejectReason { TITLE, ARTIST, DURATION, SCORE }

/**
 * Score breakdown. [title], [artist], [duration] and [album] are 0..1 similarities; a null one was not
 * comparable and took no part in [total]. [penalty] is what one-sided version markers, video kind and
 * explicit mismatch subtracted.
 */
data class MatchScore(
    val total: Double,
    val confidence: MatchConfidence,
    val title: Double,
    val artist: Double?,
    val duration: Double?,
    val album: Double?,
    val oneSidedMarkers: Set<VersionMarker>,
    val penalty: Double,
    val rejectReason: RejectReason?,
)

data class ScoredCandidate(
    val candidate: MatchCandidate,
    val score: MatchScore,
)
