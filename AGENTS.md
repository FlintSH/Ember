# Working on Ember

Ember is the Android client for Flare. It should hold the same quality bar as Flare: documented, tested, and visually consistent with Flare's design.

## Rules for every change

1. **Map the impact first.** Read the guide in `docs/` and the code you're changing. Identify affected screens, settings and defaults, background work, permissions, failure cases, and the Flare routes involved.
2. **Implement, test, and document together.** Update every affected page in `docs/` in the same commit as the behavior change: what people can do, where the control is, defaults, limits, errors, and recovery. Remove instructions that no longer apply. Update `README.md` when a headline feature changes.
3. **Keep the API contract honest.** Ember only uses routes Flare documents for bearer tokens, plus `GET /api/folders` for the account upload token and the dashboard's `X-Flare-Paste` header. Check Flare's current handbook and source before relying on behavior, and add MockWebServer tests and, where possible, live tests in `api/src/integrationTest`.
4. **Show real behavior.** Visible changes need refreshed Roborazzi screenshots (`./gradlew :app:recordRoborazziDebug`), reviewed image by image. The emulator run in CI produces screenshots and a recording of the real app against a real Flare server; use those for README and pull request evidence. Never present a mockup as the app.
5. **Validate before committing.** Run `./gradlew :api:test :app:lintDebug :app:verifyRoborazziDebug`, plus `:api:integrationTest` with a fixture when changing client code. See `docs/testing.md`.

## Product and design

- Follow Flare's design system: `DARK_PALETTE`/`LIGHT_PALETTE` tokens in `ui/theme/Color.kt`, Inter, Lucide icons (`scripts/icons/generate_lucide.py`), bordered cards, eyebrows, and Flare's button styles. Dark is the default theme.
- Keep screens compact and obvious. Configuration belongs in Settings or a folder's options, with defaults that work without changes.
- All text goes in `strings.xml`, written plainly: specific, direct, no hype, no em dashes.

## Safety

- Never log, print, or screenshot tokens. Test fixtures stay out of artifacts.
- Background work must be resumable: record progress in Room, keep uploads idempotent where Flare allows, and respect Android's background limits.
- Release signing secrets are only used by the CI publish path. Don't add other uses.
