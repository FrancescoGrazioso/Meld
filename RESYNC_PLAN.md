# Meld ← Metrolist resync plan (v13.1.1 → v13.7.0)

Branch: `chore/metrolist-resync-v13.7.0` (from `main` @ `8e23bc3d5`)

---

## 1. Why the previous sync attempt failed

`git merge-base main upstream/main` returns **nothing**. The two repositories share no
common ancestor, which is why every sync attempt looked like a full-tree rewrite.

The cause is not Meld: **MetrolistGroup/Metrolist rewrote its git history** between
`v13.6.0` (2026-06-21) and `v13.6.1` (2026-07-17). The current `upstream/main` has only
384 commits, all dated 2026-06-24 or later, with two synthetic roots.

The old history is *not* lost — it is still reachable locally through the release tags
that were fetched with `git fetch upstream --tags`:

| Tag | Date | Shares ancestry with Meld `main`? |
|---|---|---|
| `v13.1.1` … `v13.6.0` | 2026-02-19 → 2026-06-21 | **yes**, merge-base = `2d4b922ae` |
| `v13.6.1` … `v13.7.0` | 2026-07-17 → 2026-09-07 | no (rewritten history) |

And the seam is provably narrow:

```
git diff --shortstat v13.6.0 17c6e658b   # upstream's new-history root
 47 files changed, 1405 insertions(+), 1075 deletions(-)
```

So the real topology is a single chain with one cut, which we can stitch back together:

```
2d4b922ae  (fork point — Metrolist, 2026-02-27)
   ├── main .............................................. Meld
   └── v13.2.0 → … → v13.6.0 ──[stitch]── 17c6e658b → … → upstream/main (v13.7.0)
```

## 2. Where Meld actually forked

`2d4b922ae` (*fix(lyrics): remove parentheses and brackets…*, 2026-02-27) is the newest
commit in Meld's history whose tree contains **zero** Spotify/Qobuz/Meld files. Git
independently confirms it: it is the merge-base between `main` and every old Metrolist
release tag.

Meld then re-synced once more, in release **v0.6.5** (`fc4146253`, 2026-04-15), which
squashed upstream into a single commit. Measuring tree distance shows exactly how far
that sync went:

| Metrolist release | `git diff --shortstat <rel> main` |
|---|---|
| v13.3.0 | 410 files, 61055 (+) |
| **v13.4.0** | **304 files, 38818 (+)** ← minimum |
| v13.4.1 | 384 files, 35084 (+) |
| v13.6.0 | 570 files, 39210 (+) |
| v13.7.0 | 720 files, 55938 (+) |

**Meld's content baseline is Metrolist v13.4.0 (2026-04-01).** Everything from v13.4.1
onward is genuinely missing.

## 3. File-level divergence (base `2d4b922ae` → `main` vs → `upstream/main`)

| | files |
|---|---|
| Meld-only new files (Spotify/Qobuz/branding/CI) | 125 |
| Both sides added the same path | 52 |
| Both sides modified — real conflict surface | 241 |
| Meld modified, upstream **deleted** | 22 |
| Meld modified only | 23 |
| Upstream modified only (take theirs) | 93 |
| Upstream-only new files (take theirs) | 129 |

A single `main` × `v13.7.0` merge conflicts in **223 files**. Incremental merges are far
smaller: v13.4.0 → 101, v13.5.0 → 182, v13.6.0 → 189.

## 4. What upstream changed structurally (this is the hard part)

1. **Module collapse.** `kizzy`, `kugou`, `lastfm`, `lrclib`, `shazamkit`, `simpmusic`,
   `betterlyrics`, `paxsenix` are gone as Gradle modules; their code now lives under
   `app/src/main/kotlin/com/metrolist/…`. `settings.gradle.kts` is down to `:app` and
   `:innertube`.
2. **Playback rewritten.** `utils/YTPlayerUtils.kt` and the whole `utils/cipher/*` stack
   are **deleted**, replaced by the external library `com.github.MetrolistGroup.innertubex`
   (v0.5.2) plus a thin `utils/InnerTubeXPlayer.kt`.
3. **Discord rewritten.** `kizzy/*` → `music/discord/*` (14 new files).
4. **`metroproto` submodule dropped**; `listentogether.proto` is vendored in-tree.
5. New: equalizer wizard, playlist home-screen widget, `StreamUrlCache`, `ArtistPageCache`,
   Zemer lyrics provider, adaptive icons moved to `mipmap-anydpi-v26`, ~40 new unit tests.
6. Gradle wrapper 9.4.1 → 9.7.0.

## 5. Classification of Meld's commits since the fork

278 commits (107 human + 171 automated `chore(spotify): update GQL hashes`).

### A — Meld identity & features → **keep, port forward**

* **Spotify** (the bulk, ~40 commits): cookie + TOTP auth, GraphQL client with rotating
  hashes, `SpotifyHashProvider`/`SpotifyHashSync` + the daily hash-check workflow,
  liked songs, playlists & folders, albums, home sections, recommendation engine,
  playlist pinning, preload, track menu, YouTube matching & manual match override,
  Spotify-only home screen, Android Auto auth. Module `:spotify` + 40 `app` files.
* **Qobuz** lossless (v0.7.2): `QobuzAudioProvider`, `QobuzBackendHealthChecker`,
  `QobuzMatchOverride`, `QobuzMatchEntity`, `QobuzMatchOverrideDialog`.
* **Local files** playback + device scan (`LocalFilesScreen/ViewModel`, `song.localPath`).
* **New Releases** (`NewReleaseItem`), **Recently Played** home section.
* **SponsorBlock**, **Musixmatch lyrics**, **Mistral/OpenRouter** AI services.
* **Branding**: `applicationId = com.meld.app`, `app_name = Meld`, full mipmap icon set
  (foreground/background/monochrome, 5 densities), README, fastlane.
* **Infra**: `spotify-hash-check.yml`, `pr_title_prefix.yml`, `docs/spotify-gql-hashes.json`,
  Docker dev env, `notes` private submodule, `CrashReporter`, `AnrWatchdog`, unit tests.

### B — Bug fixes on Metrolist code → **re-check against v13.7.0, re-apply only if still open**

| Commit | Fix |
|---|---|
| `891c1b559` | schema 36 restore, `MIGRATION_21_24`/`22_24` missing columns, `contentLength!!` NPE, `codecs=` IndexOutOfBounds, player-init guards, `albumsUploaded` DAO WHERE bug |
| `c6222044f` | sync: batch all DB ops in one transaction |
| `76c7d692d` | audio focus + loudness enhancer |
| `013cec94c`, `8912bc3c9` | LastFM init + surface ignored scrobbles |
| `8b98a8c40` | low-quality album art in player |
| `2fc3d7144` | stop on task clear; Spotify liked-songs play order |
| `cc0a69e80` | stats crash, blurry art, ducking volume jump, boot autostart |
| `21a63b402`, `34911ab84` | shuffle whole playlist, not first batch |
| `994e5e39b`, `df125d002` | the 403 work — see C |

### C — Divergences superseded by upstream → **drop, adopt upstream**

* The entire **403 / cipher / player-config stack**: `Fix403.kt`, `utils/cipher/*`,
  `utils/sabr/*`, Meld's `YTPlayerUtils`, `assets/player_configs.json`,
  `assets/player_dates.json`. Upstream solves the same problem with `innertubex`.
  **Two generic defects from `df125d002` must be re-verified against the new code**, since
  they are independent of client selection:
  - `songUrlCache` branch not applying `.subrange()` → 403 on cached URLs;
  - resolver throwing `PlaybackException` (not an `IOException`), so media3 rewraps it and
    reports every failure as `IO_UNSPECIFIED` / "Unknown error".
* `:paxsenix` module → upstream's in-app `com/metrolist/paxsenix` (154/117 lines apart).
* `:betterlyrics`, `:kizzy`, `:kugou`, `:lastfm`, `:lrclib`, `:shazamkit`, `:simpmusic`
  modules → folded into `:app`.
* `metroproto` submodule → upstream's vendored proto.
* Meld's `MetrolistWidgetManager` perf rework and DiscordRPC/kizzy tweaks → upstream
  rewrote both areas.

## 6. Database — the highest-risk item

Schemas forked at v36. Current shapes:

| | Meld v39 | Upstream v38 |
|---|---|---|
| `song` | `+ localPath`, `+ isrc` | — |
| `artist` | `+ spotifyId` | `+ cachedPageJson` |
| `speed_dial_item` | — | `+ subtitleIds`, `+ albumId`, `+ albumName` |
| extra tables | `spotify_match`, `qobuz_match` | — |

`34.json` … `38.json` differ between the two trees, so upstream's exported schema chain
**cannot** be adopted wholesale: every existing Meld install is on Meld's v39 lineage and
would fail to migrate.

**Decision: Meld's schema lineage stays canonical.** Port upstream's *columns* forward as
a new **version 40** (`artist.cachedPageJson`, `speed_dial_item.subtitleIds/albumId/albumName`),
keep Meld's `1.json…39.json` untouched, add `40.json`, and cover it with a migration test.

## 7. Result

| check | result |
|---|---|
| `:app:assembleFossDebug` | green |
| `:app:assembleGmsDebug` | green |
| `:app:assembleIzzyDebug` | green |
| `:app:testFossDebugUnitTest` + `:innertube:test` | **214 tests, 0 failures** |
| `DatabaseMigrationLadderTest` | 6/6 — schema ladder contiguous, code version 40 matches the highest committed schema |
| `:app:lintFossDebug` | green |
| smoke test on a Pixel 10 emulator over an **existing v39 install** | see below |

The smoke test is the one that matters, because it exercised the riskiest decision — the
database — against real data rather than a fresh install:

```
DatabaseBackup: Database upgrade 39 -> 40, backing up first
DatabaseBackup: Backed up database to .../database_backups/song.db_backup_2026-09-10_14-09-42.db
MusicService: Player successfully initialized
MusicService: Google Cast initialized
Qobuz: endpoints configured | squid=… kenny=… trypt=… jumo=…
```

The library survived the migration (Recently Played and New Releases both render real
history), Qobuz configured its endpoints, and playback resolved and buffered a stream
through the new InnerTubeX path (`buffered position=5861, error=null`). No fatals, no ANRs.

Branch `chore/metrolist-resync-v13.7.0`:

```
fix(branding): restore discord_information_warning
fix(branding): re-assert Meld naming across the merged string resources
fix: restore the helpers the merged test suite needs, unbreak gms + tests
fix: port Meld's code onto the upstream v13.7.0 APIs
merge: Metrolist v13.7.0 into Meld
merge: record Metrolist v13.4.0 as merged, refresh translations
docs: add Metrolist resync plan
```

## 8. Execution order

* **Phase 0** — branch, green baseline build, `git rerere`, build the stitched upstream
  chain with `git commit-tree` (tree of v13.6.1/13.6.2/13.6.3/v13.7.0, each parented on the
  previous) so the rewritten history gets a real merge-base.
* **Phase 1** — merge `v13.4.0` (confirms Meld's existing baseline), then `v13.5.0`,
  then `v13.6.0`. Compile after each.
* **Phase 2** — merge the stitched chain: 13.6.1 → 13.6.2 → 13.6.3 → 13.7.0.
* **Phase 3** — structural reconciliation: fold `:spotify` into `app/`, drop `:paxsenix`
  and the other dead modules, adopt `innertubex`, re-wire `QobuzAudioProvider` onto
  `InnerTubeXPlayer`, re-verify the two defects from §5C.
* **Phase 4** — database: version 40 + migration test.
* **Phase 5** — identity: `com.meld.app`, `Meld` name, Meld icons, README/fastlane,
  version 0.9.0, merged workflows (keep `spotify-hash-check`, `pr_title_prefix`).
* **Phase 6** — verify: `assembleFossDebug` + `Gms` + `Izzy`, unit tests, lint, smoke test.

## 8. Decisions taken during the merge

Adopted from upstream (Meld's version dropped as superseded):

| Area | Decision |
|---|---|
| Stream resolution | `innertubex` + `InnerTubeXPlayer`; deleted `YTPlayerUtils`, `utils/cipher/*`, `Fix403` |
| `InnerTube`/`YouTube` | upstream's InnerTubeX facade (1786 → 3205 lines of API coverage) |
| Download URL cache | upstream's `StreamUrlCache` (bounded ranges + per-stream headers) |
| Discord | `music/discord/*`; deleted `utils/DiscordRPC.kt` and the `:kizzy` module |
| Protobuf | upstream's protobuf Gradle plugin; dropped the `metroproto` submodule and `.github/actions/setup-protobuf` |
| Modules | `settings.gradle.kts` down to `:app` + `:innertube`; `:spotify` folded into `app/src/main/kotlin/com/metrolist/spotify/`, `:paxsenix` replaced by upstream's in-app copy |
| Lyrics | registry-driven provider order, per-provider timeout; Musixmatch kept alongside upstream's Zemer |
| Equalizer, ListenTogether, PoToken, search screens, AppearanceSettings, ComposeToImage | upstream |
| Room | upstream's `BackupBeforeMigrationFactory` + non-destructive fallback |

Kept from Meld:

* Everything Spotify and Qobuz, plus local files, New Releases, Recently Played,
  SponsorBlock, Musixmatch, crash reporting, the GQL hash workflow.
* `applicationId com.meld.app`, `app_name Meld`, Meld icons, Meld release/issue URLs.
* Draft releases in `release.yml`, and the PR workflow's unit-test + lint gate.
* `ui/component/Preference.kt` — upstream replaced these primitives with
  `Material3SettingsGroup`, but Meld's own settings screens still use them.
* Room schema lineage (see §6); database version is now **40**.

## 9. Follow-ups

Done during the resync:

* **Incognito search** restored. `InnerTubeX.search` does take a nullable `setLogin`, so the
  facade forwards it and `YouTube.searchSummary(query, incognito = true)` works again —
  Spotify→YouTube matching still does not pollute the user's YouTube search history.
* **Updater**: upstream prefers a `Metrolist-KMP` release over its own and shows
  `kmp_upgrade_warning`. Meld has no KMP build and must not point users at another app, so
  that path is removed (`getLatestKmpRelease`, `parseKmpRelease`, and the `kmpUpdate` branch).
* **Schema 40** exported and verified: Meld's `spotify_match`/`qobuz_match`,
  `song.localPath`/`isrc`, `artist.spotifyId`, plus upstream's `artist.cachedPageJson` and
  `speed_dial_item.subtitleIds`/`albumId`/`albumName`.
* **Widget rendering**: Meld's CONFLATED-channel renderer dropped in favour of upstream's
  `pendingWidgetUpdate` loop — same fix for the same main-thread flooding problem.
* **Lyrics shaping**: Meld's `requiresShapedRendering` fallback dropped; upstream's animation
  walks grapheme clusters, which fixes the same Devanagari/Bengali issue (#141) properly.

Still open:

1. **Android Auto.** The merge kept Meld's Spotify sections in
   `MediaLibrarySessionCallback` and dropped upstream's YouTube-playlist browsing; worth
   having both.
2. **Settings look.** Meld's Spotify/Qobuz/SponsorBlock screens still use the old
   `Preference.kt` primitives and will look different from upstream's redesigned settings.
3. **Discord login route.** `settings/discord/login` was removed with Meld's screen; check
   nothing still navigates to it now that upstream uses `DiscordOAuthActivity`.
4. **`scripts/diagnose-yt-403.ps1`** still probes the IOS/ANDROID_VR/VISIONOS cascade and
   references `YTPlayerUtils`. Harmless (it is not compiled) but it now describes a stack
   that no longer exists — rewrite it against InnerTubeX or drop it.
5. **Playback in the field.** The two generic defects behind Meld's 403 work — a cached URL
   going out without a Range header, and the resolver throwing a non-`IOException` so media3
   reclassified every failure as "Unknown error" — are both structurally addressed by
   upstream's `StreamUrlCache` (bounded ranges, per-stream headers) and its typed
   `PlaybackException` mapping. Worth confirming on real content, especially
   age-restricted tracks, since that is where Meld's chain differed most.

## 10. Rules held throughout

1. Naming, icons and application id stay **Meld**; the Kotlin package stays
   `com.metrolist.music` (renaming it would break every merge from here on).
2. No Spotify or Qobuz capability is dropped.
3. Where upstream and Meld solve the same problem, upstream wins — unless Meld's version
   fixes something upstream still gets wrong, in which case the fix is re-applied on top of
   upstream's code, not instead of it.
