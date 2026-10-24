# Install and connect

Ember connects to one Flare server with an API token. This guide covers installing the app, creating a token with the right permissions, and connecting, including servers on your home network.

## Requirements

- Android 8.0 (API 26) or newer.
- A Flare server you can reach from your phone. Ember is tested against Flare 3.0.0, and its CI runs every change against that release.
- A Flare account that can upload. Your administrator controls this through [roles](https://flintsh.github.io/Flare/admin/roles.html).

## Install

1. Open the [latest release](https://github.com/FlintSH/Ember/releases/latest) on your phone and download `Ember-x.y.z.apk`.
2. Open the file. If Android asks, allow your browser or file manager to install apps.
3. Optionally, check the download against `SHA256SUMS` from the same release, and the signing certificate listed in the [README](../README.md#verify-a-download).

To try changes before they're released, install **Ember Rolling** from the [`rolling` pre-release](https://github.com/FlintSH/Ember/releases/tag/rolling). It's a separate app, so it can sit next to the stable one with its own connection.

Updates install over the existing app and keep your connection and sync folders. Android blocks an update signed with a different key, which protects you from tampered APKs.

## Create a token

Use a named token so Ember gets only the permissions it needs and you can revoke it without affecting your other tools.

1. In Flare, open **Profile → Integrations** and choose **New token**.
2. Name it after the phone, such as `Ember on Pixel 8`.
3. Choose scopes:

   | Scope | What it enables in Ember |
   | --- | --- |
   | **Upload files** (required) | Uploads, pastes, and folder sync |
   | **Read files** | The **Files** tab in Library |
   | **Read short links** | Listing all your short links, not only ones made on this phone |
   | **Manage short links** | Creating and deleting short links |

4. Optionally choose an **Upload profile**. A bound token always uses that profile, which is the simplest way to make every upload from your phone private, expiring, or tagged. See [binding uploads to a profile](https://flintsh.github.io/Flare/api/authentication.html#bind-uploads-to-a-profile).
5. Optionally set an expiration.
6. Create the token and copy the `flr_…` secret. Flare shows it once.

Creating pastes also needs the `pastes.create` permission on your account. Flare can't report that ahead of time, so Ember finds out when you create your first paste.

### The account upload token

Flare's older account upload token also works. Find it in **Profile → Uploads → Manual setup and upload token**. It isn't limited by scopes, so Ember can also show your Flare folders as a list. The tradeoffs:

- It carries your account's full permissions on every route that accepts it.
- Replacing it breaks every ShareX, Flameshot, and Spectacle configuration that uses it.

With a named token you can still upload into a folder by pasting its **Upload here** link. See [choosing a folder](settings.md#folder).

## Connect

1. Open Ember and choose **Connect to Flare**.
2. Enter your Flare address, such as `files.example.com`. Ember adds `https://` when you leave the scheme out, and ignores any path, so a pasted dashboard link works too.
3. Paste the token. The clipboard button next to the field pastes it for you.
4. Choose **Connect**.

Ember checks the address and token without changing anything in Flare:

| Check | Request |
| --- | --- |
| Is this Flare? | `GET /api/health` |
| Can it upload? | `GET /api/files/chunks` for an upload that can't exist |
| Can it read files? | `GET /api/files?limit=1` |
| Can it list short links? | `GET /api/urls` |
| Can it manage short links? | `DELETE /api/urls/ember-capability-check`, a link that can't exist |
| Can it list folders? | `GET /api/folders` |

If the token can't upload, Ember says so and doesn't connect. Everything else is optional, and Ember shows what's available on the next screen and in **Settings → Connection**. After you change a token's scopes in Flare, choose **Check access** there.

Ember stores the token encrypted with a key held in the Android Keystore. See [security and privacy](security.md).

### A server on your home network

If your Flare address is on your local network, such as `http://192.168.1.20:3000` or `http://flare.local`, Android 17 asks whether Ember may reach devices on your network. Ember explains this first and only asks when the address is local. Ember never scans your network; the permission only lets it reach your Flare.

If you decline, Ember can't connect. You can allow it later in Android's settings for Ember, under **Permissions → Nearby devices**.

### Plain HTTP

Over `http://`, your token and files travel unencrypted. For an address that isn't on your local network or this phone itself (`localhost`), Ember asks you to confirm before connecting. Use HTTPS when your server supports it, for example behind a [reverse proxy](https://flintsh.github.io/Flare/hosting/reverse-proxy.html).

### A private certificate authority

Ember trusts certificate authorities you've installed in Android's settings, so a server using a certificate from your own CA works once that CA is installed. Ember does not let you skip certificate checks.

## After connecting

Ember asks to show notifications. They carry finished upload links with **Copy** and **Share** buttons, and tell you when something needs attention. Uploads work without them.

Next:

- [Share files, paste text, and shorten links](sharing.md)
- [Set up folder sync](sync.md)
- [Choose your upload defaults](settings.md)

## Disconnect or switch servers

**Settings → Disconnect** forgets the token and stops all uploads and sync. Your upload history and sync folders stay on the phone, and Ember remembers the address, so reconnecting picks up where you left off, including uploads that were waiting. Files already in Flare are not affected.

To replace a token, for example after revoking it, disconnect and connect again with the new one.

To use another server, disconnect and connect to the new one. A synced folder remembers what it already uploaded, so files sent to the old server aren't sent to the new one. Remove and add the folder again with **Everything here** to upload them anyway.
