package com.metrolist.innertube.models

data class TasteArtist(
    val name: String,
    val selectionValue: String,
    val impressionValue: String,
)

data class TasteProfile(
    val artists: Map<String, TasteArtist>,
)
