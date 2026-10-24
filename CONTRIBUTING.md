# Contributing to Ember

Thanks for helping. Ember is a small, focused companion to [Flare](https://github.com/FlintSH/Flare): it should feel like Flare, stay compact, and do the everyday things on a phone very well.

## Before you start

- For anything bigger than a fix, open an issue first so we can agree on the shape of it.
- Changes to what Flare's API can do belong in Flare. Ember only uses routes Flare documents for tokens.

## Development setup

You need JDK 21 and the Android SDK (platform 37, build tools 36). Android Studio provides both.

```sh
git clone https://github.com/FlintSH/Ember.git
cd Ember
./gradlew :app:assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` (Ember Debug, `dev.fl1nt.ember.debug`) next to a release build if you have one.

## Making a change

1. Read the relevant guide in `docs/` and the code it describes.
2. Make the change with tests. Ember favors:
   - unit tests for pure logic such as the sync planner and MIME detection
   - Robolectric tests for code that touches Android
   - MockWebServer tests for anything that talks to Flare
3. Update the documentation in the same change. A feature isn't done until `docs/` describes what ships, including defaults, limits, and failure cases. See [AGENTS.md](AGENTS.md).
4. For visible changes, record screenshots and review every image:
   ```sh
   ./gradlew :app:recordRoborazziDebug
   ```
5. Run the checks:
   ```sh
   ./gradlew :api:test :app:lintDebug :app:verifyRoborazziDebug
   ```
6. Open a pull request. CI also runs the live Flare and emulator tests and attaches their screenshots and screen recordings.

## Style

- Kotlin official style, warnings are errors.
- Match Flare's design: its palette, Inter, Lucide icons, bordered cards, and compact layouts. Prefer polishing an existing pattern to inventing a new one.
- All user-facing text lives in `strings.xml`. Write it plainly and specifically: say what happened and what to do next.
- Never log or display tokens, and keep secrets out of screenshots and test fixtures.

## Releases

Maintainers release by changing `version.properties` on `main`. See [releasing](docs/releasing.md).
