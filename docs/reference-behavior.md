# Reference exploration

Inspected Smart AudioBook Player 11.9.0 (package `ak.alizandro.smartaudiobookplayer`) installed from Google Play on an Android 13 / API 33 ARM64 emulator. Used generated MP3 files numbered 1, 2, and 10, an M4B file, and an original illustrated cover. No existing app code or assets were extracted.

The free installation automatically offered “Basic + Full (30 days left)”. No purchase was made. This allowed direct inspection of several features expected to be paywalled. Where exact behavior was not exercised, Hearth's behavior below is a deliberate implementation choice, not a claim about the reference.

| Area | Observed in reference | Hearth behavior |
| --- | --- | --- |
| Library | Folder picker; book folders can be nested under authors; All/New/Started/Finished; manual Full scan | Same local-folder organization and statuses, with search, list/grid views, sorting, and automatic refresh |
| Playback | File navigation, book progress, individual file time, 10-second and minute skips | Whole-book scrubbing, file/chapter list, cross-file skips, background session controls |
| Speed | Slider, plus/minus, presets 1/1.25/1.5/1.75/2; skip silence; time-left changes with speed | 0.5–3× in 0.05 steps, per-book persistence, natural pitch, speed-adjusted remaining time, silence skipping |
| Bookmarks | Title, description, current file/time, seek controls | Named saved positions, navigation, and removal |
| Characters | Character-list entry point; full settings describes name reminders | Per-book names and notes |
| Sleep | Inactivity duration, shake reset, schedule, motion sensitivity, fadeout/reset notification settings | Fixed countdown or end-of-file stop, 15-second fadeout, optional shake reset/resume; fixed sensitivity |
| Rewind | Pause-dependent rewind, up to 30 seconds in the selected reference setting | Configurable 0/5/10/15 seconds when resuming after a pause |
| Sound | Volume boost and equalizer are full-version tools | Flat/clear-voice/warm-bass presets and 0–9 dB boost |
| History | History is available through the More menu | Last 100 open/seek positions per book |
| Artwork | Embedded artwork rendered centrally with a play overlay and tinted background; separately copied folder images did not render in this reference setup | Folder JPG/PNG artwork or embedded metadata, with an original fallback treatment |
| Extras | Cast, conversion, rotation gestures, repeat, and online artwork features are visible | Scope recorded in README; no claim of complete feature parity |

Hearth's identity, interface, icon, and demo artwork are original. The goal is an independently implemented local audiobook player, not a pixel-for-pixel copy.

## Automatic refresh

Hearth subscribes to folder/MediaStore change notifications and checks every ten seconds while its activity is visible. It also scans when brought to the foreground. Providers do not all emit usable change events, so polling is intentional. A scan uses one metadata query per folder and reuses duration/chapter metadata for unchanged files. It does not wake an otherwise closed app to scan. Failed or revoked access retains the previous catalog and saved progress and displays a recovery message.
