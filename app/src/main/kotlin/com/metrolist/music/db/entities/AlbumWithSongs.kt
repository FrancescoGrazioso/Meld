/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Ignore
import androidx.room.Junction
import androidx.room.Relation

@Immutable
data class AlbumWithSongs(
    @Embedded
    val album: AlbumEntity,
    @Relation(
        entity = ArtistEntity::class,
        entityColumn = "id",
        parentColumn = "id",
        // Every column but cachedPageJson: Room builds one ArtistEntity per pairing here, and the
        // cached page would be copied into every one of them.
        projection = [
            "id",
            "name",
            "thumbnailUrl",
            "channelId",
            "lastUpdateTime",
            "bookmarkedAt",
            "isLocal",
            "isPodcastChannel",
        ],
        associateBy =
        Junction(
            value = AlbumArtistMap::class,
            parentColumn = "albumId",
            entityColumn = "artistId",
        ),
    )
    val artists: List<ArtistEntity>,
    @Relation(
        entity = SongAlbumMap::class,
        entityColumn = "albumId",
        parentColumn = "id",
    )
    val songEntries: List<AlbumSong>,
) {
    // Room's relation query does not preserve ORDER BY from a junction view.
    @Ignore
    val songs: List<Song> = songEntries.sortedBy { it.map.index }.map { it.song }
}

data class AlbumSong(
    @Embedded val map: SongAlbumMap,
    @Relation(
        entity = SongEntity::class,
        entityColumn = "id",
        parentColumn = "songId",
    )
    val song: Song,
)
