# Releasing

Releases come from `main`, automatically, after every test passes. There's nothing to run by hand.

## Channels

| Channel | When | App | Version |
| --- | --- | --- | --- |
| Stable `vX.Y.Z` | A push to `main` where `version.properties` names a version that has no tag yet | `dev.fl1nt.ember`, **Ember** | `versionName` from `version.properties`; `versionCode` is `major × 10000 + minor × 100 + patch` |
| Rolling | Every other push to `main` | `dev.fl1nt.ember.rolling`, **Ember Rolling** | `X.Y.Z-rolling+<sha>`; `versionCode` is the commit count |

Rolling is a separate app so people can try `main` without ever being stuck on a build newer than the next stable release. The `rolling` tag and pre-release move to each new build, and the previous rolling APK is replaced.

## Cut a stable release

1. Write release notes in `.github/release-notes/vX.Y.Z.md`. Without the file, the release lists commit subjects since the previous tag. Read earlier notes first and match their voice.
2. Change `versionName` in `version.properties`.
3. Merge to `main`.

CI builds the stable APK, runs every test against it, then tags `vX.Y.Z` on that commit and publishes `Ember-X.Y.Z.apk` with `SHA256SUMS`. If any test fails, nothing is published; fix it and push again.

Version parts must stay below 100. Never reuse or decrease a version: Android refuses to install an update with a lower `versionCode`.

## How CI publishes

The `CI` workflow's jobs:

1. **Plan** decides the channel from `version.properties` and existing tags.
2. **Build and test** runs lint, unit and screenshot tests, then builds the release APK with the release key and checks its certificate.
3. **Live Flare API** runs the client against Flare 3.0.0.
4. **Emulator (Android 16 and 17)** installs that exact APK and walks through Ember against Flare 3.0.0.
5. **Publish** verifies the APK's checksum and creates the release.

Pull requests run the first four jobs with a debug-signed build and never publish.

## Signing

Release APKs are signed with Ember's release key (APK signature schemes v2 and v3). Its certificate:

```text
SHA-256: 99:0D:09:8E:FE:E3:7E:AB:6D:28:6D:75:2D:F8:CD:17:F1:46:EF:9E:14:E5:00:1A:F3:C4:E1:F1:00:81:86:9B
```

The key lives in repository secrets:

| Secret | Value |
| --- | --- |
| `EMBER_KEYSTORE_BASE64` | The PKCS #12 keystore, base64 encoded |
| `EMBER_KEYSTORE_PASSWORD` | Keystore password |
| `EMBER_KEY_ALIAS` | `ember` |
| `EMBER_KEY_PASSWORD` | Key password |

The repository variable `EMBER_SIGNING_CERT_SHA256` holds the certificate digest in `apksigner`'s format (lowercase hex). CI refuses to publish an APK signed with anything else, including the debug key.

Keep an offline backup of the keystore and its passwords. **If the key is lost, nobody can update an installed Ember**; everyone would have to uninstall, losing their connection and sync folders, and install an APK signed with a new key. Android's [key rotation](https://source.android.com/docs/security/features/apksigning/v3) (APK signature scheme v3) is the way to move to a new key without that, and it still needs the old key.

### Build a signed APK locally

Gradle reads the same values from the environment:

```sh
export EMBER_KEYSTORE_PATH=/path/to/ember-release.p12 EMBER_KEY_ALIAS=ember
export EMBER_KEYSTORE_PASSWORD=... EMBER_KEY_PASSWORD=...
./gradlew :app:assembleRelease
```

Without them, release builds are signed with the debug key so anyone can build and test locally.
