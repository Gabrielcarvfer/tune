# Tune privacy policy

*Last updated: 6 October 2026*

Tune is a music player for the music files on your phone. It has no accounts,
ads, analytics or tracking. The developer does not collect, store or receive
any of your data.

## What stays on your phone

- **Your music files.** Tune reads them to play them and, when you edit song
  information, writes the changes into the files themselves. With "all files
  access" allowed, Tune also renames and moves files inside the music folder
  you chose.
- **Your settings and playlists** (theme, accent colour, music folder, your
  AcoustID key, playlists, recently played). They are stored only in the app's
  private storage on your phone and are deleted when you uninstall Tune.
- **Measured loudness** of each song, for volume normalization. Measured on
  the phone, kept only in the app's private storage.
- **Saved lookups**: the audio fingerprints Tune computed and the answers
  AcoustID gave, so songs aren't looked up again. Also only in the app's
  private storage; "forget scan" or uninstalling deletes them.

## What is sent over the internet, and only when you ask

Tune uses the internet only when you choose **find info online** for a song
or an album, **scan collection** or **search by name**:

- **AcoustID** (`api.acoustid.org`, run by the AcoustID project) receives, for
  each song: an audio fingerprint computed on your phone, the song's length,
  and your AcoustID API key. The fingerprint is a compact summary of the sound
  used to recognise the recording; the audio itself is never uploaded. The
  answer includes song and album information from MusicBrainz.
  **Scan collection** does the same for every song not looked up before.
- **MusicBrainz** (`musicbrainz.org`, run by the MetaBrainz Foundation)
  receives the album and artist names you search for, and is asked for the
  track list of the release you pick.
- **Cover Art Archive** (`coverartarchive.org`, which serves images from the
  Internet Archive) is asked for the cover images of the album you're looking
  at.

These services see your phone's IP address, as with any internet request.
Their own privacy policies apply:
[AcoustID](https://acoustid.org/privacy),
[MusicBrainz / MetaBrainz](https://metabrainz.org/privacy) and
[Internet Archive](https://archive.org/about/terms.php).

Tune sends nothing at any other time. With the **network kill switch**
(settings → network) on, it makes no web requests at all, even for these
features.

## Permissions

- **Music and audio**: to find and play your music.
- **Notifications**: for playback controls while music plays.
- **All files access** (optional, asked only if you choose a music folder
  outside Android's Music folder): to rename and move files inside that folder
  when you organize them.
- **Internet**: only for the lookups described above.

## Children

Tune is not directed at children and collects no personal information from
anyone.

## Changes

Changes to this policy are published in this file, in the app's
[source repository](https://github.com/Gabrielcarvfer/tune/blob/master/PRIVACY.md).

## Contact

Questions: open an issue at <https://github.com/Gabrielcarvfer/tune/issues>.
