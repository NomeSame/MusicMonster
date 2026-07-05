# Claude-Code-Auftrag: „Music Monster" (MusicBox) refactoren + Design-Makeover

> Dieser Text ist als Anweisung für Claude Code gedacht. Paste ihn in Claude Code im Projektordner,
> oder sag: „Folge CLAUDE_CODE_REFACTOR_PROMPT.md". Arbeite die Schritte der Reihe nach ab.

## Kontext (was die App ist)
Android / Kotlin / **Jetpack Compose (Material3)** Musikplayer „Music Monster".
Playback über **ExoPlayer 2.18.1** + **MediaSessionCompat/MediaControllerCompat** (androidx.media).
**Diese Features existieren und dürfen NICHT kaputtgehen:** lokale Wiedergabe, Playlists (add/delete, Longpress-hinzufügen, **JSON Import/Export**, persistent via SharedPreferences `"music_prefs"`), **Equalizer** + Presets + **BassBoost**, **Circular-Visualizer**, **Sleep-Timer**, **Next-Up-Queue mit Shuffle**, Lockscreen-/Notification-Controls, Seekbar.

## Ist-Zustand / Problem
- **`MainActivity.kt` ≈ 1881 Zeilen = God-Activity.** Sie hält:
  - Allen UI-State direkt als Activity-Felder (`mutableStateOf`/`mutableStateListOf`): `songs`, `playlists`, `nowPlayingTitle/Id`, `isPlaying`, `isShuffled`, `playbackPositionMs`, `playbackDurationMs`, `audioSessionId`, `eqEnabled`, `eqBandLevels/Count/Hz`, `equalizer`, `bassBoost*`, `eqPresetLabel`, `controllerReady` …
  - Permissions + alle `registerForActivityResult`-Launcher, `initMediaController()`, Service-Start.
  - Daten: `loadSongs`, `loadSongsFromTree`, `loadPlaylists`/`savePlaylists`, `exportPlaylistsToUri`/`importPlaylistsFromUri`.
  - Audio-Effekte: `buildPresetLevels`, `setupEqualizerForSession`.
  - Sleep-Timer: `startSleepTimer`/`cancelSleepTimer`. Business-Logik: `playPlaylist`, `nextUpSong`, `createPlaylist`, `addSongToPlaylist`.
  - Die **gesamte UI als Activity-Methoden**: `MainScreen()` (~525 Z.), `QueueAndSleepPanel()` (~460 Z.), `VisualizerPanel()`, `EqualizerPanel()` → greifen direkt auf Activity-Felder zu.
- **`Playlist`** ist als **private nested class** in MainActivity (≈ Z. 131) definiert.
- **Kein ViewModel, keine Repositories, keine Trennung.**
- **`MusicService.kt` (≈677 Z.)** = ExoPlayer + MediaSession Foreground-Service → ist ok getrennt, **so lassen**, nur konsumieren.
- **`Song.kt`** = saubere top-level data class → behalten.

## Ziel
Wartbare Architektur (**ViewModel + Repositories + zustandslose, `@Preview`-bare Composables**), damit danach ein Design-Makeover gefahrlos geht. **Kein Feature-Verlust, keine Verhaltensänderung in Phase 1.**

## ⛔ HARTE REGELN (unbedingt einhalten)
1. **Inkrementell, ein Schritt nach dem anderen.** Nach JEDEM Schritt: `./gradlew assembleDebug` muss grün sein; App startet & das betroffene Feature funktioniert wie vorher (wenn möglich auf Gerät/Emulator prüfen).
2. **Nach jedem grünen Schritt committen** (kleine, beschreibende Commits). **KEIN Big-Bang-Rewrite.**
3. **Phase 1 = reines Refactoring: NULL Verhaltens- und NULL Design-Änderung.** Erst wenn die Architektur steht → Phase 2 (Design).
4. **Package/`applicationId`-Umbenennung NICHT hier** mitmachen (separater Schritt, siehe unten).
5. **`MusicService` Wiedergabe-/MediaSession-Logik nicht umbauen**, nur nutzen.
6. Wenn ein Schritt die App bricht oder etwas unklar ist: **stoppen, zum letzten grünen Commit zurück, melden** — nicht „drüberbügeln".

## Ziel-Struktur (Package `com.example.myapplication` vorerst BEIBEHALTEN)
```
model/        Song (vorhanden) · Playlist (aus MainActivity rausziehen) · PlaybackState · EqualizerState
data/         SongRepository (MediaStore/Tree laden) · PlaylistRepository (SharedPreferences + JSON import/export)
audio/        EqualizerController (Equalizer + BassBoost + Presets + zugehöriger State)
playback/     PlaybackConnection (kapselt MediaControllerCompat, exposed State als StateFlow) · MusicService (bleibt)
ui/
  screens/    PlayerScreen · QueuePanel · SleepTimerPanel · VisualizerPanel · EqualizerPanel
  components/ SongRow · PlaylistRow · NowPlayingBar · TransportControls · SeekBar …
  theme/      (vorhanden)
MainViewModel (androidx.lifecycle.ViewModel) – hält UI-State als StateFlow, ruft Repos/Controller
MainActivity  – dünn: Permissions/Launcher + setContent { AppRoot(vm) }
```

## Schritt-für-Schritt (in dieser Reihenfolge, je 1 Commit)
0. **Checkpoint (nur lokal, NICHT pushen):** den aktuellen (noch uncommitteten) Stand als einen Commit sichern — `git add -A && git commit -m "checkpoint: pre-refactor state"`. Das ist der Restore-Punkt, bevor irgendetwas geändert wird. **Push erfolgt NICHT jetzt**, sondern erst ganz am Ende, wenn der saubere refactorte Stand steht (das ist die neue Basis).
1. **`model/Playlist.kt`**: die nested `Playlist`-Klasse als top-level rausziehen. Build, test, commit.
2. **`data/PlaylistRepository.kt`**: `loadPlaylists`/`savePlaylists`/`export`/`import` (SharedPreferences `"music_prefs"` + JSON) hierher; MainActivity ruft nur noch das Repo. Build, test, commit.
3. **`data/SongRepository.kt`**: `loadSongs`/`loadSongsFromTree` hierher. Build, test, commit.
4. **`audio/EqualizerController.kt`**: `buildPresetLevels`/`setupEqualizerForSession` + Equalizer/BassBoost + deren State kapseln. Build, test, commit.
5. **`playback/PlaybackConnection.kt`**: MediaControllerCompat-Init + Callbacks kapseln; `nowPlaying`, `isPlaying`, `position`, `duration`, `isShuffled` als **StateFlow** exposen. Build, test, commit.
6. **`MainViewModel`** einführen: alle Activity-`mutableStateOf`-Felder in den ViewModel (als StateFlow) verschieben; VM nutzt Repos/Controller aus 2–5. Activity beobachtet nur noch VM-State. Build, test, commit.
7. **UI zerlegen:** `MainScreen` und `QueueAndSleepPanel` in **kleine, zustandslose** Composables (`ui/screens` + `ui/components`) splitten — jede bekommt Werte + Lambdas als Parameter (**state hoisting**), `@Preview` ergänzen. Ein Commit pro extrahiertem Screen/Component.
8. **`MainActivity` abspecken:** nur noch Permissions/Launcher + `setContent { AppRoot(vm) }`. Build, test, commit.

## Phase 2 – Design-Makeover (erst NACH Phase 1)
Ziel-Gefühl: **schön, flüssig, selbsterklärend, immersiv, Ein-Hand-bedienbar.**
- Konsistentes Theme in `ui/theme`: Farbpalette, Typo-Skala, Spacing-Skala (z.B. 4/8/16/24).
- Klarer Now-Playing-Screen, saubere Listen (SongRow/PlaylistRow), sinnvolle Transitions (`animate*AsState`, `AnimatedVisibility`), Empty-States.
- Erst **einen** Screen als „Referenz-Look" perfektionieren, dann den Stil auf die anderen übertragen.
- Weil die UI jetzt zustandslos + previewbar ist: jede Änderung isoliert per `@Preview` prüfbar. Kleine Schritte, Commit pro Screen.

## Separat (eigener Commit, am besten via Android Studio, NICHT mit obigem mischen)
- **`applicationId` / Package `com.example.myapplication` → z.B. `com.nomesame.musicmonster`.** In Android Studio: Package rechtsklick → Refactor → Rename (safe), + `applicationId` in `app/build.gradle.kts` anpassen. **Vor jedem Play-Store-Release Pflicht** (unter `com.example.*` kann man nicht veröffentlichen).

## Definition of Done (pro Schritt)
Build grün (`./gradlew assembleDebug`) · App startet · betroffenes Feature funktioniert wie vorher · Commit gesetzt.
