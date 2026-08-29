# Feature request: ListenBrainz scrobbling support (alternative to Last.fm)

## Summary

Meld already supports Last.fm scrobbling (playback + now-playing), which is great. However, Last.fm requires an API account and is a closed, ad-supported service.

**ListenBrainz** (by MetaBrainz, the MusicBrainz team) is the open, free, privacy-respecting alternative:

- Free, open source, no ads, no tracking
- Public API, simple auth (Bearer token — simpler than Last.fm's signature flow)
- Open data: users keep full ownership of their listening history
- Growing ecosystem: multi-scrobbler, Airbuds, Pano Scrobbler already support it

## Proposed feature

Add ListenBrainz as an optional scrobbling provider alongside Last.fm:

- Settings → Integrations → ListenBrainz section (token validation + enable toggle)
- Scrobble + "now playing" submission using the LB API (`POST /1/submit-listens`)
- Users choose Last.fm, ListenBrainz, or both

## Why it fits Meld

- The scrobbling plumbing already exists (`ScrobbleManager` + `LastFmScrobbleClient` interface) — ListenBrainz support is a parallel client implementing the same interface
- The LB API is simpler than Last.fm's (single endpoint, Bearer token, no MD5 signing)
- Aligns with the open-source spirit of the project (GPL-3.0): users avoiding proprietary services get a fully open metadata layer
- Enables users in regions where Spotify/Last.fm are restricted (e.g., Russia — Yandex Music users) to keep their scrobbling history

## Reference

- ListenBrainz API: https://listenbrainz.readthedocs.io/en/latest/submission_api.html
- `POST /1/submit-listens` (single listen + playing_now), `GET /1/validate-token`

I'm happy to work on a PR if the maintainer agrees with adding it — the implementation would follow the existing `LastFmScrobbleClient` interface pattern.