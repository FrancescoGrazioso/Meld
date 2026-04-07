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

## Upstream Rebase Strategy

### Context

This repository is a fork of Metrolist that can fall hundreds of commits behind upstream. The fork contains custom features that **must never be lost or overwritten under any circumstances**. Previous merge and rebase attempts using `--theirs` have failed, either creating hundreds of conflicts or silently deleting custom features.

**The absolute priority is to preserve the maintainer's modifications. Time is not a constraint — a slow, meticulous process is always preferred over a fast, destructive one.**

### Phase 0 — Preparation and Analysis

1. **Create a backup branch before any operation:**
   ```bash
   git checkout -b backup-my-work main
   git checkout -b rebase-attempt main
   ```

2. **Identify ALL files modified in the fork** relative to the upstream divergence point:
   ```bash
   # Find the divergence point
   git merge-base main upstream/main

   # List all files touched in the fork
   git diff --name-only $(git merge-base main upstream/main)..main > my_modified_files.txt

   # Save the full diff as a reference
   git diff $(git merge-base main upstream/main)..main > my_full_diff.patch
   ```

3. **Save a physical backup of all modified files:**
   ```bash
   mkdir -p /tmp/my-meld-backup
   while read file; do
     if [ -f "$file" ]; then
       mkdir -p "/tmp/my-meld-backup/$(dirname "$file")"
       cp "$file" "/tmp/my-meld-backup/$file"
     fi
   done < my_modified_files.txt
   ```

4. **Enumerate upstream commits** to understand what they touch:
   ```bash
   git log --oneline $(git merge-base main upstream/main)..upstream/main > upstream_commits.txt
   git log --oneline --name-only $(git merge-base main upstream/main)..upstream/main > upstream_commits_with_files.txt
   ```

5. **Classify upstream commits into 3 categories:**
   - **SAFE**: commits that touch ONLY files NOT modified in the fork → safe to apply without review
   - **CONFLICTING**: commits that touch files modified in the fork → require manual attention
   - **SKIP**: purely cosmetic or irrelevant commits

   ```bash
   MERGE_BASE=$(git merge-base main upstream/main)

   for commit in $(git rev-list --reverse $MERGE_BASE..upstream/main); do
     files_in_commit=$(git diff-tree --no-commit-id --name-only -r $commit)
     has_conflict=false

     for file in $files_in_commit; do
       if grep -qF "$file" my_modified_files.txt; then
         has_conflict=true
         break
       fi
     done

     short=$(git log --oneline -1 $commit)
     if [ "$has_conflict" = true ]; then
       echo "CONFLICTING: $short" >> commit_classification.txt
     else
       echo "SAFE: $short" >> commit_classification.txt
     fi
   done
   ```

### Phase 1 — Apply SAFE Commits (Batch)

```bash
git checkout rebase-attempt

grep "^SAFE:" commit_classification.txt | while read line; do
  hash=$(echo "$line" | awk '{print $2}')
  echo "Applying SAFE commit: $line"
  git cherry-pick "$hash"

  if [ $? -ne 0 ]; then
    echo "UNEXPECTED ERROR on SAFE commit: $hash — stop and investigate"
    git cherry-pick --abort
    break
  fi
done
```

After every ~50 SAFE commits, verify the project still compiles.

### Phase 2 — Apply CONFLICTING Commits (One by One)

For each commit that touches files modified in the fork:

```bash
COMMIT_HASH="<hash>"

# 1. Understand what this commit does
git show $COMMIT_HASH --stat
git show $COMMIT_HASH

# 2. Identify which fork files are involved
git diff-tree --no-commit-id --name-only -r $COMMIT_HASH | while read f; do
  grep -qF "$f" my_modified_files.txt && echo "⚠️  CONFLICT: $f"
done

# 3. Attempt the cherry-pick
git cherry-pick $COMMIT_HASH
```

**Hard rules for conflict resolution:**

1. **Section modified by the maintainer**: ALWAYS keep the fork's version. The upstream change in that section is discarded.
2. **Section NOT modified by the maintainer** (but the file is in the list because other parts were touched): accept the upstream version.
3. **New code added by upstream** (new functions, imports, classes) in a modified file: integrate the new upstream code, but do not touch the custom sections.
4. **Renamed/moved files that were modified**: follow the rename/move and carry the custom modifications into the renamed file.
5. **When in doubt**: keep the fork's version and flag the commit for manual review.

After each resolution:

```bash
# Verify custom modifications are still intact
diff /tmp/my-meld-backup/<file> <file>
# Differences should be ONLY upstream additions, NEVER removals of custom code

git add <file>
git cherry-pick --continue
```

### Phase 3 — Final Verification

```bash
# Verify all custom modifications are still present
while read file; do
  if [ -f "$file" ] && [ -f "/tmp/my-meld-backup/$file" ]; then
    echo "=== Checking: $file ==="
    diff "/tmp/my-meld-backup/$file" "$file"
  fi
done < my_modified_files.txt

# Verify new upstream commits are present
git log --oneline rebase-attempt | head -20
```

### Phase 4 — Error Recovery

```bash
git cherry-pick --abort                        # if mid cherry-pick
git checkout backup-my-work                    # return to backup
git branch -D rebase-attempt                   # delete the failed attempt
git checkout -b rebase-attempt backup-my-work  # start over
```

### AI Agent Rules for Upstream Rebases

- **Never use `git checkout --theirs` or `--ours` on entire files** without first verifying whether they contain custom maintainer modifications.
- **Do not use native `git rebase`** — use cherry-pick commit by commit for maximum control.
- **After every 20–30 commits**, save the current hash and log progress to `rebase_progress.log` so the work can be resumed if the session is interrupted.
- **If a single cherry-pick produces more than 5 conflicts in custom files**: stop, report it, and wait for instructions before proceeding.
- **Progress log**: maintain a `rebase_progress.log` file recording each applied commit, its status (SAFE/RESOLVED/SKIPPED), and any relevant notes.
- Speed is never the goal. It is better to spend 10 hours and produce a perfect result than to risk losing even a single line of custom code.