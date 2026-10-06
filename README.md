<p align="center">
  <img src="art/tune-logo.svg" width="128" alt="Tune logo">
</p>

<h1 align="center">Tune</h1>

<p align="center">A music player for Android in the style of the Windows Phone 8 music app.</p>

Built with Kotlin, Jetpack Compose and Media3, plus a little C++ (Chromaprint
and TagLib via JNI).

## Features

- **Metro UI**: the "music" panorama hub (collection / history / new), wrapping
  pivots (artists, albums, songs, playlists, genres), jump lists with letter
  tiles, the tilt effect, round app-bar buttons, context menus, the flying-dots
  progress bar, and the 20 WP8 accent colours. The theme can follow the system
  or be forced dark (a very dark grey) or light (white), and titles can be
  drawn in the accent colour. The Selawik font stands in for Segoe WP.
- **Playback**: background playback with lock-screen and notification controls
  (Media3 `MediaSessionService`), play/pause, previous/next, shuffle,
  repeat (off/all/one), seeking, a queue with play next, add to now playing,
  remove and jump, swiping the album art to skip, and "shuffle all".
- **Volume normalization**: songs play at a similar loudness, so the next one
  isn't suddenly louder, and your files are never changed. Each song's
  integrated loudness is measured once on the phone (ITU BS.1770 / EBU R128,
  like ReplayGain 2), as it comes up to play or all at once (settings →
  playback → measure all songs). When a song starts, the player turns loud
  songs down to -18 LUFS; quiet ones play as they are. It can be switched off.
- **Skipping quiet endings**: while measuring loudness, Tune also notes where
  each song's sound ends for good (30 dB below the song's own level, so a long
  fade-out plays almost to its end and only an inaudible or silent tail is
  cut). When playback gets there it moves straight to the next song (settings
  → playback → transitions, on by default). No audio processing is involved:
  the player just skips ahead.
- **Settings backup**: export every setting, playlists, history, measured
  loudness and saved scans to one JSON file, and import it in another install.
- **Library management**: playlists (create, rename, reorder, delete), deleting
  songs and albums, and search.
- **Metadata editing**: tags are written into the files themselves with
  TagLib (title, artist, album, album artist, genre, year, track, disc,
  cover art). An album editor changes every track at once and can set a new
  cover from the gallery.
- **Automatic tagging (like Picard)**: songs are fingerprinted with Chromaprint
  and looked up on AcoustID. You choose among the MusicBrainz releases found,
  each shown with its cover. For a whole album, songs are grouped by release and
  ranked by how many of them match. You then choose the cover from every
  Cover Art Archive image for that release, or keep the current one.
- **Search by name**: when the fingerprints point at the wrong edition (or
  there's no AcoustID key), search MusicBrainz releases by album and artist.
  Songs are paired with the release's tracks by title, length and position.
- **Merging albums**: pick several albums and edit them as one album (album,
  album artist, year, renumbered tracks), or find the release that holds the
  most of their songs.
- **Scan and consolidate**: settings → collection → **scan
  collection** fingerprints and looks up every song once and saves the
  answers, so later lookups don't ask again. **Consolidate albums** then finds
  songs that are on one release but split across albums (say, an anniversary
  edition whose tracks were each matched to their original soundtrack) and
  gathers them on the release that holds the most of them. Single-song
  lookups also prefer the release most of the collection is on.
- **Web services, only when you ask**: Tune uses three free services to find
  information online: AcoustID (identifies recordings from audio
  fingerprints), MusicBrainz (song and album information) and the Cover Art
  Archive (album covers). It contacts them only when you use finding info
  online, search by name or scan collection; otherwise it makes no web
  requests at all. The **network kill switch** (settings → network) blocks
  every web request while it's on. Requests are rate limited app-wide
  (AcoustID 3 a second, MusicBrainz and the Cover Art Archive 1 a second), and
  Tune identifies itself to MusicBrainz as it asks. Settings → about says the
  same in the app.
- **File organizing**: moves files to
  `Music/<album artist>/<album>/<NN>-<title>.<ext>` (multi-disc:
  `<disc>-<NN>-<title>`, no track number: just `<title>`), inside the music
  folder. It happens on demand for a song, album, artist or the whole
  collection, or after saving info (settings switch, off unless you chose a
  music folder). Folders left empty are removed.
- **Music folder**: settings → collection → "music folder" limits the collection to one
  folder, and organized files stay inside it. Android only lets apps move audio
  within `Music/`, `Download/` and similar folders. For a folder elsewhere (for
  example a Resilio Sync or Syncthing folder), tap "allow all files access";
  files are then renamed in place and keep their MediaStore ids. Without that
  access, files in such a folder are never moved.

## Using Tune

The numbers in the screenshots match the steps under them.

Settings are a pivot, like the collection: swipe between **playback**,
**collection** (music folder, organizing, identifying songs), **network**
(kill switch, AcoustID key), **appearance** and **about**, or tap a title.

### Choosing where your music is

By default every song on the phone is in the collection. To use only one
folder, such as a folder synced with Resilio Sync or Syncthing:

<p>
  <img src="docs/guide/folder-1.png" width="200" alt="Hub with settings marked">
  <img src="docs/guide/folder-2.png" width="200" alt="Settings, music folder section">
  <img src="docs/guide/folder-3.png" width="200" alt="Android folder picker">
  <img src="docs/guide/folder-4.png" width="200" alt="Allow access to the folder">
</p>

1. On the music hub, tap **settings**.
2. Swipe to (or tap) the **collection** page; under **music folder**, tap
   **choose folder**.
3. Open your music folder in Android's picker and tap **Use this folder**.
4. Tap **Allow**. Only songs in that folder and its subfolders are now in the
   collection, and organized files stay inside it.

**whole phone** goes back to every song on the phone. In that case organized
files go to Android's `Music` folder.

#### Folders outside Music (synced folders)

Android only lets apps move songs within `Music/`, `Download/` and similar
folders. For a folder anywhere else, Tune leaves files where they are unless
you give it all files access:

<p>
  <img src="docs/guide/folder-5.png" width="200" alt="Allow all files access button">
  <img src="docs/guide/folder-6.png" width="200" alt="Android's all files access switch">
</p>

5. Tap **allow all files access** (it shows only when the folder needs it).
6. Turn on **Allow access to manage all files**, then go back. Android
   restarts Tune when this changes. From then on, edited and organized files
   are renamed inside your folder, so your sync tool sees the changes.

### Editing song and album info

Changes are written into the music files themselves, so other players and
devices see them too. With **move files after editing info** on (settings →
collection → organizing files; on by default once you choose a music folder), saved files
are also moved to `<album artist>/<album>/<NN>-<title>.<ext>` inside the music
folder.

<p>
  <img src="docs/guide/edit-1.png" width="200" alt="Song menu with edit info">
  <img src="docs/guide/edit-2.png" width="200" alt="Song editor">
  <img src="docs/guide/edit-3.png" width="200" alt="Album menu with edit album info">
  <img src="docs/guide/edit-4.png" width="200" alt="Album editor">
</p>

1. Touch and hold a song (in any list) and tap **edit info**.
2. Change any field. The bottom of the page shows where the file will be moved.
3. Tap ✓ (save). Android may ask once to allow changes to the file.
4. For a whole album, open it and tap **⋯** on the app bar.
5. Tap **edit album info**.
6. Tap the cover to pick a new image from your phone.
7. Change the album, album artist, genre, year or track titles.
8. Tap ✓ (save) to update every song on the album.

### Finding info online (AcoustID)

Tune can recognise songs by their sound, like MusicBrainz Picard. This fixes
badly tagged files: titles, artists, album, year, track numbers and cover.

<p>
  <img src="docs/guide/acoustid-1.png" width="200" alt="AcoustID key in settings">
  <img src="docs/guide/acoustid-2.png" width="200" alt="Album menu with find album info online">
  <img src="docs/guide/acoustid-3.png" width="200" alt="Releases found">
  <img src="docs/guide/acoustid-4.png" width="200" alt="Release details with cover choice">
</p>

First, once only, add a free AcoustID key:

1. In settings → **network**, under **acoustid**, tap **get a key**. Sign in on
   acoustid.org and register an application (any name, such as "Tune"); it
   shows you an API key.
2. Paste the key into **acoustid api key**.
3. Tap **save key**.

Then, for an album (or a single song, from its menu: **find info online**):

4. Open the album and tap **⋯**.
5. Tap **find album info online**. Tune fingerprints each song and looks it up.
6. Pick the release you own. The ones that match the most songs come first.
7. Check the new titles ("was:" shows the old ones), then choose a cover from
   the Cover Art Archive, or **keep current**.
8. Tap ✓ (apply). The tags and cover are written into the files, which are
   then organized.

### Searching by name

When the fingerprints point at the wrong edition, or you have no AcoustID
key, search MusicBrainz for the release by name:

<p>
  <img src="docs/guide/search-1.png" width="200" alt="Search by name button under the releases">
  <img src="docs/guide/search-2.png" width="200" alt="Search form and results">
</p>

1. On the releases list, tap **search by name** (without an AcoustID key,
   **find info online** opens the search straight away).
2. The album and artist are filled in from your songs; correct them if needed.
3. Tap **search**.
4. Pick the release. Your songs are paired with its tracks by title, length
   and track number; then choose the cover and apply, as above.

### Merging albums

For albums that should be one, like a soundtrack and its expansions, or one
album split by inconsistent tags:

<p>
  <img src="docs/guide/merge-1.png" width="200" alt="Album menu with merge with other albums">
  <img src="docs/guide/merge-2.png" width="200" alt="Choosing albums to merge">
  <img src="docs/guide/merge-3.png" width="200" alt="Editing merged albums as one">
</p>

1. Touch and hold an album and tap **merge with other albums**.
2. Tap the other albums to merge (similar ones are listed first).
3. **edit as one** writes one album name, album artist and year to every
   song; **⋯ → number tracks in order** renumbers them.
4. Or **find online**: Tune identifies all the songs together and lists the
   releases holding the most of them first.
5. Set the album name.
6. Tap ✓ (save).

### Scanning and consolidating albums

**Scan collection** fingerprints and looks up every song once and saves the
answers on your phone, so later lookups don't ask again.
**Consolidate albums** then finds songs that are on one release but split
across albums (say, an anniversary edition whose tracks were each matched to
their original soundtrack) and gathers them on the release that holds the
most of them.

<p>
  <img src="docs/guide/consolidate-1.png" width="200" alt="Scan collection in settings">
  <img src="docs/guide/consolidate-2.png" width="200" alt="Consolidate albums after the scan">
  <img src="docs/guide/consolidate-3.png" width="200" alt="Releases that gather split albums">
  <img src="docs/guide/consolidate-4.png" width="200" alt="Reviewing and applying a release">
</p>

1. In settings → **collection**, under **identifying songs**, tap
   **scan collection**. It runs
   within the services' rate limits, so a big collection takes a while; you
   can stop and continue later, already scanned songs are kept.
2. Tap **consolidate albums**.
3. Each row is a release and the albums its songs are in now. Tap one.
4. Check the tracks, pick a cover, and tap ✓ (apply).

**forget scan** deletes the saved fingerprints and answers.

### Playing at an even volume

So that one song isn't suddenly much louder than the last, Tune turns loud
songs down to a common level while playing. Your files aren't changed.

<p>
  <img src="docs/guide/volume-1.png" width="200" alt="Volume settings on the playback page">
</p>

1. Settings → **playback**: **normalize volume** is on by default.
2. Songs are measured as they come up to play; **measure all songs** does the
   whole collection now, in the background (you can stop and continue later).

### Skipping quiet endings

Some songs end with a long fade or several seconds of near-silence. Tune can
move on to the next song as soon as the sound is gone, so you don't sit through
the quiet.

<p>
  <img src="docs/guide/transitions-1.png" width="200" alt="Skip quiet song endings on the playback page">
</p>

1. Settings → **playback** → **transitions**: **skip quiet song endings** is on
   by default.

The end of each song is found while its loudness is measured (above), so a song
is only cut once it has been measured: **measure all songs** gets every song
ready at once. How much is cut depends on the song: a fade-out still plays
almost to its end, and only the part that's practically inaudible (30 dB below
the song's own level) is skipped. Nothing is cut when that's under a second.
With **repeat one** the song starts again instead, and the last song in the
list plays out. The audio itself is never processed: the player just skips
ahead.

### Backing up and moving your settings

<p>
  <img src="docs/guide/backup-1.png" width="200" alt="Export and import on the about page">
</p>

1. Settings → **about** → **export settings** saves everything (settings,
   AcoustID key, music folder, playlists, history, measured loudness, saved
   scans) to one JSON file.
2. **import settings** on another install reads it back and applies it.
   Measured loudness and scans are tied to this phone's songs, so they only
   carry over on the same phone.

## Setup

1. Open the folder in Android Studio (or build with `./gradlew assembleDebug`).
   It needs the Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1. The first
   build downloads Chromaprint 1.5.1, TagLib 2.0.2 and utfcpp through CMake `FetchContent`
   (`app/src/main/cpp/CMakeLists.txt`).
2. Get a free AcoustID API key at <https://acoustid.org/new-application> and
   enter it in **settings → network** (see
   [Finding info online](#finding-info-online-acoustid)).

## Continuous integration

`.github/workflows/android.yml` runs the unit tests and the instrumented tests
on an Android 15 emulator. When both pass, it builds the debug APK and
uploads it as the `tune-debug.apk` artifact (a plain APK, not a zip).

### Running the CI locally

[act](https://github.com/nektos/act) replays the workflow on a Linux machine
(or WSL) with Docker or Podman and KVM:

```sh
docker build -t tune-act:24.04 -f tools/ci/act.Containerfile tools/ci
sudo chmod 666 /dev/kvm   # what the workflow's "Enable KVM" step does on GitHub
act push -j test --pull=false -P ubuntu-latest=tune-act:24.04   --container-options "--device /dev/kvm" --artifact-server-path /tmp/act-artifacts
act push -j apk --pull=false -P ubuntu-latest=tune-act:24.04 --artifact-server-path /tmp/act-artifacts
```

With Podman, use `localhost/tune-act:24.04`. Podman may also need
`--pids-limit -1 --ulimit nproc=65535:65535` in `--container-options`, because
Gradle and the emulator start many threads.

## Tests

- **Unit tests** (JVM, fast): `./gradlew testDebugUnitTest`. These cover tag
  helpers, file-organizer paths, AcoustID response parsing and Picard-style
  release ranking, MusicBrainz search and track matching, consolidation, the
  saved scans, the rate limiter and kill switch, the fingerprinting pipeline,
  the loudness meter (against the EBU reference tone), settings backup and
  the licence files.
- **Functional tests** (on a device or emulator):
  `./gradlew connectedDebugAndroidTest`. Each test generates its own tagged
  audio files (AAC encoded on the device, tagged with TagLib, published through
  MediaStore) and removes them afterwards. AcoustID and the Cover Art Archive
  are faked; fingerprints are real. Areas covered:
  - **Touch:** taps, long presses, swiping the panorama, pivots and album
    art, and the seek bar.
  - **Keyboard and keys:** typing, IME actions, Tab and D-pad focus, Enter,
    Escape and Back, and the media play/pause/next keys.
  - **Features:** playback, the queue, shuffle and repeat, playlists, tag
    editing, organizing (including empty-folder cleanup), deleting through
    the system consent dialog, the identify flow with release and cover
    picking, search by name, merging albums, scanning and consolidating,
    the network kill switch, volume normalization, going back to where you
    were, the settings pages, and exporting and importing settings.

  Run them on an emulator, not on a phone you use: every test starts by
  resetting Tune's settings and saved data, and they create and delete files
  under `Music/TuneTest*` and `Music/Tune Test*`. Gradle runs them on every
  attached device, so with a phone attached set
  `ANDROID_SERIAL=emulator-5554` (or your emulator's serial) first.
- **Guide screenshots**: `tools/guide/capture.sh` regenerates the pictures in
  `docs/guide/` on a connected emulator. The `GuideScreenshots` test drives
  the app through each flow with a made-up collection and records where to
  tap; `tools/guide/annotate.py` (Python with Pillow) then draws the numbered
  arrows. Normal test runs skip it.

## Notes

- Privacy: Tune collects nothing; see [PRIVACY.md](PRIVACY.md).
- Editing, moving and deleting files on Android 11+ goes through the system
  consent dialog, which asks once per batch.
- The app is minSdk 26.
- Debug builds, local or from CI, are all signed with the same key
  (`tools/signing/debug.keystore`), so a new build installs over the old one
  and keeps its settings, playlists, history and saved fingerprints. Store
  releases are signed with your own upload key instead; don't mix the two on
  one phone. Android's app backup also keeps the settings and playlists
  across reinstalls and new phones.
- Windows Phone and Zune are trademarks of Microsoft. This project is not
  affiliated with Microsoft and ships none of its logos.

## License

Tune is free software: you can redistribute it and/or modify it under the
terms of the [GNU General Public License, version 3](LICENSE).

It includes third-party code under these licences; the app lists them all,
with their full texts, under settings → about → open-source licences
(`app/src/main/assets/licenses/`):

- Chromaprint 1.5.1 (LGPL 2.1, with KISS FFT under BSD-3-Clause) and TagLib
  2.0.2 (LGPL 2.1): unmodified releases, linked statically into
  `libtune_native.so`. This repository has the complete source and build
  scripts, so you can rebuild the app with your own versions of them.
- UTF8-CPP 4.0.5: Boost Software License 1.0.
- Selawik font: SIL Open Font License 1.1.
- AndroidX, Jetpack Compose, Media3, Kotlin, kotlinx.coroutines, Coil, Guava:
  Apache License 2.0.
