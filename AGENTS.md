# Working with Metrolist as an AI agent

Metrolist is a 3rd party YouTube Music client written in Kotlin. It follows material 3 design guidelines closely.

## Rules for working on the project

1. Always create a new branch for your feature work. Follow these naming conventions:
   - Bug fixes: `fix/short-description`
   - New features: `feat/short-description`
   - Refactoring: `ref/short-description`
   - Documentation: `docs/short-description`
   - Chores: `chore/short-description`
2. Branch descriptions should be concise yet descriptive enough to understand the purpose of the branch at a glance.
3. Always pull the latest changes from `main` before starting your work to minimize merge conflicts.
4. While working on your feature you should rebase your branch on top of the latest `main` at least once a day to ensure compatibility.
5. Commit names should be clear and follow the format: `type(scope): short description`. For example: `feat(ui): add dark mode support`. Including the scope is optional.
6. All string edits should be made to the `Metrolist/app/src/main/res/values/metrolist_strings.xml` file, NOT `Metrolist/app/src/main/res/values/strings.xml`. Do not touch other `strings.xml` or `metrolist_strings.xml` files in the project.
7. You are to follow best practices for Kotlin and Android development.

## AI-only guidelines

1. You are strictly prohibited from making ANY changes to the readme/markdown files, including this one. This is to ensure that the documentation remains accurate and consistent for all contributors.
2. You are NOT allowed to use the following commands:
   - You are not to commit, push, or merge any changes to any branch.
   - You should absolutely NOT use any commands that would modify the git history, do force pushes (except for rebases on your own branch), or delete branches without explicit instructions from a human.
3. Always follow the guidelines and instructions provided by human contributors.
4. Ensure the absolutely highest code quality in all contributions, including proper formatting, clear variable naming, and comprehensive comments where necessary.
5. Comments should be added only for complex logic or non-obvious code. Avoid redundant comments that simply restate what the code does.
6. Prioritize performance, battery efficiency, and maintainability in all code contributions. Always consider the impact of your changes on the overall user experience and app performance.
7. If you have any doubts ask a human contributor. Never make assumptions about the requirements or implementation details without clarification.
8. If you do not test your changes using the instructions in the next section, you will be faced with reprimands from human contributors and may be asked to redo your work. Always ensure that you test your changes thoroughly before asking for a final review.
9. You are absolutely **not allowed to bump the version** of the app in ANY way. Version bumps are only done by the core development team after manual review.

## Spotify GQL hash management

The app uses Spotify's internal GraphQL API (via `api-partner.spotify.com`). Each GQL operation requires a SHA-256 hash that Spotify rotates periodically. The hashes are defined in `spotify/src/main/kotlin/com/metrolist/spotify/Spotify.kt`.

### Remote hash registry

A GitHub Actions workflow (`.github/workflows/spotify-hash-check.yml`) runs daily at 06:00 UTC. It:
1. Fetches the current Spotify web player JS bundle
2. Extracts all operation→hash mappings
3. Compares them with `docs/spotify-gql-hashes.json`
4. If any hash has rotated: updates the JSON (moving the old hash to `previous_hash`), commits, and deploys to GitHub Pages

The live JSON is served at: `https://francescograzioso.github.io/Meld/spotify-gql-hashes.json`

### JSON structure

Each entry in `operations` has:
- `hash` — the current valid SHA-256 hash
- `previous_hash` — the last known working hash (fallback if `hash` fails with 412)
- `status` — `verified` (found in bundle) or `not_in_bundle` (may break without warning)
- `last_verified` / `last_changed` — timestamps

### Tracked operations

The JSON tracks **only the operations currently used by the app** in `Spotify.kt`. As of v0.6.0:

| Operation | App function(s) |
|---|---|
| `profileAttributes` | Profile info |
| `libraryV3` | `myPlaylists()`, `myArtists()` |
| `fetchPlaylist` | `playlist()`, `playlistTracks()` |
| `fetchLibraryTracks` | `likedSongs()` |
| `searchDesktop` | `search()` |
| `queryArtistOverview` | `artist()`, `artistTopTracks()`, `artistRelatedArtists()` |
| `getAlbum` | `album()` |
| `queryWhatsNewFeed` | `whatsNewFeed()` |
| `addToPlaylist` | `addToPlaylist()` |
| `removeFromPlaylist` | `removeFromPlaylist()` |
| `moveItemsInPlaylist` | `moveItemsInPlaylist()` |
| `editPlaylistAttributes` | `editPlaylistAttributes()` |
| `addToLibrary` | `addToLibrary()` — sync likes |
| `removeFromLibrary` | `removeFromLibrary()` — sync likes |

### When adding new GQL operations

If you add a new GQL operation to `Spotify.kt`, you **must** also add a corresponding entry in `docs/spotify-gql-hashes.json` so the daily checker tracks it. The entry format is:
```json
"operationName": {
  "hash": "<the 64-char sha256 hash you're using>",
  "previous_hash": null,
  "type": "query or mutation",
  "status": "verified",
  "last_verified": "<current ISO timestamp>",
  "last_changed": null
}
```

### Reference documents

- `notes/SPOTIFY_GQL_REFERENCE.md` — full documentation of all known GQL endpoints, variables, and hash history (private submodule, see below)
- `.github/scripts/check_spotify_hashes.py` — the hash checker script

## Private notes submodule

The `notes/` directory is a git submodule pointing to a private repository (`FrancescoGrazioso/meld-notes`). It contains:

- `ROADMAP_NOTES.md` — internal development roadmap and planning notes
- `SPOTIFY_GQL_REFERENCE.md` — full reference for all known Spotify GQL endpoints

The submodule is not populated by default when cloning. To access its contents (requires repo access):

```bash
git submodule update --init
```

If you do not have access to the private repo, the `notes/` directory will be empty. This is expected — do not attempt to recreate or modify its contents.

## Building and testing your changes

1. After making changes to the code, you should build the app to ensure that there are no compilation errors. Use the following command from the root directory of the project. Before building  ask the user if it's necessary or if he will build and test:

```bash
./gradlew :app:assembleFossDebug
```

2. If the build is not successful, review the error messages, fix the issues in your code, and try building again.
3. Once the build is successful, you can test your changes on an emulator or a physical device. Install the generated APK located at `app/build/outputs/apk/universalFoss/debug/app-universal-foss-debug.apk` and ask a human for help testing the specific features you worked on.
