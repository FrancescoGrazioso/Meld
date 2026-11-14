package com.meld.spotify.match

import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isVideoSong

fun SongItem.toMatchCandidate(): MatchCandidate =
    MatchCandidate(
        id = id,
        title = title,
        artists = artists.map { it.name },
        album = album?.name,
        durationMs = duration?.times(1000),
        // YouTube badges only explicit songs, so an unbadged song is clean; for videos the badge is unreliable.
        explicit = artworkExplicitness ?: if (isVideoSong) null else false,
        kind = if (isVideoSong) CandidateKind.VIDEO else CandidateKind.SONG,
    )
