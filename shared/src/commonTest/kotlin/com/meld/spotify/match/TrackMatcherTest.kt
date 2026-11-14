package com.meld.spotify.match

import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.MUSIC_VIDEO_TYPE_OMV
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackMatcherTest {
    private fun query(
        title: String,
        vararg artists: String,
        durationMs: Long? = 200_000,
        album: String? = null,
        explicit: Boolean? = null,
    ) = MatchQuery(title, artists.toList(), album, durationMs, explicit)

    private fun candidate(
        title: String,
        vararg artists: String,
        durationMs: Long? = 200_000,
        album: String? = null,
        explicit: Boolean? = null,
        kind: CandidateKind = CandidateKind.SONG,
        id: String = title,
    ) = MatchCandidate(id, title, artists.toList(), album, durationMs, explicit, kind)

    private fun confidence(
        query: MatchQuery,
        candidate: MatchCandidate,
    ) = TrackMatcher.score(query, candidate).confidence

    // Ported from the old SpotifyMapperMatchScoreTest.

    @Test
    fun identicalTrackIsAHighConfidenceMatch() {
        val score =
            TrackMatcher.score(
                query("Bohemian Rhapsody", "Queen", durationMs = 354_000),
                candidate("Bohemian Rhapsody", "Queen", durationMs = 354_000),
            )
        assertTrue(score.total >= 0.95, "$score")
        assertEquals(MatchConfidence.HIGH, score.confidence)
    }

    @Test
    fun featAndRemasterTagsDoNotTankTheScore() {
        val score =
            TrackMatcher.score(
                query("Lose Yourself (feat. Eminem) [Remastered 2023]", "Eminem"),
                candidate("Lose Yourself", "Eminem"),
            )
        assertTrue(score.total >= 0.90, "$score")
        assertEquals(MatchConfidence.HIGH, score.confidence)
    }

    @Test
    fun sameTitleByAnotherArtistIsRejected() {
        val score = TrackMatcher.score(query("Imagine", "John Lennon"), candidate("Imagine", "Ariana Grande"))
        assertEquals(RejectReason.ARTIST, score.rejectReason)
        assertEquals(MatchConfidence.NONE, score.confidence)
    }

    @Test
    fun unknownDurationIsNeutralButNeverHighConfidence() {
        val score = TrackMatcher.score(query("Flowers", "Miley Cyrus"), candidate("Flowers", "Miley Cyrus", durationMs = null))
        assertTrue(score.total >= 0.80, "$score")
        assertNull(score.duration)
        assertEquals(MatchConfidence.LOW, score.confidence)
    }

    @Test
    fun scoreDecaysSmoothlyWithDurationDifference() {
        val scores =
            (0..60).map {
                TrackMatcher.score(query("Song", "Artist"), candidate("Song", "Artist", durationMs = 200_000 + it * 1000L)).total
            }
        scores.zipWithNext { closer, further ->
            assertTrue(closer >= further, "not monotonic: $scores")
            assertTrue(closer - further < 0.02, "cliff: $scores")
        }
    }

    // Audit V-01: non-Latin scripts.

    @Test
    fun normalisationKeepsEveryScript() {
        assertEquals("夜に駆ける", normalizeText("夜に駆ける"))
        assertEquals("группа крови", normalizeText("Группа крови"))
        assertEquals("حبيبي يا نور العين", normalizeText("حَبيبي يا نور العين"))
        assertEquals("तुम ही हो", normalizeText("तुम ही हो"))
        assertEquals("beyonce", normalizeText("Beyoncé"))
        assertEquals("beyonce", normalizeText("Beyoncé"))
        assertEquals("rosalia motomami", normalizeText("ROSALÍA – MOTOMAMI"))
        assertEquals("abc 123", normalizeText("ＡＢＣ　１２３"))
        assertEquals("dont stop believin", normalizeText("Don’t Stop Believin'"))
        assertEquals("÷", normalizeText(" ÷ "))
        assertEquals("", normalizeText("  "))
    }

    @Test
    fun emptyStringsNeverMatch() {
        assertEquals(0.0, similarity("", ""))
        assertEquals(MatchConfidence.NONE, confidence(query("", "Artist"), candidate("", "Artist")))
        assertEquals(MatchConfidence.NONE, confidence(query("!!!", "Artist"), candidate("???", "Artist")))
    }

    @Test
    fun nonLatinTitlesAreComparedNotCollapsed() {
        val yoru = query("夜に駆ける", "YOASOBI", durationMs = 261_000)
        assertEquals(MatchConfidence.HIGH, confidence(yoru, candidate("夜に駆ける", "YOASOBI", durationMs = 261_000)))
        assertEquals(RejectReason.TITLE, TrackMatcher.score(yoru, candidate("群青", "YOASOBI", durationMs = 261_000)).rejectReason)
        assertEquals(
            RejectReason.ARTIST,
            TrackMatcher.score(query("夜に駆ける", "ヨアソビ", durationMs = 261_000), candidate("夜に駆ける", "米津玄師", durationMs = 261_000)).rejectReason,
        )

        val kino = query("Группа крови", "Кино")
        assertEquals(MatchConfidence.HIGH, confidence(kino, candidate("Группа крови", "КИНО")))
        assertEquals(MatchConfidence.NONE, confidence(kino, candidate("Звезда по имени Солнце", "Кино")))

        val hindi = query("तुम ही हो", "अरिजीत सिंह")
        assertEquals(MatchConfidence.HIGH, confidence(hindi, candidate("तुम ही हो", "अरिजीत सिंह")))
        assertEquals(MatchConfidence.NONE, confidence(hindi, candidate("चन्ना मेरेया", "अरिजीत सिंह")))

        val arabic = query("نور العين", "عمرو دياب")
        assertEquals(MatchConfidence.HIGH, confidence(arabic, candidate("نور العين", "عمرو دياب")))
        assertEquals(MatchConfidence.NONE, confidence(arabic, candidate("تملي معاك", "عمرو دياب")))
    }

    @Test
    fun accentsDoNotSeparateTheSameArtist() {
        assertEquals(MatchConfidence.HIGH, confidence(query("Halo", "Beyoncé"), candidate("Halo", "Beyonce")))
        assertEquals(
            MatchConfidence.HIGH,
            confidence(query("Con Altura", "ROSALÍA", "J Balvin"), candidate("Con Altura", "Rosalia", "J. Balvin")),
        )
    }

    // Audit V-03: one strong signal must not buy a match.

    @Test
    fun anotherSongByTheSameArtistIsRejected() {
        val score = TrackMatcher.score(query("Yellow", "Coldplay"), candidate("Clocks", "Coldplay"))
        assertEquals(RejectReason.TITLE, score.rejectReason)
        assertEquals(RejectReason.TITLE, TrackMatcher.score(query("Hello", "Artist"), candidate("Hello Goodbye", "Artist")).rejectReason)
        assertEquals(MatchConfidence.NONE, confidence(query("Symphony No. 5", "Orchestra"), candidate("Symphony No. 9", "Orchestra")))
        assertEquals(MatchConfidence.NONE, confidence(query("Hello", "Artist"), candidate("Hello Again", "Artist")))
        assertEquals(MatchConfidence.NONE, confidence(query("Stay With Me", "Artist"), candidate("Stay", "Artist")))
    }

    @Test
    fun decorationsThatAreNotVersionsDoNotHideTheSong() {
        val lights = query("Blinding Lights", "The Weeknd")
        assertEquals(1.0, TrackMatcher.score(lights, candidate("Blinding Lights (Official Audio)", "The Weeknd")).total)
        assertEquals(MatchConfidence.HIGH, confidence(query("夜に駆ける", "YOASOBI"), candidate("夜に駆ける (Yoru ni Kakeru)", "YOASOBI")))
        val frozen = query("Let It Go - From \"Frozen\"", "Idina Menzel")
        assertEquals(MatchConfidence.HIGH, confidence(frozen, candidate("Let It Go", "Idina Menzel")))
        val selfTitled = candidate("Bad Company - Bad Company (Official Audio)", "Bad Company", kind = CandidateKind.VIDEO)
        assertEquals(1.0, TrackMatcher.score(query("Bad Company", "Bad Company"), selfTitled).title)
    }

    @Test
    fun aMuchLongerOrShorterRecordingIsRejected() {
        val studio = query("Song", "Artist", durationMs = 180_000)
        assertEquals(MatchConfidence.HIGH, confidence(studio, candidate("Song", "Artist", durationMs = 183_000)))
        assertEquals(MatchConfidence.LOW, confidence(studio, candidate("Song", "Artist", durationMs = 200_000)))
        assertEquals(RejectReason.DURATION, TrackMatcher.score(studio, candidate("Song", "Artist", durationMs = 420_000)).rejectReason)
    }

    // Audit V-04: versions.

    @Test
    fun oneSidedLiveIsFlaggedAndLosesToTheStudioVersion() {
        val studio = query("As It Was", "Harry Styles")
        val live = candidate("As It Was (Live)", "Harry Styles")
        val score = TrackMatcher.score(studio, live)
        assertEquals(setOf(VersionMarker.LIVE), score.oneSidedMarkers)
        assertEquals(MatchConfidence.LOW, score.confidence)
        assertEquals(
            "studio",
            TrackMatcher.best(studio, listOf(live, candidate("As It Was", "Harry Styles", id = "studio")))?.candidate?.id,
        )
        assertEquals(MatchConfidence.LOW, confidence(query("As It Was - Live", "Harry Styles"), candidate("As It Was", "Harry Styles")))
    }

    @Test
    fun liveOnBothSidesIsNotPenalised() {
        val wembley = query("Bohemian Rhapsody - Live at Wembley Stadium, July 1986", "Queen")
        val score = TrackMatcher.score(wembley, candidate("Bohemian Rhapsody (Live at Wembley '86)", "Queen"))
        assertEquals(emptySet(), score.oneSidedMarkers)
        assertEquals(MatchConfidence.HIGH, score.confidence)
        assertEquals(MatchConfidence.HIGH, confidence(wembley, candidate("Bohemian Rhapsody (Live)", "Queen")))
        assertEquals(
            setOf(VersionMarker.LIVE),
            TrackMatcher.score(wembley, candidate("Bohemian Rhapsody (Live in Montreal)", "Queen")).oneSidedMarkers,
        )
    }

    @Test
    fun markerWordsInsideTheSongNameAreNotVersions() {
        assertEquals(MatchConfidence.HIGH, confidence(query("Live Forever", "Oasis"), candidate("Live Forever", "Oasis")))
        assertEquals(MatchConfidence.HIGH, confidence(query("Alive", "Pearl Jam"), candidate("Alive", "Pearl Jam")))
        assertEquals(
            setOf(VersionMarker.LIVE),
            TrackMatcher.score(query("Live Forever", "Oasis"), candidate("Live Forever (Live at Knebworth)", "Oasis")).oneSidedMarkers,
        )
    }

    @Test
    fun remasterIsTheSameRecordingButTheLabelledOneRanksFirst() {
        val remaster = query("Come Together - Remastered 2009", "The Beatles")
        val plain = candidate("Come Together", "The Beatles")
        assertEquals(MatchConfidence.HIGH, confidence(remaster, plain))
        assertEquals(
            "labelled",
            TrackMatcher
                .best(
                    remaster,
                    listOf(plain, candidate("Come Together (2009 Remaster)", "Beatles", id = "labelled")),
                )?.candidate
                ?.id,
        )
    }

    @Test
    fun aRemixIsNeverTheOriginal() {
        val remix = query("Roses - Imanbek Remix", "SAINt JHN", "Imanbek")
        val original = candidate("Roses", "SAINt JHN")
        assertNotEquals(MatchConfidence.HIGH, confidence(remix, original))
        assertNotEquals(MatchConfidence.HIGH, confidence(query("Roses", "SAINt JHN"), candidate("Roses (Imanbek Remix)", "SAINt JHN")))
        assertNotEquals(MatchConfidence.HIGH, confidence(remix, candidate("Roses (Different Remix)", "SAINt JHN")))
        val same = candidate("Roses (Imanbek Remix)", "SAINt JHN", id = "remix")
        assertEquals(MatchConfidence.HIGH, confidence(remix, same))
        assertEquals("remix", TrackMatcher.best(remix, listOf(original, same))?.candidate?.id)
        assertNull(TrackMatcher.best(remix, listOf(candidate("Roses", "SAINt JHN", durationMs = 160_000))))
    }

    @Test
    fun spotifyDashSuffixesAreReadAsVersions() {
        assertEquals(setOf(VersionMarker.EDIT), parseTitle("Blue Monday - Radio Edit").markers.keys)
        assertEquals("blue monday", parseTitle("Blue Monday - Radio Edit").primary)
        assertEquals(setOf(VersionMarker.REMASTER), parseTitle("Heroes - 2017 Remaster").markers.keys)
        assertEquals(setOf(VersionMarker.ACOUSTIC), parseTitle("Layla - Acoustic").markers.keys)
        assertEquals(setOf(VersionMarker.RERECORDED), parseTitle("Love Story (Taylor’s Version)").markers.keys)
        assertEquals("symphony no 5 allegro", parseTitle("Symphony No. 5 - Allegro").primary)
        val edit = query("Blue Monday - Radio Edit", "New Order")
        assertEquals(
            "edit",
            TrackMatcher
                .best(
                    edit,
                    listOf(candidate("Blue Monday", "New Order"), candidate("Blue Monday (Radio Edit)", "New Order", id = "edit")),
                )?.candidate
                ?.id,
        )
    }

    @Test
    fun karaokeCoverAndSpedUpUploadsAreRejected() {
        val song = query("Flowers", "Miley Cyrus")
        listOf(
            "Flowers (Karaoke Version)",
            "Flowers (Instrumental)",
            "Flowers - Sped Up",
            "Flowers (Cover)",
            "Flowers [Slowed + Reverb]",
        ).forEach {
            assertEquals(MatchConfidence.NONE, confidence(song, candidate(it, "Miley Cyrus")), it)
        }
    }

    @Test
    fun explicitParityBreaksTheTie() {
        val clean = candidate("WAP", "Cardi B", explicit = false, id = "clean")
        val explicit = candidate("WAP", "Cardi B", explicit = true, id = "explicit")
        assertEquals("explicit", TrackMatcher.best(query("WAP", "Cardi B", explicit = true), listOf(clean, explicit))?.candidate?.id)
        assertEquals("clean", TrackMatcher.best(query("WAP", "Cardi B", explicit = false), listOf(explicit, clean))?.candidate?.id)
        assertEquals(0.0, TrackMatcher.score(query("WAP", "Cardi B"), explicit).penalty)
    }

    @Test
    fun songsArePreferredOverVideos() {
        val track = query("Bohemian Rhapsody", "Queen")
        val video =
            candidate("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official", kind = CandidateKind.VIDEO, id = "video")
        val song = candidate("Bohemian Rhapsody", "Queen", id = "song")
        assertEquals("song", TrackMatcher.best(track, listOf(video, song))?.candidate?.id)
        assertEquals(MatchConfidence.HIGH, TrackMatcher.score(track, video).confidence)
        assertEquals(
            MatchConfidence.HIGH,
            confidence(
                query("Live Forever", "Oasis"),
                candidate("Oasis - Live Forever (Official Video)", "OasisVEVO", kind = CandidateKind.VIDEO),
            ),
        )
        assertEquals(
            RejectReason.ARTIST,
            TrackMatcher.score(track, candidate("Bohemian Rhapsody", "Some Channel", kind = CandidateKind.VIDEO)).rejectReason,
        )
    }

    @Test
    fun albumBreaksTheTie() {
        val track = query("Song", "Artist", album = "Original Album (Deluxe Edition)")
        val compilation = candidate("Song", "Artist", album = "Greatest Hits", id = "compilation")
        val original = candidate("Song", "Artist", album = "Original Album", id = "original")
        assertEquals("original", TrackMatcher.best(track, listOf(compilation, original))?.candidate?.id)
        assertEquals(MatchConfidence.HIGH, TrackMatcher.score(track, compilation).confidence)
    }

    // Audit V-04: artists.

    @Test
    fun artistsMatchInAnyOrderAndAnyGrouping() {
        val collab = query("Señorita", "Shawn Mendes", "Camila Cabello")
        assertEquals(1.0, TrackMatcher.score(collab, candidate("Señorita", "Camila Cabello", "Shawn Mendes")).artist)
        assertEquals(1.0, TrackMatcher.score(collab, candidate("Señorita", "Shawn Mendes & Camila Cabello")).artist)
        assertEquals(MatchConfidence.HIGH, confidence(collab, candidate("Señorita", "Shawn Mendes")))
        assertEquals(MatchConfidence.HIGH, confidence(collab, candidate("Señorita", "Camila Cabello")))
        assertEquals(
            MatchConfidence.HIGH,
            confidence(query("Señorita", "Shawn Mendes"), candidate("Señorita", "Shawn Mendes", "Camila Cabello")),
        )
        assertEquals(MatchConfidence.HIGH, confidence(query("The Boxer", "Simon & Garfunkel"), candidate("The Boxer", "Simon & Garfunkel")))
    }

    @Test
    fun featuredArtistsAreReadFromTitlesAndCredits() {
        val credited = query("Stay", "The Kid LAROI", "Justin Bieber")
        listOf("Stay (feat. Justin Bieber)", "Stay (with Justin Bieber)", "Stay ft. Justin Bieber", "Stay [Feat. Justin Bieber]").forEach {
            val score = TrackMatcher.score(credited, candidate(it, "The Kid LAROI"))
            assertEquals(1.0, score.title, it)
            assertEquals(1.0, score.artist, it)
        }
        val inTitle =
            TrackMatcher.score(
                query("Stay (feat. Justin Bieber)", "The Kid LAROI"),
                candidate("Stay", "The Kid LAROI feat. Justin Bieber"),
            )
        assertEquals(1.0, inTitle.title)
        assertEquals(1.0, inTitle.artist)
    }

    @Test
    fun rankKeepsSearchOrderOnTiesAndPutsRejectedLast() {
        val ranked =
            TrackMatcher.rank(
                query("Song", "Artist"),
                listOf(
                    candidate("Other", "Artist", id = "wrong"),
                    candidate("Song", "Artist", id = "first"),
                    candidate("Song", "Artist", id = "second"),
                ),
            )
        assertEquals(listOf("first", "second", "wrong"), ranked.map { it.candidate.id })
        assertNull(TrackMatcher.best(query("Song", "Artist"), listOf(candidate("Other", "Artist"))))
        assertNull(TrackMatcher.best(query("Song", "Artist"), emptyList()))
    }

    @Test
    fun searchPlanTriesSongsThenIsrcThenVideos() {
        val track = MatchQuery("Heroes - 2017 Remaster", listOf("David Bowie", "Brian Eno"), isrc = "us-jt1-17-00010")
        assertEquals(
            listOf(
                SearchStep("David Bowie Heroes 2017 Remaster", SearchScope.SONGS),
                SearchStep("USJT11700010", SearchScope.SONGS, isIsrc = true),
                SearchStep("David Bowie heroes", SearchScope.SONGS),
                SearchStep("David Bowie Heroes 2017 Remaster", SearchScope.VIDEOS),
            ),
            buildSearchPlan(track),
        )
        assertTrue(buildSearchPlan(track, includeIsrc = false).none { it.isIsrc })
        assertTrue(buildSearchPlan(track.copy(isrc = "not-an-isrc")).none { it.isIsrc })
        assertEquals(
            listOf(SearchStep("YOASOBI 夜に駆ける", SearchScope.SONGS), SearchStep("YOASOBI 夜に駆ける", SearchScope.VIDEOS)),
            buildSearchPlan(MatchQuery("夜に駆ける", listOf("YOASOBI"))),
        )
    }

    @Test
    fun songItemBecomesACandidate() {
        val song = SongItem("id", "Title", null, listOf(ArtistTiny(null, "A"), ArtistTiny(null, "B")), AlbumTiny(null, "Album"), 180)
        assertEquals(MatchCandidate("id", "Title", listOf("A", "B"), "Album", 180_000, false, CandidateKind.SONG), song.toMatchCandidate())
        val video = song.copy(album = null, duration = null, musicVideoType = MUSIC_VIDEO_TYPE_OMV)
        assertEquals(MatchCandidate("id", "Title", listOf("A", "B"), null, null, null, CandidateKind.VIDEO), video.toMatchCandidate())
        assertEquals(true, song.copy(isExplicit = true).toMatchCandidate().explicit)
    }
}
