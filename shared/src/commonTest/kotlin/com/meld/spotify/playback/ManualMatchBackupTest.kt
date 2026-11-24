package com.meld.spotify.playback

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ManualMatchBackupTest {
    private val youtube = FakeYouTube()
    private var setting = ""

    private fun backup(dao: FakeMatchDao) = ManualMatchBackup(dao, { setting }, { setting = it }, { 0 })

    private fun TestScope.resolver(dao: FakeMatchDao) =
        SpotifyTrackResolver(dao, youtube::search, youtube::songsById, backgroundScope, { 0 }, onManualChange = backup(dao)::changed)

    @Test
    fun aChoiceTravelsInTheSettingAndComesBackOnAnEmptyDatabase() =
        runTest {
            val dao = FakeMatchDao()
            val resolver = resolver(dao)
            // An automatic match is not worth carrying: it is found again.
            resolver.resolve(track(1))
            assertEquals("", setting)

            resolver.choose(track(7), ytSong("live7", "Song 7 (Live)"))
            assertTrue("\"spotifyId\":\"sp7\"" in setting && "\"youtubeId\":\"live7\"" in setting, setting)

            // A restore: the setting arrives, the database of the new install knows nothing.
            val restored = FakeMatchDao()
            backup(restored).restore()
            assertEquals("live7", restored.get("sp7")?.youtubeId)
            assertEquals(true, restored.get("sp7")?.isManualOverride)
            assertEquals("live7", (resolver(restored).resolve(track(7)) as SpotifyResolution.Matched).song.id)

            // Dropped choices leave the setting too, so they do not come back.
            resolver(restored).forgetChoice(track(7))
            assertEquals("", setting)
        }

    @Test
    fun whatTheDatabaseAlreadyHoldsWinsAndASpoiltSettingIsIgnored() =
        runTest {
            val dao = FakeMatchDao()
            resolver(dao).choose(track(7), ytSong("mine7", "Song 7"))
            setting = """[{"spotifyId":"sp7","youtubeId":"other7"},{"spotifyId":"sp8","youtubeId":"yt8x"}]"""
            backup(dao).restore()
            assertEquals("mine7", dao.get("sp7")?.youtubeId)
            assertEquals("yt8x", dao.get("sp8")?.youtubeId)

            setting = "not json"
            backup(dao).restore()
            assertEquals(2, dao.manualOverrides().size)
            assertTrue("mine7" in setting)
        }
}
