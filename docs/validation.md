# Validation

## Resume after navigation

- Confirmed the original bug with a real chaptered M4B: selecting the chapter at 40 seconds, then pressing Play, resumed at 35 seconds.
- Android 16: all eight device tests pass, including preserving paused seeks within and across files, chapter navigation with resume rewind enabled, and ordinary pause/resume rewind.
- Debug and optimized release builds, both lint checks, and all 15 JVM tests in each variant pass.
- Android 13: manually verified the optimized release with The Lantern Keeper using pause, chapter scrubbing, Next, and Play. Playback stays in the selected chapter.

## Version 0.1.2

- Release build, R8 optimization, and release lint pass.
- 15 JVM tests pass, including mixed file/chapter timelines, exact boundaries, invalid metadata, and speed-adjusted remaining time.
- Android 13: the two new UI tests and four existing device tests passed in the full run. The existing background-playback persistence test passed on recheck after replacing a fixed delay with a bounded wait for actual progress.
- New UI coverage uses real chaptered M4B audio: library opening stays paused, previous/next work across files and embedded chapters, last-chapter next is disabled, chapter selection and highlighting agree, and navigation preserves playing/paused state.
- Optimized release manually checked on Android 13 for paused opening, embedded chapter navigation, chapter-relative scrubbing, chapter list highlighting, and layout.
- Android 16 coverage below refers to the initial version; this update was validated on Android 13.

## Initial version


- Android lint: passes (no errors; dependency-update and Kotlin-extension suggestions remain).
- JVM tests: 11 passed, covering natural sorting, boundaries, cross-file seeking, remaining-time arithmetic, and real Nero/QuickTime M4B chapter fixtures.
- Android 13 / API 33: five instrumentation tests passed, using real storage and playback without mocks.
- Android 16 / API 36: the same five instrumentation tests passed.

Manual checks on Android 13: folder selection, library list, cover rendering, playback/pause, speed selection and adjusted time remaining, bookmark creation/listing, embedded M4B chapter selection, process-restart position recovery, and Android media-session registration. Folder-based and embedded artwork both rendered in Hearth.

The emulator cannot establish physical-speaker sound quality, Bluetooth route behavior, real-device motion sensitivity, or Android Auto dashboard behavior. No Chromecast receiver or cloud service is included.
