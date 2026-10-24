# Security and privacy

Ember holds a credential for your Flare server and can read files you choose. This page describes exactly what it stores, sends, and asks for.

## What Ember sends, and to whom

Ember only talks to the Flare server you connect it to. It has no analytics, crash reporting, ads, or accounts of its own, and contacts no other service.

Requests carry your token in an `Authorization: Bearer` header, never in a URL, and a `User-Agent` such as `Ember/0.1.0 (Android 16; SDK 36)`.

## Your token

- Ember encrypts the token with AES-256-GCM before writing it to disk. The key is generated in the Android Keystore, can't be exported, and never leaves the phone.
- Ember turns off Android backups and device-to-device transfer, so neither the encrypted token nor your settings are copied anywhere. After moving to a new phone, connect Ember again.
- Ember never shows the token after you connect, and keeps it out of logs.
- A named token can be revoked in Flare's **Profile → Integrations** without affecting your other tools. Revoke it if you lose the phone. Flare's session controls don't revoke tokens.

Upload passwords you type for a share or paste are encrypted the same way while the upload waits, and erased once it finishes.

## Files

- Files you share are copied into Ember's private storage until they upload, then deleted. See [why](sharing.md#what-happens-behind-the-scenes). This storage is excluded from backups.
- Synced files are read in place and never copied.
- Ember refuses `file://` paths from other apps, so another app can't trick it into uploading Ember's own private files.
- Photos from synced albums arrive in Flare without GPS location data. See [photo location data](sync.md#photo-location-data).

Remember that **Public** uploads open for anyone with the link. Logs and configuration files can contain secrets; check visibility before sharing them.

## Network security

- **HTTPS** is the default. Plain `http://` works for servers on your home network. For other addresses, Ember asks you to confirm, because the token and files would be readable on the way.
- **Certificates** from Android's trusted authorities and from authorities you installed yourself are accepted. You can't turn off certificate checks.
- On Android 17, connections to publicly trusted certificates also require Certificate Transparency.

## Permissions

| Permission | Why |
| --- | --- |
| Internet, network state | Talk to your Flare and wait for a connection before uploading. |
| Notifications | Show upload progress, finished links, and problems. Optional. |
| Foreground service (data sync) | Keep long uploads running while Ember shows their progress. |
| Local network access (Android 17) | Reach a Flare server on your home network. Only requested when your address is local. |
| Photos and videos | Sync photo and video albums. Only requested when you add an album. |
| Storage, Android 12 and older | The same, on older Android versions. |

Ember doesn't request location, contacts, camera, microphone, or access to all files. Folders you pick in the system file picker are shared with Ember individually, and Ember releases that access when you stop syncing the folder.

## Report a vulnerability

Please report security problems privately. See [SECURITY.md](../SECURITY.md).
