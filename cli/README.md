# archivetune (CLI)

A terminal client for YouTube / YouTube Music. Same innertube engine as the
Android app, no emulator and no Android SDK needed.

## Requirements

- JDK 21 (the build downloads one automatically; set `JAVA_HOME` if your JDK is
  not on `PATH`)
- [VLC](https://www.videolan.org/vlc/) or [mpv](https://mpv.io/) as the audio
  backend - `archivetune player --probe` reports which one was found

## Build and install

```sh
./gradlew :cli:installCli            # builds cli-all.jar
powershell -File scripts/install-cli.ps1   # Windows: launcher + PATH
```

The launcher goes to `%LOCALAPPDATA%\ArchiveTune\bin`, so `archivetune` works
from any new terminal.

## Use

```sh
archivetune                        # full-screen player
archivetune radiohead              # player, pre-searched
archivetune search "kay hanada"    # list results
archivetune play "hindia"          # play the best match
archivetune play "bohemian rhapsody" --all
archivetune lyrics "radiohead creep"
archivetune auth youtube           # link an account (optional)
archivetune config path            # where settings live
```

`play` takes words, a URL, a video id, an album browse id or a playlist id. A
search plays the best match with the remaining results queued behind it, so an
upload that YouTube gates is skipped instead of ending playback.

## Playback without a login

Playback does not need an account. The configured stream client is tried first
(`config set playback.streamclient <NAME>`) and guest VISIONOS is used as a
fallback, which is what serves YouTube when an account or an upload is refused.
`auth youtube` is only needed for anything that is account-bound.

## Notes for contributors

- `:app` and `:morideobfuscator` are skipped when no Android SDK is detected, so
  every plain-JVM module still configures and builds on a desktop machine.
- The CLI depends on three edits in the `core` submodule, which cannot be
  pushed from this repository. They live in `patches/` - see `patches/README.md`.