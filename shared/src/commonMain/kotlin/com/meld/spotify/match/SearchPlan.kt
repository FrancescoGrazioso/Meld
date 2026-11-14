package com.meld.spotify.match

enum class SearchScope { SONGS, VIDEOS }

data class SearchStep(
    val text: String,
    val scope: SearchScope,
    val isIsrc: Boolean = false,
)

private val ISRC = Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")
private val DASH_OR_SPACES = Regex("""\s+-\s+|\s+""")

/**
 * YouTube Music searches to try, in order, stopping at the first one that yields an accepted match.
 * Song results come first because they are the label-delivered audio; videos are the last resort.
 * Whether YouTube Music resolves an ISRC typed as search text is unverified, so that step can be
 * left out with [includeIsrc].
 */
fun buildSearchPlan(
    query: MatchQuery,
    includeIsrc: Boolean = true,
): List<SearchStep> {
    val artist = query.artists.firstOrNull().orEmpty()
    val exact = "$artist ${query.title}".replace(DASH_OR_SPACES, " ").trim()
    val bare = "$artist ${parseTitle(query.title).primary}".trim()
    val isrc =
        query.isrc
            ?.uppercase()
            ?.filter(Char::isLetterOrDigit)
            ?.takeIf { includeIsrc && ISRC.matches(it) }
    return listOfNotNull(
        SearchStep(exact, SearchScope.SONGS),
        isrc?.let { SearchStep(it, SearchScope.SONGS, isIsrc = true) },
        SearchStep(bare, SearchScope.SONGS).takeIf { normalizeText(bare) != normalizeText(exact) },
        SearchStep(exact, SearchScope.VIDEOS),
    ).filter { it.text.isNotEmpty() }
}
