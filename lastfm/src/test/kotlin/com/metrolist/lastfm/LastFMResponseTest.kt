package com.metrolist.lastfm

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LastFMResponseTest {

    @Test
    fun `accepted scrobble response is successful`() {
        LastFM.validateLastFmResponse(
            method = "track.scrobble",
            responseText = """
                {
                  "scrobbles": {
                    "scrobble": {
                      "track": { "#text": "Test Track", "corrected": "0" },
                      "artist": { "#text": "Test Artist", "corrected": "0" },
                      "timestamp": "1287140447",
                      "ignoredMessage": { "#text": "", "code": "0" }
                    },
                    "@attr": { "accepted": "1", "ignored": "0" }
                  }
                }
            """.trimIndent(),
        )
    }

    @Test
    fun `ignored scrobble response fails with ignored code`() {
        try {
            LastFM.validateLastFmResponse(
                method = "track.scrobble",
                responseText = """
                    {
                      "scrobbles": {
                        "scrobble": {
                          "track": { "#text": "Test Track", "corrected": "0" },
                          "artist": { "#text": "Unknown Artist", "corrected": "0" },
                          "timestamp": "1288728940",
                          "ignoredMessage": {
                            "#text": "Artist name failed filter: Unknown Artist",
                            "code": "1"
                          }
                        },
                        "@attr": { "accepted": "0", "ignored": "1" }
                      }
                    }
                """.trimIndent(),
            )
            fail("Expected LastFmIgnoredException")
        } catch (e: LastFM.LastFmIgnoredException) {
            assertEquals("track.scrobble", e.method)
            assertEquals(1, e.ignoredCode)
            assertEquals("Artist name failed filter: Unknown Artist", e.message)
        }
    }

    @Test
    fun `ignored now playing response fails with ignored code`() {
        try {
            LastFM.validateLastFmResponse(
                method = "track.updateNowPlaying",
                responseText = """
                    {
                      "nowplaying": {
                        "track": { "#text": "Test Track", "corrected": "0" },
                        "artist": { "#text": "Unknown Artist", "corrected": "0" },
                        "ignoredMessage": {
                          "#text": "Artist name failed filter: Unknown Artist",
                          "code": "1"
                        }
                      }
                    }
                """.trimIndent(),
            )
            fail("Expected LastFmIgnoredException")
        } catch (e: LastFM.LastFmIgnoredException) {
            assertEquals("track.updateNowPlaying", e.method)
            assertEquals(1, e.ignoredCode)
            assertEquals("Artist name failed filter: Unknown Artist", e.message)
        }
    }

    @Test
    fun `api error response fails with LastFmException`() {
        try {
            LastFM.validateLastFmResponse(
                method = "track.scrobble",
                responseText = """{"error": 9, "message": "Invalid session key"}""",
                statusCode = 403,
            )
            fail("Expected LastFmException")
        } catch (e: LastFM.LastFmException) {
            assertEquals(9, e.code)
            assertEquals("Invalid session key", e.message)
        }
    }
}
