## What changes

Describe the problem and the resulting behavior, including the effect on people already using Ember.

## Documentation

- [ ] Updated every affected guide in `docs/` and the README in this change (see `AGENTS.md`).
- [ ] Covered defaults, limits, permissions, failure cases, and recovery where affected.

List the updated guides. If behavior is unchanged, say which documentation you reviewed.

## Validation

- [ ] `./gradlew :api:test :app:lintDebug :app:verifyRoborazziDebug`
- [ ] `./gradlew :api:integrationTest` against a disposable Flare, for client changes
- [ ] Reviewed the CI emulator screenshots and recording

List anything else you checked and any remaining limitations.

## Screenshots / recordings

Include evidence for visible changes, using demonstration data.

## Related issues

Follow the [contributing guidelines](../CONTRIBUTING.md). Pull requests are reviewed by @FlintSH.
