# 🎵 Music Monster

An **ad-free, offline music player for Android**. Play the audio files on your device,
organize playlists, shape the sound, and make the player look your way.

**Android 7.0 or newer · No account required · No Internet permission**

### Your library and player

<p align="center">
  <img src="https://raw.githubusercontent.com/NomeSame/MusicMonster/master/docs/1-hauptansicht.png" alt="1. Hauptansicht — music library and player" width="300">
</p>

## ⬇️ Download & install

Download a published APK from **[MusicMonster-Release](https://github.com/NomeSame/MusicMonster-Release/releases/latest)**.
This repository contains the player source code. Source builds and development branches
can include changes that are not yet in the latest published APK.

1. Download the release `.apk` to your Android device.
2. Open it from your browser's downloads or your file manager.
3. If Android asks, allow **Install unknown apps** for that browser or file manager.
   The location and wording of this setting vary by device.
4. Tap **Install**, then open **Music Monster**.
5. Allow audio access to load your device library, or use the **Music** folder picker
   to select a specific folder. Allow notifications if you want notification controls.

<details>
<summary>Example: allowing installation from your browser or file manager</summary>

<p align="center">
  <img src="https://raw.githubusercontent.com/NomeSame/MusicMonster/master/docs/install-unknown-apps.png" alt="Android's Allow from this source setting" width="300">
</p>

</details>

Keep [Google Play Protect](https://support.google.com/android/answer/2812853?hl=en)
enabled. Review any warning before installing; a warning is not proof that an APK is safe.

## ✨ Features

- **Your music, locally:** load the device audio library or a chosen folder, including subfolders.
  Common formats include MP3, M4A, FLAC, OGG, and WAV; decoding support depends on your device.
- **Local video in the source build:** play MP4 files from your chosen folder and
  use Android picture-in-picture on supported devices.
- **Natural sorting and fast scrolling:** numbered titles sort naturally, with a draggable
  scrollbar and letter indicator for larger libraries.
- **Background playback:** play/pause, previous/next, seeking, shuffle, repeating playback,
  and notification/lock-screen controls. The player remembers the last track and position.
- **Playlists:** create playlists, add or remove songs, start from a selected song,
  and import/export playlists as JSON. Playlist exports contain references to songs, not audio files.
- **Equalizer and bass boost:** adjust the available bands and choose Metal, Rock, Classic,
  Flat, or Pop presets. Audio effects and band availability depend on the device.
- **Sleep timer:** 15-, 30-, and 60-minute presets, a custom hours/minutes/seconds duration,
  cancellation, and a gentle fade-out before playback pauses.
- **Make it yours:** accent colors, a custom image background, background dimming,
  player-card transparency, and an optional accent color extracted from your image.
- **Compact navigation:** pull down the song list to reveal the Playlists, Equalizer,
  and Sleep Timer panels, then swipe between them.

## 📷 More screenshots

Explore the playlist, equalizer, and personalization screens below.

### 2. Playlists

<p align="center">
  <img src="https://raw.githubusercontent.com/NomeSame/MusicMonster/master/docs/2-playlists.jpg" alt="2. Playlists — a playlist with songs" width="300">
</p>

### 3. Equalizer

<p align="center">
  <img src="https://raw.githubusercontent.com/NomeSame/MusicMonster/master/docs/3-equalizer.jpg" alt="3. Equalizer — bands, presets, and bass boost" width="300">
</p>

### 4. Personalisation

<p align="center">
  <img src="https://raw.githubusercontent.com/NomeSame/MusicMonster/master/docs/4-personalisierung.jpg" alt="4. Personalisierung — color and background settings" width="300">
</p>

## 🔒 Privacy & permissions

Music Monster has **no Internet permission** and includes no advertising or analytics SDKs.
It does not require an account or upload your music library.

- Audio/storage access lets the app find local music. Choosing a folder grants access
  to that folder separately through Android's system picker.
- Notification access enables playback controls outside the app.
- Background playback and audio-effect permissions support playback and the equalizer.

Files and preferences are handled on the device. If you choose a cloud-backed folder or
file provider, that provider manages its own network access independently of Music Monster.

## 🔄 Updates

Check [Releases](https://github.com/NomeSame/MusicMonster-Release/releases) for newer APKs.
Install the update over the existing app to retain its settings and playlists.
The APK must use the same application ID and signing certificate, and Android must accept
its version. An incompatible signing certificate prevents an in-place update.

Export your playlists before uninstalling: uninstalling removes the app's local settings.

## 🛠️ Build from source

The repository is named **MusicMonster**; the installed app is **Music Monster**
(`com.nomesame.musicmonster`). It uses Kotlin, Jetpack Compose, and AndroidX Media3.

1. Open the project in Android Studio with support for Android Gradle Plugin 8.13.2.
2. Install **Android SDK Platform 36** and configure its local SDK path.
3. Use **JDK 17 or newer**, compatible with the project's Gradle 8.13 wrapper.
   See the [Android Gradle Plugin compatibility requirements](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
4. Run the following from the project root in PowerShell:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

`master` is the public player branch. It builds independently, without private
download code or an engine. Optional compatible modules must be embedded in the same
APK; installing a separate app does not add actions to this build.

Library folder navigation, media presets, progressive loading, and the optional
generic document-sequence interface currently live on
[`feature/library-organisation`](https://github.com/NomeSame/MusicMonster/tree/feature/library-organisation).
They have not yet been merged into `master`.

For device/emulator tests:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

Release builds use local signing configuration when provided; otherwise they are unsigned.
Signing keys and machine-specific configuration are not included in the repository.

## ✅ Development status

Validation of **`feature/library-organisation`**, not the published APK:
**20 October 2026**.

- **693 local unit/API/Robolectric test executions passed**, with no failures or skipped tests.
- Debug and release builds, the Android test APK, and lint checks completed successfully.
- **124 instrumented tests passed on a physical API 33 device**, using separate test
  app data and owned fixtures. API 24, API 30, and API 35/36 validation remains open.

On that feature branch, large folders use batched document-provider queries and show titles before
reading media durations. Missing durations load in the background and display a dash
until available; validated duration metadata is cached across launches. Refreshing
or switching folders cancels obsolete work and preserves the current view during a
refresh of the same folder tree.

Initial-display tests do not establish smooth scrolling. Scroll performance is
still under investigation; a release-build frame measurement remains open.

Recent fixes improve playlist imports and ID allocation, folder permissions, library
scanning, Unicode title sorting, playback-session cleanup, image decoding bounds,
and sleep-timer behavior.

## 💬 Feedback

Bug reports and feedback are welcome in
[Issues](https://github.com/NomeSame/MusicMonster/issues).
Please include your app version, Android version, device model, and steps to reproduce.
For playback problems, include the audio format and whether the file came from the device
library or a selected folder.
