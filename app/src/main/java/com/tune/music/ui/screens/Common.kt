package com.tune.music.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tune.music.MainViewModel
import com.tune.music.Screen
import com.tune.music.data.Album
import com.tune.music.data.Artist
import com.tune.music.data.Song
import com.tune.music.data.UNKNOWN
import com.tune.music.data.formatDuration
import com.tune.music.ui.components.AlbumArt
import com.tune.music.ui.components.MText
import com.tune.music.ui.components.MTextEllipsis
import com.tune.music.ui.components.MenuItem
import com.tune.music.ui.components.Overlay
import com.tune.music.ui.components.Overlays
import com.tune.music.ui.components.metroClick
import com.tune.music.ui.theme.Metro
import com.tune.music.ui.theme.MetroType

/** Shared song/album actions behind context menus and app bars. */
class Actions(val vm: MainViewModel, val overlays: Overlays) {

    fun play(songs: List<Song>, start: Int = 0) {
        vm.player.play(songs, start)
        vm.navigate(Screen.NowPlaying)
    }

    fun shuffle(songs: List<Song>) {
        vm.player.play(songs, shuffle = true)
        vm.navigate(Screen.NowPlaying)
    }

    fun addToPlaylist(songs: List<Song>) {
        val lists = vm.playlists.playlists.value
        overlays.show(
            Overlay.Picker(
                "add to playlist",
                listOf(MenuItem("new playlist") { newPlaylist(songs) }) +
                    lists.map { p -> MenuItem(p.name) { vm.addToPlaylist(p.id, songs) } },
            ),
        )
    }

    fun newPlaylist(songs: List<Song> = emptyList()) =
        overlays.input("new playlist", "", "create") { vm.newPlaylist(it, songs) }

    fun delete(songs: List<Song>, what: String, onDone: () -> Unit = {}) =
        overlays.confirm(
            "delete $what?",
            if (songs.size == 1) "\"${songs[0].title}\" will be deleted from your phone."
            else "${songs.size} songs will be deleted from your phone.",
            "delete",
        ) { vm.deleteSongs(songs, onDone) }

    fun organize(songs: List<Song>) {
        val todo = vm.songsToOrganize(songs)
        if (vm.organizeRoot == null) return vm.toast("organizing isn't available for this music folder")
        val sample = todo.firstOrNull() ?: return vm.toast("files are already organized")
        overlays.confirm(
            "organize files?",
            (if (todo.size == 1) "1 file" else "${todo.size} files") +
                " will be moved to ${vm.organizeRoot}/<album artist>/<album>/<number>-<title>, e.g.\n\n${vm.organizeTarget(sample)}",
            "move",
        ) { vm.organize(todo) }
    }

    fun songMenu(song: Song, extra: List<MenuItem> = emptyList()) = overlays.show(
        Overlay.Menu(
            listOf(
                MenuItem("play") { play(listOf(song)) },
                MenuItem("play next") { vm.player.playNext(listOf(song)) ; vm.toast("playing next") },
                MenuItem("add to now playing") { vm.player.enqueue(listOf(song)); vm.toast("added to now playing") },
                MenuItem("add to playlist") { addToPlaylist(listOf(song)) },
                MenuItem("edit info") { vm.navigate(Screen.EditSong(song.id)) },
                MenuItem("find info online") { vm.navigate(Screen.Identify(listOf(song.id), null)) },
                MenuItem("go to album") { vm.navigate(Screen.AlbumPage(song.albumId)) },
                MenuItem("go to artist") { vm.navigate(Screen.ArtistPage(song.albumArtist)) },
            ) + extra + MenuItem("delete") { delete(listOf(song), "song") },
        ),
    )

    fun albumMenu(album: Album) = overlays.show(
        Overlay.Menu(
            listOf(
                MenuItem("play") { play(album.songs) },
                MenuItem("shuffle") { shuffle(album.songs) },
                MenuItem("play next") { vm.player.playNext(album.songs); vm.toast("playing next") },
                MenuItem("add to now playing") { vm.player.enqueue(album.songs); vm.toast("added to now playing") },
                MenuItem("add to playlist") { addToPlaylist(album.songs) },
                MenuItem("edit album info") { vm.navigate(Screen.EditAlbum(album.id)) },
                MenuItem("find album info online") { vm.navigate(Screen.Identify(album.songs.map { it.id }, album.id)) },
                MenuItem("merge with other albums") { vm.navigate(Screen.MergeAlbums(album.id)) },
                MenuItem("organize files") { organize(album.songs) },
                MenuItem("delete") { delete(album.songs, "album") },
            ),
        ),
    )

    fun artistMenu(artist: Artist) = overlays.show(
        Overlay.Menu(
            listOf(
                MenuItem("play") { play(artist.songs) },
                MenuItem("shuffle") { shuffle(artist.songs) },
                MenuItem("add to now playing") { vm.player.enqueue(artist.songs); vm.toast("added to now playing") },
                MenuItem("add to playlist") { addToPlaylist(artist.songs) },
                MenuItem("organize files") { organize(artist.songs) },
            ),
        ),
    )
}

fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

@Composable
fun SongRow(
    song: Song,
    modifier: Modifier = Modifier,
    number: String? = null,
    showAlbum: Boolean = true,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = Metro.colors
    Row(
        modifier
            .fillMaxWidth()
            .metroClick(onLongClick, onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            MText(number, MetroType.medium, color = if (current) c.accent else c.subtle, modifier = Modifier.width(44.dp))
        }
        Column(Modifier.weight(1f)) {
            MTextEllipsis(song.title, MetroType.large, color = if (current) c.accent else c.foreground)
            val sub = if (showAlbum) "${song.artist} • ${song.album}" else song.artist
            MTextEllipsis(sub.replace(UNKNOWN, "unknown"), MetroType.small, color = c.subtle)
        }
        MText(formatDuration(song.durationMs), MetroType.small, color = c.subtle, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun AlbumRow(
    album: Album,
    showArtist: Boolean = true,
    inset: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .metroClick(onLongClick, onClick)
            .padding(start = if (inset) 24.dp else 0.dp, end = if (inset) 24.dp else 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AlbumArt(album.id, album.songs.firstOrNull()?.uri, Modifier.size(72.dp))
        Column(Modifier.weight(1f)) {
            MTextEllipsis(album.title, MetroType.medium)
            MTextEllipsis(
                if (showArtist) album.artist else listOfNotNull(album.year.takeIf { it > 0 }?.toString(), songCount(album.songs.size)).joinToString(" • "),
                MetroType.small, color = Metro.colors.subtle,
            )
        }
    }
}

/** Square cover with the title and artist under it, for album grids. */
@Composable
fun AlbumTile(album: Album, showArtist: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Column(Modifier.testTag("albumtile:${album.title}").metroClick(onLongClick, onClick)) {
        AlbumArt(album.id, album.songs.firstOrNull()?.uri, Modifier.fillMaxWidth().aspectRatio(1f))
        MTextEllipsis(album.title, MetroType.normal, modifier = Modifier.padding(top = 4.dp))
        MTextEllipsis(
            if (showArtist) album.artist else listOfNotNull(album.year.takeIf { it > 0 }?.toString(), songCount(album.songs.size)).joinToString(" • "),
            MetroType.small, color = Metro.colors.subtle,
        )
    }
}

@Composable
fun ArtistRow(artist: Artist, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    MTextEllipsis(
        artist.name.lowercase(), MetroType.extraLarge,
        modifier = Modifier
            .fillMaxWidth()
            .metroClick(onLongClick, onClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

/** Big lowercase link used on the hub and empty states. */
@Composable
fun BigLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    MText(
        text.lowercase(), MetroType.extraLarge,
        modifier = modifier
            .fillMaxWidth()
            .metroClick(onClick = onClick)
            .padding(vertical = 4.dp),
    )
}

@Composable
fun EmptyNote(text: String, inset: Boolean = true) {
    // Inside a panorama section the section already provides the left inset.
    MText(text, MetroType.medium, color = Metro.colors.subtle, maxLines = 4,
        modifier = if (inset) Modifier.padding(24.dp) else Modifier.padding(vertical = 24.dp))
}
