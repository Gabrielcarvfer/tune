package com.tune.music.data

fun song(
    id: Long = 1,
    title: String = "Title",
    artist: String = "Artist",
    album: String = "Album",
    albumArtist: String = artist,
    track: Int = 1,
    disc: Int = 1,
    path: String = "/storage/emulated/0/Music/in/$title.mp3",
) = Song(
    id = id, title = title, artist = artist, album = album, albumArtist = albumArtist,
    albumId = 1, genre = "Pop", year = 2020, track = track, disc = disc,
    durationMs = 180_000, path = path, dateAdded = 0,
)
