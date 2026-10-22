# Contributing to Anansi

## Building

Requirements: Android Studio or the Android SDK (compileSdk 37) plus JDK 17.

- Command line: `./gradlew assembleDebug` builds the debug APK under
  `app/build/outputs/apk/debug/`, `./gradlew testDebugUnitTest` runs the
  unit suite, `./gradlew lint` runs Android lint, `./gradlew assembleRelease`
  builds the release APK.
- Android Studio: open the repository root and run the `app` configuration.

CI runs `./gradlew assembleDebug testDebugUnitTest lint` on every push to
`main` and on every pull request (`.github/workflows/checks.yml`); please run
the same checks before pushing. Releases are cut by pushing a `v<version>`
tag: `.github/workflows/release.yml` builds and publishes a signed APK to the
GitHub Release automatically (it can also be run by hand from the Actions
tab).

Regenerating the committed plugin-host vendor bundle
(`app/src/main/assets/pluginhost/vendor.js`) is only needed when bumping its
bundled JavaScript libraries: run `npm install && npm run build` inside
`scripts/vendor-bundle/`. Regular app builds never need Node.

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

## Ground rules

- Clean-room only: GPL projects (legado and its forks, Kotatsu, DropSauce,
  Shosetsu kotlin-lib) are read-only references; never copy their code into
  this MIT repo. Plugin behavior is supported through adapters, and plugins
  must fail loudly with a visible reason, never silently.
- No ads, analytics, tracking, crash-reporting SDKs, or accounts; no
  anti-circumvention code of any kind (no CAPTCHA or challenge bypass, no
  paywall logic); browser-equivalent HTTP only.
- Commit titles: under 50 characters, lowercase, no conventional-commit
  prefixes.
- Open pull requests against this repository only, never against the upstream
  LNReader or other reader projects.
