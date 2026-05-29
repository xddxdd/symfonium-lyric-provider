# Symfonium Lyric Provider Project Notes

## Project Purpose

This project is an Xposed/LSPosed module that extracts realtime lyrics from the Symfonium playback process and exposes them through Lyricon Provider for system lyric display components.

- Module package: `pub.lantian.symfoniumlyricprovider`
- Xposed entry point: `pub.lantian.symfoniumlyricprovider.HookEntry`
- Target app package: `app.symfonik.music.player`

## Source Structure

```text
app/src/main/java/pub/lantian/symfoniumlyricprovider/
  HookEntry.java    Xposed entry point, Lyricon Provider initialization, MediaSession hooks, current renderer-state discovery
  MediaStateHeuristics.java  current renderer-state and playable-media structure detection
  ReflectionAccess.java  shared safe reflection helpers for structural scans
  LyricLines.java   lyric line structure detection, line timing scoring, lyric candidate selection, signature generation, RichLyricLine conversion
  LyricCues.java    Cue structure detection, char range and timing scoring, LyricWord conversion

app/src/main/assets/xposed_init
  Xposed entry point declaration. Keep it in sync with HookEntry's fully qualified class name.

app/src/main/res/
  Minimal module metadata resources only. Launcher artwork is intentionally omitted so Android uses its default app icon.

app/build/
  Gradle-generated output. Do not edit generated artifacts by hand.
```

## Implementation Details

`HookEntry` owns the outer flow: target process filtering, Lyricon Provider initialization, hooks for `MediaSession#setMetadata` and `MediaSession#setPlaybackState`, and dex scanning to hook Symfonium's current renderer-state object.

`MediaStateHeuristics` owns current renderer-state and playable-media detection. It detects the state object by its playback-state field shape, then extracts the current playable-media object that contains both the stable `MediaItem` model and parsed lyric candidates.

Lyrics are not published from raw lyric constructors because Symfonium may construct lyrics for preloaded queue items before playback reaches them. Instead, `HookEntry` hooks the current renderer-state shape, reads its current playable-media object, and deduces the lyric container from that current playable object. This keeps lyrics tied to the object Symfonium marks as currently playing.

Renderer state, playable media, lyric containers, lyric lines, and cues are detected structurally rather than by the obfuscated class names found in the current Symfonium APK. This keeps the hook resilient when R8/ProGuard renames classes or fields, as long as the underlying data model keeps the same runtime shape.

`ReflectionAccess` owns shared reflection primitives used by these structural scans. It catches metadata-resolution failures from `getDeclaredFields()` and `Field#getType()` so unrelated candidate classes with missing framework/library references are skipped instead of aborting hook installation.

`LyricLines` owns all lyric-line logic, including deciding whether a `List<?>` looks like a lyric line list, scoring multiple lyric candidates from the current playable-media object, reading line text and timing, generating duplicate-prevention signatures, normalizing missing line end times, and converting to Lyricon's `RichLyricLine`.

`LyricCues` owns all cue logic, including cue list validation, cue start time, optional end time, `charStart`/`charEnd` character ranges, and conversion to Lyricon's `LyricWord`. `charStart` and `charEnd` are substring boundaries into the parent lyric line text.

Line timing and cue field mapping use scoring because obfuscation leaves several fields with the same runtime type, such as multiple `int` fields. Reflection can identify the field types, but not which field means begin, end, charStart, or charEnd. The scoring logic uses semantic constraints such as valid time intervals, valid character ranges, declaration-order preference, and known track duration to reduce false positives.

## Development And Verification

This system is NixOS. Use the flake apps for APK builds:

```bash
nix run .#build-debug
nix run .#build-release
```

Use the Nix dev shell for manual Android and Gradle tooling:

```bash
nix develop
```

Do not start an APK build unless the user explicitly asks for it. For ordinary refactors or documentation edits, prefer source inspection with `rg` and targeted file reads.

When changing the package name, update all of these locations together:

- `app/build.gradle.kts` `namespace` and `applicationId`
- Java `package` declarations
- `HookEntry.PROVIDER_PACKAGE`
- `app/src/main/assets/xposed_init`
- `README.md` and this file
