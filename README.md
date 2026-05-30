# Symfonium Lyric Provider

Xposed/LSPosed module that exports Symfonium realtime lyrics to [Lyricon](https://github.com/tomakino/lyricon).

Tested against Symfonium 15.0.0B2, but this module tries to detect Symfonium's internal data structures dynamically, and should hopefully work with other versions as well.

> [!WARNING]
> This module is mostly vibe coded. I have inspected its logic and it works for me, but use at your own risk.

## Package

- Application ID / namespace: `io.github.proify.lyricon.symfoniumprovider`
- Xposed entry point: `io.github.proify.lyricon.symfoniumprovider.xposed.HookEntry`
- Target package: `app.symfonik.music.player`

## Hook Summary

- Playback state is mirrored from `android.media.session.MediaSession#setPlaybackState`.
- Track metadata is mirrored from `android.media.session.MediaSession#setMetadata`.
- Lyrics are discovered from Symfonium's current renderer-state object, then deduced from that object's current playable-media field. `MediaStateHeuristics` owns that structure detection. This avoids publishing lyrics that Symfonium parsed for a preloaded next song.
- Renderer state, playable media, lyric containers, lyric lines, and cues are detected by runtime structure instead of hard-coding obfuscated class names.
- Structural scans use shared safe reflection helpers, so candidate classes with unresolved field types are skipped instead of breaking hook installation.
- Lyric lines and word cues are mapped reflectively by field type and runtime value shape, then converted to Lyricon's `RichLyricLine` and `LyricWord` models.

## Build on NixOS

Use the flake apps to build APKs with the pinned Gradle, JDK, and Android SDK environment:

```bash
nix run .#build-debug
nix run .#build-release
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The release APK is written under:

```text
app/build/outputs/apk/release/
```

For manual Gradle work, enter the same environment with:

```bash
nix develop
```

GitHub Actions does not use Nix. It uses standard GitHub-hosted runner Java and Gradle tooling plus `android-actions/setup-android` to build debug APK artifacts for pushes and pull requests. Pushed tags build release APKs and attach them to the matching GitHub Release.

Do not assume an existing APK under `app/build/` reflects the latest source changes unless the corresponding build command has been run after those changes.
