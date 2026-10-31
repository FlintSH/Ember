# Testing

Ember has five layers of tests. CI runs all of them on every pull request and push, and only publishes an APK that passed every layer.

| Layer | Command | What it covers |
| --- | --- | --- |
| Client unit tests | `./gradlew :api:test` | URL parsing, credentials, MIME detection against samples of what Flare's detector reports, options, rate pacing, and every client call against MockWebServer, including chunked uploads that resume, part retries, size limits, and error mapping. |
| App unit and Robolectric tests | `./gradlew :app:testDebugUnitTest` | The sync planner and engine against a fake Flare with an in-memory database, including failures, retries, cancelling, switching a folder between new files only and everything, and uploads that resume after being stopped; the share intent parser, encrypted account storage, settings, presets, and helpers. |
| Screenshot tests | `./gradlew :app:verifyRoborazziDebug` | Every screen rendered with demonstration data, plus light-theme versions of key screens, compared with the committed images in `app/src/test/screenshots`. |
| Live Flare tests | `./gradlew :api:integrationTest` | Ember's client against a real Flare server: capability checks per token type, private and expiring uploads, folders, pastes, chunked uploads past Next.js's 10 MiB request limit, resuming an abandoned upload, non-ASCII file names, phone file types, short links, and scope enforcement. |
| End-to-end tests | `scripts/ci/run-e2e.sh` | The installed release APK on Android 16 and 17 emulators against a real Flare server: connecting, sharing a photo, pasting selected text, shortening a link, syncing an album's new photos and then the ones already in it, and browsing the library. Results are checked through Flare's API. |

Run the everyday set before opening a pull request:

```sh
./gradlew :api:test :app:lintDebug :app:verifyRoborazziDebug
```

## Requirements

- JDK 21. Gradle runs on 17 or newer; CI uses Temurin 21.
- The Android SDK with platform 37 and build tools 36. Set `ANDROID_HOME`, or let Android Studio manage it.

## Screenshot tests

`ScreenshotTest` renders each screen with `SampleData` through Robolectric's native graphics. After an intentional visual change, record new images and review them before committing:

```sh
./gradlew :app:recordRoborazziDebug
git diff --stat app/src/test/screenshots
```

`verifyRoborazziDebug` fails on any difference, and CI uploads the comparison images when it does.

Sample times a day or more in the past use whole days, because "Yesterday" and "2 days ago" follow the calendar and would change with the time of day the tests run.

The screenshots in the README, `docs/images/screens`, are these renders scaled to 540 pixels wide. Refresh them when the screens they show change.

## Live Flare tests

These need a disposable Flare server and a fixture file with tokens. `scripts/flare-fixture/bootstrap.py` prepares any Flare instance: it completes first-run setup, signs in, creates tokens, and writes the fixture. See [its README](../scripts/flare-fixture/README.md).

```sh
python3 scripts/flare-fixture/bootstrap.py --url http://localhost:3300 --out /tmp/flare-fixture.json
FLARE_FIXTURE=/tmp/flare-fixture.json ./gradlew :api:integrationTest
```

Without `FLARE_FIXTURE` the suite skips itself. Point it only at a server you can throw away: it creates files, folders, tokens, and short links.

## End-to-end tests

The `:e2e` module drives an installed Ember APK with UI Automator, the way a person would. Because it doesn't instrument Ember's code, it tests the exact minified, signed APK that gets published.

CI starts PostgreSQL and the Flare 3.0.0 image, prepares the fixture, boots an emulator, and runs `scripts/ci/run-e2e.sh`. To run it locally with an emulator or device connected:

```sh
./gradlew :app:assembleRelease :e2e:assembleDebug
FLARE_URL=http://localhost:3000 FLARE_TOKEN=flr_... EMBER_PACKAGE=dev.fl1nt.ember E2E_LABEL=local \
  scripts/ci/run-e2e.sh app/build/outputs/apk/release/app-release.apk e2e/build/outputs/apk/debug/e2e-debug.apk e2e-results
```

The script clears Ember's data first. It saves a screenshot of every step, a screen recording, and logcat to the output folder, and CI attaches them to the run as `e2e-android-16` and `e2e-android-17`. Use them for review: they show the real app talking to a real server.

CI runs Android 17 on Google's automated test device image (`google_atd`), because the Google APIs image restarted its system services right after booting on every run. The test image drives the app the same way, but it doesn't draw the screen, so its screenshots and recording are blank. Review the Android 16 ones instead.

On Android 17, CI connects through `10.0.2.2`, a local network address, so the run also covers Android 17's local network permission prompt. On Android 16 it uses `adb reverse` and `localhost`.
