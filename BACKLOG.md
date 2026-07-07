# Backlog — Music Monster

Bekannte Bugs & „später"-Punkte, damit nichts im Chat verloren geht.

## Bekannte Bugs
- **Visualizer reagiert nicht auf Sound.** Vorbestehend (nicht durch den Refactor). Vermutlich Android-15-/Visualizer-API-Einschränkung + RECORD_AUDIO. Priorität: niedrig.
- **Rotation ruckelt kurz bei der Wiedergabe.** Musik läuft weiter, Position bleibt erhalten — nur ein kurzer Hänger beim Drehen. War nie Main-Goal. **Vor bezahltem Release ruckelfrei machen.** Priorität: mittel (für „fertige App").

## Features / Verbesserungen (nach dem Refactor)
- **Folderpicker soll im aktuellen Ordner starten** (statt bei null). Technisch via `EXTRA_INITIAL_URI`. Priorität: niedrig-mittel.
- **Splashscreen klären:** ist der aktuelle Splash der Android-12+-System-Default oder ungewollt hinzugefügt? Ggf. bewusst stylen oder abschalten.

## Technische Schulden (optional, wenn es wächst)
- `MusicService` (~680 Z.) ist ok (eine kohärente Aufgabe), aber falls es weiter wächst: Notification-Bau und das Device/Tree-Song-Laden auslagern (Letzteres überschneidet sich mit `SongRepository`).

## Vor Play-Store-Release (Pflicht)
- **Package-Rename** `com.example.myapplication` → z.B. `com.nomesame.musicmonster` (via Android Studio → Refactor → Rename + `applicationId`). Eigener, isolierter Commit.
