# Music Monster (MusicBox) — Consolidated TODO & Reference

> **Stand:** 07/2026 — Feature-Complete für v1 (Gratis-Release).
> Letzter Commit: `e3df767` — swipe screens, background, scrollbar, layout.
> **Bugfix (10.07.):** Custom-Background wurde weiß bei JPEG-Bildern (ContentResolver-Stream fehlte mark/reset für BitmapFactory). Gefixt: `decodeSampledBitmap` auf `decodeByteArray` umgestellt + Fallback auf Gradient bei fehlgeschlagenem Decode.
> Alle Source-Dokumente in diese Datei konsolidiert und gelöscht.
> Branch: `master` (uncommitted: Bugfix + TODO.md). `release`-Branch existiert (keine eigenen Commits).

---

## 1. Was die App ist

Lokaler Android-Musikplayer für eigene **.mp3/.mp4/.flac/.wav/.ogg**-Dateien.
**Kotlin + Jetpack Compose (Material3)**, Wiedergabe via **androidx.media3 (ExoPlayer)** in Foreground-**Service** mit MediaSession.
Ursprünglich „Music Box", intern umbenannt zu **„Music Monster"** (Repo heißt noch `MusicBox`).

**Wedge:** schön, flüssig, selbsterklärend, immersiv — keine Werbung, kein Internet.

---

## 2. Architektur (Regeln — bitte einhalten)

### Schichten

```
model/         Reine Datenklassen (keine Logik, kein Android). z.B. Playlist, Song.
data/          Repositories — lesen/schreiben Daten. SongRepository (MediaStore/Tree),
               PlaylistRepository (SharedPreferences + JSON import/export),
               BackgroundRepository (Custom-BG URI + Scrim).
audio/         EqualizerController — kapselt Equalizer/BassBoost/Presets.
playback/      PlaybackConnection — kapselt MediaControllerCompat, gibt Playback-State als StateFlow.
               MusicService — ExoPlayer + MediaSession (Wiedergabe-Engine). NICHT beiläufig umbauen.
ui/screens/    Ganze Bildschirme/Panels: PlayerScreen, QueuePanel, SleepTimerPanel,
               EqualizerPanel, PlaylistScreen.
ui/components/ Wiederverwendbare kleine Bausteine: SongRow, PlaylistRow, PlaylistSongRow,
               TransportControls, FastScroller, AppBackground, AccentPicker.
ui/theme/      Farben, Typo, Theme, PaletteEngine.
MainViewModel  (AndroidViewModel) — hält UI-State (StateFlow), ruft Repos/Controller, App-Logik.
MainActivity   Dünn (~160 Z.): Permissions/Launcher + setContent { PlayerScreen(...) }. Sonst NICHTS.
```

### Goldene Regeln

1. **MainActivity bleibt dünn.** Nur Permissions/Launcher + `setContent`. Keine Logik, kein State, keine UI-Bäume.
2. **State lebt im `MainViewModel`**, nicht in Composables oder Activity. Composables bekommen Werte + Lambdas (state hoisting) — zustandslos.
3. **Jede Composable hat `@Preview`** (wo sinnvoll), liest keinen globalen State direkt.
4. **Daten immer über ein Repository** (`data/`) — nie direkt aus Composable/Activity auf MediaStore/SharedPreferences.
5. **Android-/System-Kram wird gekapselt** (PlaybackConnection, EqualizerController), nicht roh in die UI.
6. **`MusicService` ist die Wiedergabe-Engine** — konsumieren, nicht umbauen, außer Task verlangt es explizit.
7. **Eine Datei = eine Aufgabe.** Wird etwas zu groß / macht zwei Dinge → aufteilen.

### Arbeitsweise

- **Inkrementell**, ein Schritt = ein Commit. Nach jedem Schritt `./gradlew assembleDebug` grün.
- **Refactor ≠ Feature** in denselben Commit mischen.
- Bricht etwas: zurück zum letzten grünen Commit, melden — nicht drüberbügeln.

---

## 3. Was implementiert ist (Feature-Complete v1 ✅)

### Phasen 0–7 (aus ursprünglichem TODO)

| Phase | Beschreibung | Status |
|-------|-------------|--------|
| 0 | Visualizer entfernt, `RECORD_AUDIO` raus | ✅ |
| 1 | Persistenz: letzter Song/Position/Shuffle, auch bei `onTaskRemoved`, Liste scrollt zum Song | ✅ |
| 2 | Songliste als Standard-Ansicht, Collapse → Equalizer | ✅ |
| 3 | Swipe-Navigation: 3 Screens (Playlists ◀ Equalizer ▶ Sleep-Timer) via HorizontalPager | ✅ |
| 4 | Natürliche Sortierung (1,2,10 statt 1,10,2) | ✅ |
| 5 | Fast-Scroll-Sidebar mit Haptik + Buchstaben-Label (gegenüberliegende Seite) | ✅ |
| 6 | Folder-Icon am Folder-Picker | ✅ |
| 7 | Feel-Politur: Rotation-Fix, Scrollen, Klicks | ✅ |
| 8 | Player-Card-Opacity-Slider im Color-Picker (transparenter Playerbereich) | ✅ |

### Release-Prep (aus RELEASE_TODO)

| Schritt | Beschreibung | Status |
|---------|-------------|--------|
| 1 | Package-Rename `com.example.myapplication` → `com.nomesame.musicmonster` | ✅ |
| 2 | Signierter Release-Build (Config + Verifikations-Key) | ✅ |
| 3 | ExoPlayer 2.18.1 → androidx.media3 1.4.1 | ✅ |
| 4 | Rotation-Ruckler-Fix (`android:configChanges`) | ✅ |
| 5 | Splashscreen gebrandet (core-splashscreen, dunkel #121316 + Icon) | ✅ |
| 6 | Fast-Scroll-Fix: 12dp vom rechten Rand, systemGestureExclusion, Verdickung | ✅ |
| 7 | Store-Listing-Draft (STORE_LISTING.md → hier unten integriert) | ✅ |

### Features im Detail

- Lokale Wiedergabe · Playlists (CRUD, Longpress, JSON Import/Export, persistent)
- Equalizer + Presets (Metal/Rock/Classic/Flat/Pop) + BassBoost
- Sleep-Timer mit Presets + manuell (h/m/s) + sanftes Ausblenden
- Next-Up-Queue mit Shuffle
- Lockscreen-/Notification-Controls (Tint folgt Accent-Farbe)
- Seekbar
- Wählbare Accent-Farbe (Farbwähler, live + persistent, inkl. Lockscreen)
- **Custom-Background / Personalisierung:** Master-Switch, Bild-Picker (SAF), Default-Reset, Transparenz-/Dim-Slider (Default 0.75), Auto-Palette (PaletteEngine → „Match theme to image?"-Dialog)
- **Rendering:** Ein Vollbild-Background am Root (Bild+Scrim oder Verlauf); alle Ebenen transparent; Liste transparent, wischt nur nach unten
- Button-Schriftfarbe folgt Accent-Luminanz (`onAccent`)
- build.gradle.kts aufgeräumt (keine Platzhalter/Version-Duplikate mehr)
- Kommentar-Cleanup (KI-Artefakte entfernt)
- Hardening: `FLAG_IMMUTABLE`, keine exportierten Komponenten, Least Privilege

---

## 4. Noch offen / 👤 (nicht vom Agenten machbar)

> **Legende:** 👤 = braucht dich (Gerät / Play Console / Secret / Grafik)

### Vor Release (Release-Gate)

- [ ] 🔲 Fast-Scroll am echten Gerät verifizieren (Greifbarkeit, Verdickung, Buchstaben-Label, Zurück-Geste)
- [ ] 🔲 media3-Migration + Rotation-Fix Feel-Check am Gerät
- [ ] 🔲 Custom-Background / Personalisierung Verhalten final am Gerät prüfen
- [ ] 🔲 **Produktions-/Upload-Key** durch echten ersetzen + sichern (Verlust = kein Update möglich außer Play App Signing Recovery)
- [ ] 🔲 Datenschutz-URL erstellen (Play verlangt eine — „Music Monster erhebt keine Daten." reicht, muss öffentlich erreichbar sein)
- [ ] 🔲 Screenshots + Feature-Grafik (1024×500) erstellen (Rohmaterial: `AppIcon.png`, `Design.png`, `background_screen2.png`)
- [ ] 🔲 **Closed Test:** 12 Tester über 14 Tage (Pflicht für neue persönliche Accounts) → Produktions-Freigabe
- [ ] 🔲 Store-Listing Assets hochladen (Icon 512×512, Screenshots min. 2, Feature-Grafik)

### Fixes (erledigt)

- [x] **Custom-Background weiß bei JPEG** — `BitmapFactory.decodeStream` scheiterte bei ContentResolver-Streams (fehlendes mark/reset). `decodeSampledBitmap` liest jetzt per `readBytes()` in Byte-Array und decoded via `decodeByteArray`. Zusätzlich Fallback auf Gradient bei fehlgeschlagenem Decode.

### Backlog (niedrige Prio)

- [ ] 🔲 **Folderpicker startet im aktuellen Ordner** — via `EXTRA_INITIAL_URI` (Prio niedrig-mittel)
- [ ] 🔲 **Visualizer** (entfernt) — war kaputt (Android-15-API + RECORD_AUDIO-Hürde). Optional dekorativ ohne Mikro wieder einbauen (Prio niedrig)
- [ ] 🔲 **`allowBackup=true`** — sobald `pro_unlock` persistiert wird, diesen Key vom Backup ausschließen

### Technische Schulden

- `MusicService` (~758 Z.) ist ok (eine kohärente Aufgabe), aber falls es wächst: Notification-Bau und Device/Tree-Song-Laden auslagern
- `QueuePanel.kt` (351 Z.) — aktuell nicht von `PlayerScreen` referenziert (verwaist). Prüfen ob noch gebraucht oder löschen.

---

## 5. Monetarisierung (für später, nicht v1)

**Entscheidung: v1 wird GRATIS ohne Paywall released.**
Ziel: Lerneffekt + erste App live + Erfolgsgefühl. Umsatz erst wenn Zug entsteht.

**Geplanter Pro-Unlock (3,50 € einmalig, kein Abo):**
- Personalisierungs-Engine (Custom-BG + Auto-Palette — aktuell im Gratis-Build; ggf. später hinter Paywall)
- Unbegrenzte Playlists (Gratis auf 3 limitiert)
- Custom-EQ-Bänder + eigene Presets speichern
- Kommende Optik-/Komfort-Features

**Technisch:** `INAPP` non-consumable (`pro_unlock`), `queryPurchasesAsync(INAPP)` für Restore,
`StateFlow<Boolean>` im ViewModel, Gate-Typen: Zähl-Gate (Playlist-Limit) + Feature-Gate.
Eigener Wrapper `billing/BillingConnection` (analog `PlaybackConnection`) gemäß Architektur.

---

## 6. Store-Listing (Kurzreferenz)

- **Titel:** `Music Monster`
- **Package:** `com.nomesame.musicmonster`
- **Kategorie:** Musik & Audio
- **Preis:** Gratis, keine IAPs (v1), keine Werbung
- **Kurzbeschreibung (80 Z.):** "Schöner, flüssiger, werbefreier Player für deine eigene Musik. Ohne Internet."
- **USP:** Keine `INTERNET`-Permission — Daten verlassen das Gerät nie
- **Data Safety:** Keine Nutzerdaten erhoben/geteilt
- **Permissions:** `READ_MEDIA_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`/`_MEDIA_PLAYBACK`, `MODIFY_AUDIO_SETTINGS`, `WAKE_LOCK`

---

## 7. Asset-Checkliste (👤)

- [ ] App-Icon 512×512 PNG (aus `AppIcon.png`)
- [ ] Feature-Grafik 1024×500 PNG/JPG
- [ ] Phone-Screenshots min. 2 (Songliste, Equalizer, Playlists, Sleep-Timer, Lockscreen)
- [ ] Datenschutz-URL
- [ ] (Optional) Tablet-Screenshots, Promo-Video
