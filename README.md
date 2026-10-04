# Tune

A music player for Android in the style of the Windows Phone 8 music app: Kotlin,
Jetpack Compose, Media3, and a little C++ (Chromaprint and TagLib via JNI).

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
- **File organizing**: moves files to
  `Music/<album artist>/<album>/<NN>-<title>.<ext>` (multi-disc:
  `<disc>-<NN>-<title>`, no track number: just `<title>`). This happens
  automatically after saving info (settings switch), or on demand for a song,
  album, artist or the whole collection. Folders left empty are removed.
- **Music folder**: settings → "music folder" limits the collection to one
  folder, and organized files stay inside it. Android only lets apps move audio
  within `Music/`, `Download/` and similar folders. For a folder elsewhere (for
  example a Resilio Sync or Syncthing folder), tap "allow all files access";
  files are then renamed in place and keep their MediaStore ids. Without that
  access, files in such a folder are never moved.
- **Your own name and icon**: put Android resources in a `branding/res/`
  folder at the repository root (it's git-ignored). When it exists, its
  resources override the app's in your builds, for example:
  - `values/strings.xml` with `app_name` to rename the app.
  - `mipmap-anydpi-v26/ic_launcher.xml`, its layers and `launcher_bg` colours
    to change the launcher icon.

  This way you can dress the app up privately without that artwork ever being
  part of the source.

## Setup

1. Open the folder in Android Studio (or build with `./gradlew assembleDebug`).
   It needs the Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1. The first
   build downloads Chromaprint 1.5.1, TagLib 2.0.2 and utfcpp through CMake `FetchContent`
   (`app/src/main/cpp/CMakeLists.txt`).
2. Get a free AcoustID API key at <https://acoustid.org/new-application> and
   enter it in **settings → finding info online**.

## Continuous integration

`.github/workflows/android.yml` runs the unit tests and the instrumented tests
on an Android 15 emulator. When both pass, it builds the debug APK and
uploads it as the `tune-apk` artifact.

## Tests

- **Unit tests** (JVM, fast): `./gradlew testDebugUnitTest`. These cover tag
  helpers, file-organizer paths, AcoustID response parsing and Picard-style
  release ranking.
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
    the system consent dialog, and the identify flow with release and cover
    picking.

  Run them on a test device or emulator. They create and delete files under
  `Music/TuneTest*` and `Music/Tune Test*`.

## Notes

- Editing, moving and deleting files on Android 11+ goes through the system
  consent dialog, which asks once per batch.
- The app is minSdk 26.
- Licences of bundled or linked third-party code:
  - Selawik font: SIL Open Font License 1.1 (`third_party/Selawik-LICENSE.txt`).
  - Chromaprint: LGPL 2.1.
  - TagLib: LGPL 2.1 / MPL 1.1.

  Both libraries are linked statically into `libtune_native.so`; keep that in
  mind if you distribute builds.
- Windows Phone and Zune are trademarks of Microsoft. This project is not
  affiliated with Microsoft and ships none of its logos.
