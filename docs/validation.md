# Validation

- Android lint: passes (no errors; dependency-update and Kotlin-extension suggestions remain).
- JVM tests: 11 passed, covering natural sorting, boundaries, cross-file seeking, remaining-time arithmetic, and real Nero/QuickTime M4B chapter fixtures.
- Android 13 / API 33: five instrumentation tests passed, using real storage and playback without mocks.
- Android 16 / API 36: the same five instrumentation tests passed.

Manual checks on Android 13: folder selection, library list, cover rendering, playback/pause, speed selection and adjusted time remaining, bookmark creation/listing, embedded M4B chapter selection, process-restart position recovery, and Android media-session registration. Folder-based and embedded artwork both rendered in Hearth.

The emulator cannot establish physical-speaker sound quality, Bluetooth route behavior, real-device motion sensitivity, or Android Auto dashboard behavior. No Chromecast receiver or cloud service is included.
