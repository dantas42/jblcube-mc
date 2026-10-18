# JBL Cube

A placeable music cube for Minecraft **1.21.1** / NeoForge **21.1+**. Paste a YouTube link, a
playlist URL or a direct audio URL, and everyone nearby hears it coming from the cube.

## Features

- Play, pause, skip and go back from the cube screen (or the keybinds you bind to those actions).
- The cube can be **carried in your hand or placed in the world**: placed cubes keep playing on
  their own, and everyone inside three chunks (48 blocks) hears them.
- Playlist support: `.m3u` / `.m3u8` / `.pls` files and YouTube playlist links.
- Music is **positional**: listeners hear the track coming from the cube, with distance
  attenuation just like a jukebox note block.
- Volume slider and repeat-one/off toggle. State lives on the item and on the block entity, so the
  playlist survives being dropped, stored in a chest, broken or moved between dimensions.
- Playlist state is synchronised from the server, so the editor is authoritative: other players
  never play a different track than the one they see.
- Downloads are cached on disk, so a track plays instantly the second time.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Mod loader | NeoForge 21.1.253 or newer |
| Java | 21 |

### External tools (optional but recommended)

| Tool | Why | Env override |
|---|---|---|
| [yt-dlp](https://github.com/yt-dlp/yt-dlp) | Resolves YouTube videos and playlists into direct audio URLs | `JBLCUBE_YTDLP` |
| [ffmpeg](https://ffmpeg.org) | Converts audio that is not MP3 into MP3 | `JBLCUBE_FFMPEG` |

The older `HANDMUSICBOX_YTDLP` / `HANDMUSICBOX_FFMPEG` names are still honoured as a fallback.

If `yt-dlp` is not installed, YouTube links are resolved through public Invidious instances
instead. If `ffmpeg` is missing, only MP3 sources can be played. Both tools are looked up on the
`PATH` first; the env vars above take precedence.

Downloads and transcodes are cached in `.minecraft/jblcube/cache`.

## How playback works

1. The client resolves the pasted text into one or more tracks (`SourceResolver`): playlist file →
   YouTube via yt-dlp → direct download (ffmpeg transcode if needed) → Invidious fallback.
2. Each listening client decodes the audio with a pure-Java MP3 decoder and feeds it to Minecraft
   through a streaming sound instance, so the sound starts before the file is fully downloaded
   and seeks are instantaneous.
3. A cube in your hand relays incremental state changes to the server, which broadcasts the current
   track, position, volume and holder to nearby players.
4. A placed cube stores its playlist in a block entity and syncs it with the vanilla block-entity
   update packet, so every client that can see the block rebuilds the same track.
5. Listening clients play it through their own sound engine at the cube's coordinates, so the music
   is localised per player.

## Building

```bash
./gradlew build
```

The release artifact is `build/libs/jblcube-<version>.jar` (the `-dev` and `-sources` jars are
build by-products). Drop it into `mods/` together with NeoForge.

Useful tasks:

```bash
./gradlew test            # unit tests (URL/playlist parsing, playlist logic, MP3 SPI)
./gradlew runClient       # development client
./gradlew runServer       # development server
```

## Bundled libraries and licensing

The mod itself is MIT licensed (`gradle.properties` → `mod_license`).

MP3 decoding uses libraries that are **shaded into** the released jar:

| Library | Version | License |
|---|---|---|
| [mp3spi](https://github.com/tritonus-share/mp3spi) (`com.googlecode.soundlibs:mp3spi`) | 1.9.5.4 | LGPL-2.1+ |
| [JLayer](https://github.com/philburk/jsyn/master) (`com.googlecode.soundlibs:jlayer`) | 1.0.1.4 | LGPL-2.1+ |
| tritonus-share | 0.3.7.4 | LGPL-2.1+ |

Because these components are LGPL, the jar ships them **unmodified and relocatable** (no shading
relocation is applied to them, so the LGPL requires that users can replace these libraries with a
modified version). YouTube links are resolved by yt-dlp, which is an external program and is not
bundled; make sure its use complies with YouTube's terms of service in your distribution.