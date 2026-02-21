<div align="center">
<img src="fastlane/metadata/android/en-US/images/icon.png" width="160" height="160" style="display: block; margin: 0 auto"/>
<h1>Meld</h1>
<p>A music client that fuses Spotify and YouTube Music into one seamless experience</p>

[![Latest release](https://img.shields.io/github/v/release/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases)
[![GitHub license](https://img.shields.io/github/license/FrancescoGrazioso/Meld?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/FrancescoGrazioso/Meld/total?style=for-the-badge)](https://github.com/FrancescoGrazioso/Meld/releases)

</div>

## What is Meld?

**Meld** is an Android music client that brings together the best of Spotify and YouTube Music. It uses your Spotify account to power personalized recommendations, search, and home content — while streaming audio through YouTube Music.

The name "Meld" reflects the core idea: **melding** two music platforms into a single, unified listening experience.

### Why Meld?

- **Spotify's personalization** — Your top tracks, favorite artists, and curated playlists from Spotify drive the recommendations
- **YouTube Music's catalog** — Access YouTube Music's vast library for streaming, including rare tracks, live performances, and remixes
- **No Spotify Premium required** — Meld uses Spotify's data APIs (not streaming), so a free Spotify account is all you need
- **Built-in recommendation engine** — A custom algorithm builds personalized queues using your Spotify listening history, without relying on deprecated API endpoints

## Features

### Spotify Integration
- **Spotify as search source** — Search results powered by Spotify, with automatic YouTube Music matching for playback
- **Spotify as home source** — Home screen populated with your Spotify top tracks, top artists, playlists, and new releases
- **Smart queue generation** — Custom recommendation engine that builds radio-like queues from your Spotify taste profile (top tracks/artists across 3 time ranges, genre similarity, popularity matching)
- **Spotify library sync** — Access your Spotify playlists and liked songs directly in the app
- **Spotify-to-YouTube matching** — Fuzzy matching algorithm with local caching for fast, accurate track resolution

### Core Music Features
- Play any song or video from YouTube Music
- Background playback
- Personalized quick picks
- Library management
- Listen together with friends
- Download and cache songs for offline playback
- Search for songs, albums, artists, videos and playlists
- Live lyrics
- YouTube Music account login support
- Syncing of songs, artists, albums and playlists, from and to your account
- Skip silence
- Import playlists
- Audio normalization
- Adjust tempo/pitch
- Local playlist management
- Reorder songs in playlist or queue
- Home screen widget with playback controls
- Light / Dark / Black / Dynamic theme
- Sleep timer
- Material 3 design
- Discord Rich Presence

## Download

<div align="center">
<table>
<tr>
<td align="center">
<a href="https://github.com/FrancescoGrazioso/Meld/releases/latest/download/Meld.apk"><img src="https://github.com/machiav3lli/oandbackupx/blob/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png" alt="Get it on GitHub" height="82"></a>
</td>
<td align="center">
<a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/FrancescoGrazioso/Meld/"><img src="https://github.com/ImranR98/Obtainium/blob/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="50"></a>
</td>
</tr>
</table>
</div>

## How the Spotify Integration Works

Meld connects to your Spotify account via OAuth2 (PKCE flow) to access your listening data. Here's what happens under the hood:

1. **Authentication** — You log in with your Spotify account. Meld only requests read access to your library, top items, and playlists.
2. **Home screen** — When "Use Spotify for Home" is enabled, Meld fetches your top tracks (short/medium/long term), top artists, playlists, and new releases to build a personalized home feed.
3. **Search** — When "Use Spotify for Search" is enabled, search queries go to Spotify's API. Results are displayed as Spotify content; clicking a song resolves it to YouTube Music for playback.
4. **Queue generation** — When you play a Spotify-sourced song, Meld's recommendation engine builds a queue by:
   - Fetching top tracks from the song's artists
   - Finding genre-similar artists from your taste profile
   - Mixing in tracks from your personal top tracks pool
   - Scoring candidates by artist affinity, genre overlap, popularity, and recency
   - Diversifying the queue to avoid monotony
5. **Playback** — Each Spotify track is matched to its YouTube Music equivalent using fuzzy title/artist/duration matching, then streamed via YouTube Music's infrastructure.

## Setup

### Spotify Integration

1. Create a Spotify app at [developer.spotify.com](https://developer.spotify.com/dashboard)
2. Add `meld://spotify/callback` as a Redirect URI in your app settings
3. In Meld, go to **Settings → Integrations → Spotify** and enter your Client ID
4. Log in with your Spotify account
5. Enable "Use Spotify for Search" and/or "Use Spotify for Home" as desired

### GitHub Secrets (for building from source)

If building from source with GitHub Actions:

1. Go to your fork's repository settings
2. Navigate to **Settings → Secrets and variables → Actions**
3. Add the following secrets:
   - `LASTFM_API_KEY`: Your Last.fm API key
   - `LASTFM_SECRET`: Your Last.fm secret key
4. Get your Last.fm API credentials from: https://www.last.fm/api/account/create

## FAQ

### Q: Why isn't Meld showing in Android Auto?

1. Go to Android Auto's settings and tap multiple times on the version in the bottom to enable developer settings
2. In the three dots menu at the top-right of the screen, click "Developer settings"
3. Enable "Unknown sources"

### Q: Do I need Spotify Premium?

No. Meld uses Spotify's Web API for data (your library, top tracks, search results) — not for audio streaming. A free Spotify account works perfectly.

### Q: Why do some songs not match correctly?

The Spotify-to-YouTube matching uses fuzzy matching on title, artist name, and duration. In rare cases (live versions, remasters, regional variants), the match may not be perfect. Matched results are cached locally so they're resolved instantly on subsequent plays.

## Credits

Meld is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist), originally created by [Mo Agamy](https://github.com/mostafaalagamy).

### Upstream Projects

- **InnerTune** — [Zion Huang](https://github.com/z-huang) · [Malopieds](https://github.com/Malopieds)
- **OuterTune** — [Davide Garberi](https://github.com/DD3Boh) · [Michael Zh](https://github.com/mikooomich)

### Libraries and Integrations

- [**Kizzy**](https://github.com/dead8309/Kizzy) — Discord Rich Presence implementation
- [**Better Lyrics**](https://better-lyrics.boidu.dev) — Time-synced lyrics with word-by-word highlighting
- [**SimpMusic Lyrics**](https://github.com/maxrave-dev/SimpMusic) — Lyrics data through the SimpMusic Lyrics API
- [**metroserver**](https://github.com/MetrolistGroup/metroserver) — Listen Together implementation
- [**MusicRecognizer**](https://github.com/aleksey-saenko/MusicRecognizer) — Music recognition and Shazam API integration

## Disclaimer

This project and its contents are not affiliated with, funded, authorized, endorsed by, or in any way associated with YouTube, Google LLC, Spotify AB, or any of their affiliates and subsidiaries.

Any trademark, service mark, trade name, or other intellectual property rights used in this project are owned by the respective owners.
