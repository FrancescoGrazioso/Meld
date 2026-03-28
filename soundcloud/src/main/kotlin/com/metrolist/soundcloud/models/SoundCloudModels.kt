package com.metrolist.soundcloud.models

import kotlinx.serialization.Serializable

@Serializable
data class SoundCloudUser(
    val id: Long,
    val urn: String? = null,
    val username: String? = null,
    val full_name: String? = null,
    val avatar_url: String? = null
)

@Serializable
data class SoundCloudTrack(
    val id: Long,
    val title: String,
    val duration: Long,
    val user: SoundCloudUser? = null,
    val artwork_url: String? = null,
    val permalink_url: String? = null,
    val stream_url: String? = null,
    val media: SoundCloudMedia? = null
)

@Serializable
data class SoundCloudMedia(
    val transcodings: List<SoundCloudTranscoding> = emptyList()
)

@Serializable
data class SoundCloudTranscoding(
    val url: String,
    val preset: String,
    val format: SoundCloudFormat
)

@Serializable
data class SoundCloudFormat(
    val protocol: String,
    val mime_type: String
)

@Serializable
data class SoundCloudPlaylist(
    val id: Long,
    val title: String,
    val description: String? = null,
    val user: SoundCloudUser? = null,
    val tracks: List<SoundCloudTrack> = emptyList(),
    val artwork_url: String? = null,
    val track_count: Int = 0
)

@Serializable
data class SoundCloudPaging<T>(
    val collection: List<T> = emptyList(),
    val next_href: String? = null
)
