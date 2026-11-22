package com.meld.spotify

import kotlin.test.Test
import kotlin.test.assertEquals

class SpotifyRequestLogTest {
    @Test
    fun logsNameTheEndpointButNotTheUserOrThePlaylist() {
        assertEquals("/user/…/rootlist/changes", redactedPath("/user/31abcdefexample/rootlist/changes"))
        assertEquals("/playlist/v2/playlist/…/changes", redactedPath("/playlist/v2/playlist/37i9dQZF1DXcBWIGoYBM5M/changes"))
        assertEquals("/pathfinder/v2/query", redactedPath("/pathfinder/v2/query"))
    }
}
