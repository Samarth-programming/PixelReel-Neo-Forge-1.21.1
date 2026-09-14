# pixelReel

NeoForge mod for **Minecraft Java Edition 1.21.1** that lets you place televisions and cinema screens in your world and watch **live TV** and **on-demand media** together with friends.

Live channels come from a Tunarr (or any M3U + XMLTV) playlist. Movies and shows can come from **Jellyfin**, **Emby**, or **Plex**. Each display keeps its own channel or title, multiple screens can play at once, and audio is positional.

> This branch targets the **stable 1.21.1** NeoForge toolchain.

## Requirements

| Requirement                  | Notes                                                                                             |
| ---------------------------- | ------------------------------------------------------------------------------------------------- |
| **Java 21+**                 | Required by Minecraft 1.21.1 / this toolchain.                                                    |
| **Minecraft 1.21.1**         | Built against this release specifically.                                                          |
| **NeoForge** `21.1.250`      | Pinned in `gradle.properties`.                                                                    |
| **VLC (64-bit)**             | Needed on each **client** for video/audio. Install from [videolan.org](https://www.videolan.org). |

Without VLC, menus, guide, commands, and multiplayer sync still work, but screens show a “video player unavailable” state instead of video.

If VLC is installed in a non-standard location, launch the game with:

```text
-Dpixelreel.vlc.path=C:\Path\To\VLC
```

## Building

Windows:

```bat
gradlew.bat clean build
```

macOS / Linux:

```sh
./gradlew clean build
```

The installable JAR is:

```text
build\libs\pixelreel-2.0.0.jar
```

Use the JAR **without** `-sources`.

Dev client (Run and Debug in Cursor/VS Code, or):

```bat
gradlew.bat runClient
```

## Installing

1. Install NeoForge for Minecraft **1.21.1**.
2. Put `pixelreel-2.0.0.jar` in `mods`.
3. Install **64-bit VLC** on every client that should see video.

The mod runs on clients and servers. For multiplayer, **both sides** need the mod. VLC is only required on clients.

**Dedicated servers** must also open **TCP 25567** (or whatever you set as `mediaProxyPort`) next to 25565. Video and library artwork go through that port so API keys never reach players. See [Media proxy](#media-proxy).

> **Disclaimer — tunnel your media server.** pixelReel keeps API keys off clients, but the Minecraft server still has to reach Jellyfin, Emby, Plex, or Tunarr. Do **not** put those services on a public IP or open their ports (8096, 32400, 8000, …) to the internet. Put them behind a tunnel or private network — [Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/), Tailscale, WireGuard, or a VPN — and point pixelReel at the HTTPS hostname the tunnel gives you, not a raw LAN or WAN address. You are responsible for how your media library is exposed. A leaked key plus an open media port is a full library leak; the mod cannot fix that for you. PLEASE USE A NON ADMIN USER FOR PLEX,JELLYFIN and EMBY

## Configuration

Created on first launch at `config/pixelreel.json`. Most day-to-day setup is done in-game (look at a screen and open the source/config GUIs). Treat this file as a secret: it holds API keys and tokens.

### Tunarr (Live TV / M3U / XMLTV)

Tunnel Tunarr the same way as the other media apps if this Minecraft server is public. Do not leave the M3U host on an open WAN IP.

| Key        | Default | Meaning                                                                                            |
| ---------- | ------- | -------------------------------------------------------------------------------------------------- |
| `m3uUrl`   | `""`    | Tunarr base URL or full M3U URL. A base like `http://1.1.1.1:8000` expands to `/api/channels.m3u`. |
| `xmltvUrl` | `""`    | XMLTV guide URL. Auto-filled from a Tunarr base as `/api/xmltv.xml` when empty.                    |

### Jellyfin

**Tip:** create a dedicated Jellyfin user with access only to **Movies** and **TV Shows**, or **Anime**, then use that user’s API key here. It’s simpler and safer than pointing the mod at your admin account. Put Jellyfin behind a tunnel (Cloudflare Tunnel or similar) and use that HTTPS URL here — do not publish port 8096.

| Key                                   | Default | Meaning                                   |
| ------------------------------------- | ------- | ----------------------------------------- |
| `jellyfinUrl`                         | `""`    | Server URL (often port `8096`).           |
| `jellyfinApiKey`                      | `""`    | API key.                                  |
| `jellyfinUserId`                      | `""`    | Optional user id.                         |
| `jellyfinMoviesEnabled`               | `true`  | Show movies.                              |
| `jellyfinTvShowsEnabled`              | `true`  | Show TV series.                           |
| `jellyfinLibraryIds`                  | `[]`    | Limit to specific libraries; empty = all. |
| `jellyfinAutoplayNextEpisode`         | `true`  | Autoplay next episode.                    |
| `jellyfinLibraryCacheSeconds`         | `600`   | Library cache lifetime.                   |
| `jellyfinProgressReportSeconds`       | `30`    | Playback progress report interval.        |
| `jellyfinNextEpisodeCountdownSeconds` | `10`    | Countdown before next episode.            |

### Emby

**Tip:** create a dedicated Emby user with access only to **Movies** and **TV Shows**, or **Anime**, then use that user’s API key here. It’s simpler and safer than pointing the mod at your admin account. Tunnel Emby the same way as Jellyfin — do not publish it on a public IP.

| Key                       | Default | Meaning                                   |
| ------------------------- | ------- | ----------------------------------------- |
| `embyUrl`                 | `""`    | Server URL.                               |
| `embyApiKey`              | `""`    | API key.                                  |
| `embyUserId`              | `""`    | Optional user id.                         |
| `embyMoviesEnabled`       | `true`  | Show movies.                              |
| `embyTvShowsEnabled`      | `true`  | Show TV series.                           |
| `embyLibraryIds`          | `[]`    | Limit to specific libraries; empty = all. |
| `embyLibraryCacheSeconds` | `600`   | Library cache lifetime.                   |

### Plex

**Tip:** prefer a Plex account/user that only has access to **Movies**, **TV Shows**, and/or **Anime**, then use that account’s token here instead of a full admin setup. Tunnel Plex (Cloudflare Tunnel, Tailscale, etc.) instead of opening port 32400 to the world.

#### How to get `plexToken`

1. Open your Plex web app and sign in.
2. Click any movie or TV show (for example *American Dad*).
3. Click the **three dots** → **Get Info** → **View XML**.
4. In the address bar of the XML page, find `X-Plex-Token=` — the value after it is your Plex token.
5. Paste that value into `plexToken` in the in-game GUI.

| Key                       | Default | Meaning                                   |
| ------------------------- | ------- | ----------------------------------------- |
| `plexUrl`                 | `""`    | Server URL (often port `32400`).          |
| `plexToken`               | `""`    | Plex token (see steps above).             |
| `plexMoviesEnabled`       | `true`  | Show movies.                              |
| `plexTvShowsEnabled`      | `true`  | Show TV series.                           |
| `plexLibraryKeys`         | `[]`    | Limit to specific libraries; empty = all. |
| `plexLibraryCacheSeconds` | `600`   | Library cache lifetime.                   |

**API keys and tokens stay on the Minecraft server.** They are not written into world saves, not synced with chunks, and not sent to clients. VLC never talks to Jellyfin, Emby, Plex, or Tunarr directly.

### Media proxy

| Key                    | Default   | Meaning |
| ---------------------- | --------- | ------- |
| `mediaProxyPort`       | `25567`   | HTTP port clients use for video, artwork, and subtitles. `0` picks an ephemeral port. Dedicated servers must open this next to 25565. |
| `mediaProxyPublicHost` | `""`      | Hostname clients should use for that port. Empty means “the same host I used to join Minecraft.” Set this for NAT or a reverse proxy. |

If the proxy cannot bind, screens stay blank rather than falling back to leaking a keyed URL.

### Other playback keys

| Key                        | Default | Meaning |
| -------------------------- | ------- | ------- |
| `maximumPlaybackDistance`  | `96`    | How far a player can be from a screen and still receive video. |
| `maxSimultaneousChannels`  | `2`     | How many different streams one client will decode at once. |
| `globalTvVolume`           | `1.5`   | Client-wide volume multiplier. |
| `defaultDisplayVolume`     | `1.0`   | New screens start at this volume. |
| `audioZoneDefaultWidth`    | `30`    | Default room size for `/tv zone here` (blocks). |
| `audioZoneDefaultDepth`    | `35`    | |
| `audioZoneDefaultHeight`   | `12`    | |
| `audioZoneEdgeFadeBlocks`  | `0`     | Soft fade at zone edges. `0` is full volume anywhere inside (recommended for theaters). |

## Permissions

Every permission key takes one of:

- `everyone` — anybody (the default for player-facing keys).
- `none` — nobody.
- `op` — server operators only. This is **Minecraft operator status**, not a pixelReel rank. Do not `/op` people just so they can watch a movie.
- a comma-separated list of player names or UUIDs, e.g. `alice,bob,9f1a…`. `op` may appear as one entry: `op,alice,bob`.

**You never have to op a player to give them access.** If you want rank-based control, install
[fabric-permissions-api](https://github.com/lucko/fabric-permissions-api) plus a permission manager such as
LuckPerms and grant the nodes below. pixelReel detects that API automatically; an explicit node beats the config
rule. Leave a node unset and the config rule applies as normal.

| Node                           | Config key                     | Default    | Meaning |
| ------------------------------ | ------------------------------ | ---------- | ------- |
| `pixelreel.browse`             | `permissionBrowse`             | `everyone` | Open the media menu and list channels. |
| `pixelreel.play.tunarr`        | `permissionPlayTunarr`         | `everyone` | Tune live channels. |
| `pixelreel.play.movies`        | `permissionPlayJellyfinMovies` | `everyone` | Play movies from any on-demand provider. |
| `pixelreel.play.shows`         | `permissionPlayJellyfinShows`  | `everyone` | Play shows from any on-demand provider. |
| `pixelreel.control`            | `permissionControlPlayback`    | `everyone` | Pause, seek, stop, change content, volume. Also gates `/tv` for those actions. |
| `pixelreel.posters`            | `permissionPlacePosters`       | `everyone` | Hang posters. |
| `pixelreel.posters.url`        | `permissionCustomPosterUrls`   | `everyone` | Hang a poster pointing at an arbitrary web address. |
| `pixelreel.watch`              | `permissionWatch`              | `everyone` | Receive proxied video/artwork for nearby screens. No API keys are included. |
| `pixelreel.hosts`              | `permissionSeeMediaHosts`      | `op`       | See media-server hosts in `/tv status` and error text. |
| `pixelreel.configure.tunarr`   | `permissionConfigureTunarr`    | `op`       | Edit the Tunarr connection. |
| `pixelreel.configure.jellyfin` | `permissionConfigureJellyfin`  | `op`       | Edit the Jellyfin connection. |
| `pixelreel.configure.emby`     | `permissionConfigureEmby`      | `op`       | Edit the Emby connection. |
| `pixelreel.configure.plex`     | `permissionConfigurePlex`      | `op`       | Edit the Plex connection. |
| `pixelreel.library.refresh`    | `permissionRefreshLibrary`     | `op`       | Force a library re-scan. |
| `pixelreel.audiozone`          | `permissionConfigureAudioZone` | `op`       | Define theatre audio zones. |

`posterUrlHostAllowlist` (default `[]`) is a host list, not a permission. Empty means any **public** host. Custom poster URLs still cannot point at loopback, LAN, link-local, or cloud-metadata addresses.

With LuckPerms, giving a whole rank the ability to watch — with zero admin powers — looks like:

```text
/lp group member permission set pixelreel.watch true
```

### What players cannot see

Older builds embedded `api_key` / `X-Plex-Token` in the stream URL and handed that URL to every nearby client. That is gone.

- VLC talks only to the Minecraft media proxy. The proxy is the only process that contacts Jellyfin, Emby, Plex, or Tunarr with the real key.
- Stream URLs, subtitle URLs, Plex part keys, and library artwork URLs are not written to the world save and are not included in chunk sync. Hung library posters store the provider and item id; the image is rebuilt and proxied when a client is in range.
- Channel stream URLs are omitted for players without `permissionWatch`. Channel logos are proxied so the media host is not in the playlist packet.
- `/tv status` and error text redact media hosts unless the player has `permissionSeeMediaHosts` (default `op`).
- `/tv` uses the same gates as the GUI. Console and command blocks stay unrestricted.

On a public server, narrow `permissionWatch` to a LuckPerms group or a name list if you do not want every visitor to receive video. Players outside that list see screens and posters as blank rather than erroring. Treat `config/pixelreel.json` as a secret.

## Displays

| Block                | Size    | Audio range |
| -------------------- | ------- | ----------- |
| Compact Television   | 3 × 2   | 16          |
| Wall Television      | 6 × 4   | 24          |
| Ultrawide Monitor    | 8 × 4   | 24          |
| Cinema Screen        | 14 × 8  | 80          |
| Curved Cinema Screen | 16 × 7  | 80          |
| Giant Cinema Screen  | 20 × 10 | 100         |

A curved or giant cinema screen is the best fit for a home theater. Pixel Glasses give a fullscreen overlay of a nearby playing screen.

All displays are craftable, appear in the **pixelReel** creative tab, and are findable by searching *television*, *TV*, *screen*, *cinema*, or *monitor*.

**Pixel Glasses** are also craftable: wear them for a fullscreen overlay of a nearby playing screen (HUD hotbar/crosshair hidden while active).

Only the active screen area has collision — bezels stay buildable so you can frame screens with your own blocks. Breaking the center/controller removes the whole display; player builds are never touched.

- **Right-click** a display: open the media menu (live TV, Jellyfin, Emby, or Plex).
- **Sneak + right-click**: toggle power.
- Video is letterboxed/pillarboxed to the screen aspect (never stretched); it keeps its original aspect ratio.

## Wall posters

**Movie Poster** (2×3) and **Huge Movie Poster** (tall 2:3, up to 10×15) are craftable painting-style decorations for lobbies and hallways. Use **Huge Movie Poster** for lobby-scale art. It picks the largest 2:3 hang that fits the wall (10×15, 8×12, 7×10, 6×9, or 4×6) so the whole artwork is shown.

- **Use the item on a wall**: click anywhere on a clear stretch of wall. The poster slides to fit (you do not have to hit the exact bottom-left corner) and the poster picker opens.
- **Right-click a hung poster**: reopen the picker to swap its artwork; each hanging keeps its own title.
- **Blank Poster** in the picker clears the artwork again; breaking any part of a poster takes the whole thing down and drops the item.
- Artwork is fitted inside the frame without cropping.
- Craft four Movie Posters in a square to make one Huge Movie Poster.

Artwork comes from the same libraries as playback — Jellyfin, Emby, and Plex — plus in-game **Watching** and **Custom** tabs. You never have to quit the game or drop files into `config/pixelreel-posters`.

- **Movies / TV Shows**: search the library and hang that artwork. TV shows use the **series** poster, not episode stills.
- **Watching**: pick a nearby screen (within 48 blocks). The poster follows that TV — movies, live channels, and the main series poster for shows. Hang one poster per screen if several are playing.
- **Custom**: paste an image or GIF URL, or click **Choose File…** (a file dialog opens over Minecraft). png, jpg, and animated gif uploads are stored in the world save so everyone on the server sees them.

Library posters store the provider and item id (not a keyed image URL), so they survive relogs and world backups. Every client fetches the picture through the media proxy. An unreachable media server just leaves the poster blank instead of erroring.

Placement is gated by `permissionPlacePosters` (default `everyone`). Custom HTTP URLs are additionally gated by `permissionCustomPosterUrls` and must resolve to a publicly routable host — loopback, LAN, link-local, and cloud-metadata addresses are rejected on both the server and the client. Use `posterUrlHostAllowlist` to narrow it to specific image hosts.

## Commands

Most screen actions use these commands (look at a display when required):

```text
/tv menu                 open the media menu
/tv channels             list live channels
/tv channel <n or name>  tune a live channel
/tv next | previous      change channel
/tv guide                now/next programme overview
/tv status               power, channel, and volume diagnostics (media host hidden unless you can see hosts)
/tv retry                restart playback
/tv reload               re-download playlist/guide (library refresh permission)
/tv stop | resume        pause/resume playback
/tv power on|off|toggle  power control
/tv volume <0-100>       per-display volume
/tv rebuild              rebuild screen collision without touching builds
/tv zone claim|mark|set|here|clear|status|cancel
/tv jellyfin status|refresh|configure
```

Every subcommand honours the same permission keys as the GUI, so restricting `permissionControlPlayback` (for
example) blocks `/tv stop` as well as the stop button. The server console and command blocks are always allowed.

## Multiplayer

Display state (type, power, channel/media, facing, volume, playback position) is **server-auth** and synced to every client, including players who join later.

Each client decodes the stream locally with its own VLC. VLC talks to the Minecraft server's media proxy, not to Jellyfin, Emby, Plex, or Tunarr. Credentials are never stored in world data and never sent to clients.

For a public Minecraft server: open `mediaProxyPort`, keep `config/pixelreel.json` off backups you share, set play/watch/control permissions to a LuckPerms group or a name list rather than making people operators, and **tunnel Jellyfin / Emby / Plex / Tunarr** (Cloudflare Tunnel or a VPN) so those services are never reachable on a public IP. See the disclaimer under [Installing](#installing).

## Roadmap

### What we have **Now**

What already works in this **1.21.1** build:

- [x] NeoForge mod targeting Minecraft **1.21.1**
- [x] Six display sizes (Compact TV, Wall TV, Ultrawide, Cinema, Curved Cinema, Giant Cinema)
- [x] Live TV via Tunarr / M3U + XMLTV guide
- [x] Jellyfin, Emby, and Plex integration
- [x] In-game provider config GUIs (Tunarr, Jellyfin, Emby, Plex)
- [x] Poster-based movie & TV browse UI
- [x] Playback controls (power, pause/resume, volume, channel/media select)
- [x] `/tv` command suite for screen control, audio zones, and diagnostics
- [x] Server-authoritative multiplayer sync (late-join included)
- [x] Client-side VLC decode with positional audio
- [x] Media proxy so API keys and Plex tokens never reach clients or world saves
- [x] Permission nodes (LuckPerms-compatible string nodes, or operator / name lists) without requiring operator status
- [x] Subtitles, letterboxing, and HDR tone mapping
- [x] Pixel Glasses fullscreen overlay
- [x] Wall posters (2×3 and huge 2:3) with library art, currently-watching bound to a chosen screen, in-game custom images/GIFs
- [x] Craftable displays + creative tab



### Upcoming


| Feature                             | Status  | Priority | Notes                                                                                                                                                    |
| ----------------------------------- | ------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Admin remote control**            | Planned | High     | Item or GUI usable from anywhere (not only at the screen). Admins can start, pause, rewind/seek, pick content, and open config for any display.          |
| **Personalized screens**            | Planned | High     | Per-player private viewing — each player can have their own channel/title on a shared or personal display without forcing everyone onto the same stream. |
| **Movie scheduler**                 | Planned | Medium   | Queue showtimes (date/time + title or channel). Auto power-on, start playback, and optional lobby announcements for cinema nights.                       |
| **YouTube / streaming integration** | Planned | Medium   | Play YouTube (and possibly other stream sources) on displays alongside Tunarr and media servers. Exact providers TBD.                                    |
| **NeoForge support**                | Done    | High     | First-class NeoForge 1.21.1 build.                                                                                                                       |
| **Forge support**                   | Planned | Medium   | Forge port after NeoForge, depending on version demand.                                                                                                  |


Ideas and PRs welcome — especially for loaders, version ports, and the admin remote.

## License

This project is available under the [CC0 1.0](LICENSE) license.

DISCORD: https://discord.gg/RSWQuEnMj
