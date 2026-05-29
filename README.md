# Symfonium Lyric Provider

Xposed/LSPosed module that exports Symfonium realtime lyrics to [Lyricon](https://github.com/tomakino/lyricon).

> [!WARNING]
> This module is mostly vibe coded. I have inspected its logic and it works for me, but use at your own risk.

## Package

- Application ID / namespace: `pub.lantian.symfoniumlyricprovider`
- Xposed entry point: `pub.lantian.symfoniumlyricprovider.HookEntry`
- Target package: `app.symfonik.music.player`

## Hook Summary

- Playback state is mirrored from `android.media.session.MediaSession#setPlaybackState`.
- Track metadata is mirrored from `android.media.session.MediaSession#setMetadata`.
- Lyrics are discovered by scanning Symfonium dex classes for the runtime structure of its lyric container instead of hard-coding obfuscated class names.
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

Do not assume an existing APK under `app/build/` reflects the latest source changes unless the corresponding build command has been run after those changes.
