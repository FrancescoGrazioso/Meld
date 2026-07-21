# Contributing to Meld

Meld is an Android/Kotlin music client based on Jetpack Compose and Material 3. This guide describes the recommended local setup and validation flow for contributors.

## 1. Requirements

Recommended:

- Git
- Docker and Docker Compose

Optional local setup, if you do not use Docker:

- JDK 21
- Android Studio with Android SDK Platform 36
- Android SDK Build-Tools 36.0.0
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- `protoc` 3.21 or newer, with `protoc` 34.0 matching CI

The Gradle wrapper is committed, so installing Gradle globally is not required.

## 2. Branch Workflow

Start from an up-to-date `main` branch:

```bash
git checkout main
git pull --ff-only origin main
```

Create a dedicated branch:

```bash
git checkout -b feat/short-description
```

Branch prefixes:

- `fix/` for bug fixes
- `feat/` for new features
- `ref/` for refactoring
- `docs/` for documentation
- `chore/` for maintenance work

Commit messages should follow:

```text
type(scope): short description
```

Examples:

```text
feat(player): add queue shuffle action
fix(spotify): handle empty playlist images
chore(build): update docker dev environment
```

## 3. Docker Development Environment

Build the development image:

```bash
docker compose build dev
```

Open a shell in the container:

```bash
docker compose run --rm dev bash
```

The container includes:

- JDK 21
- Android SDK Platform 36
- Android Build-Tools 36.0.0
- Android Platform-Tools
- `protoc` 34.0
- Git and basic shell tools

Gradle and Android user caches are stored in Docker volumes so repeated builds do not download everything again.

## 4. Initial Project Setup

Initialize the public protobuf submodule:

```bash
git submodule update --init metroproto
```

Generate protobuf sources:

```bash
cd app
bash generate_proto.sh
cd ..
```

The private notes submodule is optional and requires repository access:

```text
notes/
```

Do not recreate the private notes contents if the submodule is empty.

## 5. Optional Debug Keystore

Debug builds can use the default Android debug keystore. If you need a stable project-local debug keystore, generate one with:

```bash
keytool -genkeypair -v \
  -keystore app/persistent-debug.keystore \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

This file is ignored by Git.

## 6. Optional Local Secrets

Most development builds work without secrets. Some integrations may be disabled or incomplete unless credentials are provided.

To configure local secrets, copy the sample file and fill in only the values you need:

```bash
cp local.properties.sample local.properties
```

`local.properties` is ignored by Git. You can also export these values as environment variables instead:

```properties
LASTFM_API_KEY=...
LASTFM_SECRET=...
CRASH_REPORT_REPO=francescograzioso/Meld
CRASH_REPORT_TOKEN=...
```

Release signing secrets are maintained by the project maintainers and are not needed for normal contribution work.

## 7. Build Variants

Main app variants:

- `foss`: default contribution target, no Google Play Services cast support
- `gms`: includes Google Cast support
- `izzy`: store-oriented variant with updater disabled

Use `fossDebug` for normal development unless your change specifically targets Google Cast or IzzyOnDroid behavior.

## 8. Validation Commands

Run the same fast checks used by pull request CI:

```bash
./gradlew --console=plain \
  :app:testFossDebugUnitTest \
  :spotify:test \
  :betterlyrics:test \
  --warning-mode summary
```

Build and lint the default debug APK:

```bash
./gradlew --console=plain \
  assembleFossDebug \
  :app:lintFossDebug \
  --warning-mode summary
```

The debug APK is generated at:

```text
app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

With Docker, prefix the Gradle command:

```bash
docker compose run --rm dev ./gradlew --console=plain assembleFossDebug
```

To only build the APK needed for manual testing, run:

```bash
docker compose run --rm dev ./gradlew --console=plain :app:assembleFossDebug --warning-mode summary
```

## 9. APK Types

For day-to-day testing, use the debug APK:

```bash
docker compose run --rm dev ./gradlew --console=plain :app:assembleFossDebug --warning-mode summary
```

The debug APK is signed with a local Android debug keystore, has `BuildConfig.DEBUG = true`, and is meant for fast install/test cycles. In this project it uses the debug application id suffix, so it can usually be installed next to a release build.

To generate a release APK without maintainer signing keys, run:

```bash
docker compose run --rm dev ./gradlew --console=plain :app:assembleFossRelease --warning-mode summary
```

The unsigned release APK is generated at:

```text
app/build/outputs/apk/foss/release/app-foss-release-unsigned.apk
```

This APK is minified and optimized like a release build, but it is not installable as-is on a normal Android device until it is signed. Official signed release APKs are produced by maintainers through the release workflow.

## 10. Codebase Notes

Main modules:

- `app`: Android application, Compose UI, playback, database, settings
- `innertube`: YouTube Music / InnerTube integration
- `spotify`: Spotify integration and mapping logic
- `lastfm`: Last.fm integration
- `betterlyrics`, `lrclib`, `kugou`, `shazamkit`, `paxsenix`, `kizzy`: supporting service integrations

Strings:

- Edit `app/src/main/res/values/metrolist_strings.xml` for source strings.
- Do not edit `app/src/main/res/values/strings.xml` for app text.
- Do not edit translated `metrolist_strings.xml` files directly unless the task is explicitly about translations.

Versioning:

- Do not bump `versionCode` or `versionName` during normal contribution work.

Spotify GraphQL hashes:

- If a new Spotify GraphQL operation is added in `spotify/src/main/kotlin`, add it to `docs/spotify-gql-hashes.json` so the scheduled hash checker tracks it.

## 11. Manual Testing

After a successful build, install the APK on a device or emulator:

```bash
adb install -r app/build/outputs/apk/foss/debug/app-foss-debug.apk
```

For playback-related work, test at least:

- App launch
- Search
- Starting playback
- Pause/resume
- Background playback
- Notification controls

For battery-sensitive playback behavior, test on a physical device when possible.
