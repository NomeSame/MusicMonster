# Architecture — Music Monster (MusicBox)

> **Für Menschen UND KI-Agenten, die an diesem Projekt arbeiten.**
> Diese App wurde bewusst aus einer 1881-Zeilen-„God-Activity" in die unten beschriebene Struktur zerlegt.
> **Halte diese Struktur ein — auch bei neuen Features.** Nicht alles zurück in MainActivity oder ins ViewModel kippen.

## Big picture
Lokaler Android-Musikplayer. **Kotlin + Jetpack Compose (Material3)**, Wiedergabe via **ExoPlayer** in einem Foreground-**Service** mit MediaSession. State-Handling über **ein ViewModel + StateFlow**. UI ist **zustandslos** (state hoisting) und previewbar.

## Schichten & wohin was gehört
```
model/         Reine Datenklassen (keine Logik, kein Android). z.B. Playlist, Song.
data/          Repositories — lesen/schreiben Daten. SongRepository (MediaStore/Tree),
               PlaylistRepository (SharedPreferences + JSON import/export).
audio/         EqualizerController — kapselt Equalizer/BassBoost/Presets.
playback/      PlaybackConnection — kapselt MediaControllerCompat, gibt Playback-State als StateFlow.
               MusicService — ExoPlayer + MediaSession (die Wiedergabe-Engine). NICHT beiläufig umbauen.
ui/screens/    Ganze Bildschirme/Panels: PlayerScreen, QueuePanel, SleepTimerPanel,
               EqualizerPanel, VisualizerPanel.
ui/components/ Wiederverwendbare kleine Bausteine: SongRow, PlaylistRow, PlaylistSongRow, TransportControls.
ui/theme/      Farben, Typo, Theme.
MainViewModel  (AndroidViewModel) — hält den UI-State (StateFlow), ruft Repos/Controller, enthält die App-Logik.
MainActivity   Dünn (~150 Zeilen): Permissions/Launcher + setContent { PlayerScreen(...) }. Sonst NICHTS.
```

## Goldene Regeln (bitte einhalten)
1. **MainActivity bleibt dünn.** Nur Permissions/Launcher + `setContent`. Keine Logik, kein State, keine UI-Bäume hier.
2. **State lebt im `MainViewModel`**, nicht in Composables oder der Activity. Composables bekommen Werte + Lambdas als Parameter (**state hoisting**) und sind damit **zustandslos**.
3. **Jede Composable-Funktion hat `@Preview`** (wo sinnvoll) und liest keinen globalen/Activity-State direkt.
4. **Daten immer über ein Repository** (`data/`) — nie direkt aus einem Composable oder der Activity auf MediaStore/SharedPreferences zugreifen.
5. **Android-/System-Kram wird gekapselt** (PlaybackConnection, EqualizerController), nicht roh in die UI gestreut.
6. **`MusicService` ist die Wiedergabe-Engine** — konsumieren, nicht umbauen, außer die Aufgabe verlangt es explizit.
7. **Eine Datei = eine Aufgabe.** Wird ein Screen/eine Klasse zu groß oder macht zwei Dinge → aufteilen (siehe unten).

## Neues Feature hinzufügen — Checkliste
- Neue Datenform? → `model/`.
- Daten laden/speichern? → neues/bestehendes Repository in `data/`.
- Neuer Bildschirm/Panel? → `ui/screens/`, zustandslos, State per Parameter.
- Kleiner wiederverwendbarer Baustein? → `ui/components/`.
- Neuer State / neue Logik? → in `MainViewModel` (als StateFlow), nicht in die UI.
- System-/Hardware-Zugriff (Audio, Notification, …)? → eigener Wrapper in `audio/` bzw. `playback/`.
- **Danach:** baut es grün (`./gradlew assembleDebug`)? Bleibt MainActivity dünn? Ist die neue Composable zustandslos + previewbar?

## „Ist eine Datei zu groß?" — Faustregeln
- Eine **Composable > ~150–200 Zeilen** oder mit mehreren unabhängigen UI-Blöcken → in kleinere Composables zerlegen.
- Eine **Klasse, die 3+ Dinge tut** (z.B. State + Daten + UI) → nach Verantwortlichkeiten aufteilen.
- **MainActivity, die State/Logik hält** → Warnsignal, gehört ins ViewModel.
- Nicht Zeilen zählen als Selbstzweck — Leitfrage: **„Tut diese Datei mehr als eine Sache?"** Wenn ja: trennen. Die Zeilen-Schwellen oben sind **Warnlichter, keine Gesetze.**
- **Bewusstes Gegenbeispiel:** `MusicService` (~680 Z.) ist absichtlich NICHT zerlegt — es tut *eine* kohärente Aufgabe (die Wiedergabe-Engine: Player + MediaSession + Notification, die zusammen leben). Groß, aber eine Aufgabe = ok. (Wenn es weiter wächst, wären Notification-Bau und das Device/Tree-Laden die Kandidaten zum Auslagern — nötig ist es nicht.)

## Arbeitsweise (gilt auch für KI-Agenten)
- **Inkrementell**, ein Schritt = ein Commit. Nach jedem Schritt `./gradlew assembleDebug` grün.
- **Refactor ≠ Feature** in denselben Commit mischen.
- Bricht etwas: zurück zum letzten grünen Commit, melden — nicht drüberbügeln.
