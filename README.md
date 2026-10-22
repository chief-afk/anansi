# Anansi

A free, open-source, non-commercial Android reader for web novels.

Anansi the Spider is the legendary West African trickster god, known for
spinning intricate webs of words, clever deceptions, and tales; in the old
stories he becomes the keeper of all stories. This app takes the name for the
same reason: one library, many source ecosystems, behind a single internal
source interface.

Supported source ecosystems in v0.1.0:

- LNReader v3 JavaScript plugins
- LNReader v1-era JavaScript plugins
- OPDS 1.x catalogs
- Local EPUB and TXT files (imported into app-private storage)

Incompatible plugins and catalogs fail loudly with a visible reason; they are
never silently skipped or silently disabled.

## Posture

- MIT licensed, non-commercial forever.
- No ads, no analytics, no tracking, no crash-reporting SDKs, no accounts.
- Ships zero sources and zero default repositories. Content sources come from
  user-added repository URLs only. Anansi never ships or operates a curated
  repository (permanent).
- Distributed via GitHub Releases only; never published to app stores.
- Browser-equivalent HTTP only. No anti-circumvention code of any kind: no
  CAPTCHA or challenge bypass, no paywall logic.

## Compatibility surfaces (permanent)

- LNReader backup import: `lnreader_backup_*.json` files (the official v1
  shape and the common fork shape) must keep restoring in every release.
- Anansi's own backup export will be versioned JSON; older exports must keep
  restoring forever (export itself is still a backlog item in v0.1.0).
- LNReader plugin API compatibility (v3 line first, v1-era adapter second):
  incompatible plugins fail loudly with a visible reason, never silently.

## Building

Requirements: Android Studio or the Android SDK (compileSdk 37) plus JDK 17.

- Command line: `./gradlew assembleDebug` builds the debug APK under
  `app/build/outputs/apk/debug/`, `./gradlew testDebugUnitTest` runs the
  unit suite, `./gradlew lint` runs Android lint, `./gradlew assembleRelease`
  builds the release APK.
- Android Studio: open the repository root and run the `app` configuration.

## Release signing

Release signing material is never committed to this repository. The release
build type signs only when all four keys below are present. Each key resolves
from `local.properties` first and falls back to its matching environment
variable when absent or blank:

1. `local.properties` keys (the file is gitignored; `storeFile` resolves
   relative to the repository root):
   - `anansi.release.storeFile`
   - `anansi.release.storePassword`
   - `anansi.release.keyAlias`
   - `anansi.release.keyPassword`
2. Environment variables:
   - `ANANSI_RELEASE_STORE_FILE`
   - `ANANSI_RELEASE_STORE_PASSWORD`
   - `ANANSI_RELEASE_KEY_ALIAS`
   - `ANANSI_RELEASE_KEY_PASSWORD`

When no signing material is present the release build still succeeds and
produces an unsigned APK, so secret-free machines can always run the release
check. Release minification stays off in v0.1.0 by decision.

