# Troubleshooting

## Connecting

**"Flare didn't accept this token."** Flare answers the same way for a mistyped, expired, revoked, or unscoped token, and when your account must verify its email first. Copy the token again from Flare, or create a new one with **Upload files**. If your server sits behind a reverse proxy, make sure the proxy forwards the `Authorization` header.

**"This token can't upload."** Create a token that includes **Upload files**. **"Your Flare account isn't allowed to upload"** means your roles don't include uploading; ask your administrator.

**"…doesn't look like a Flare server."** The address answered, but not like Flare. Check for a typo, a captive portal on public Wi-Fi, or a reverse proxy sending the request somewhere else. Open `https://your-address/api/health` in a browser: Flare answers `{"data":{"status":"ok"},"success":true}`.

**"Couldn't find…" or "refused the connection."** Check the address and port, that Flare is running, and that your phone can reach it. Away from home, a server on your local network isn't reachable unless you use a VPN.

**"This device doesn't trust the certificate…"** Use a certificate from a public authority, or install your own certificate authority in Android's settings (usually **Security → More security settings → Encryption and credentials → Install a certificate**).

**A local server stopped working on Android 17.** Android may have turned off Ember's local network access. Home and Sync show **Allow local network access**: choose **Allow**, or allow it in Android's settings for Ember, under **Permissions → Nearby devices**. Waiting uploads continue when you come back to Ember.

**"Flare rejected Ember's token."** The token expired, was revoked, or lost a permission. Choose **Reconnect** on Home and connect with a new token. Waiting uploads and sync continue afterwards.

**"Ember can't read its saved token."** Android can reset the keys Ember uses to encrypt the token. Choose **Reconnect** on Home and connect again.

## Uploading

**Too large.** The file is over your instance's maximum upload size, or your storage quota is full. Both are Flare settings. Flare measures limits in powers of 1024.

**Not allowed.** Your Flare roles don't allow this, for example creating pastes (`pastes.create`), uploading into folders, or uploading files that expire. If your token is bound to an upload profile, Flare also rejects uploads that choose a different visibility, expiration, or folder than the profile. Set those options to **Flare default** in Ember.

**Rejected by Flare.** Flare refused the request and Ember shows its reason, such as a missing folder. A wrong file type usually means the sending app reported the type incorrectly; send the file again from a file manager. Flare also checks pastes this way, so text that starts like another kind of file, such as `<?xml` or `%PDF`, is rejected. Flare's own paste form has the same limit; upload it as a file instead.

**Retrying soon** that never finishes. While Flare can't be reached, Ember keeps waiting and trying again with growing delays. Check that you're online, that Flare is up, and, for a server on your home network, that you're on that network. Other temporary errors retry 8 times for shares and 10 for synced files, then show as failed. A token problem shows as **Paused** instead, with **Reconnect** on Home.

**A link points to the wrong address.** Flare builds links from its `NEXTAUTH_URL` setting. Ask your administrator to set it to the public address.

**Duplicates.** If a connection drops after Flare saved a file but before Ember heard back, retrying can create a second copy. Files of 8 MiB or more use Flare's chunked uploads, which Ember can safely finish again, unless the upload sat unfinished for over an hour and Flare discarded it.

## Sync isn't uploading

1. Check that **Automatic sync** is on, and the folder's own switch.
2. Check **Settings → Automatic sync**: with **Wi-Fi only**, nothing uploads on mobile data. With **Only while charging**, scheduled syncs wait for a charger.
3. Look for a warning on the folder. **Needs attention** on the Sync screen means a folder, photo access, or local network access needs you, and the warning says what to do.
4. On Android 14 and later, make sure Ember can see **all** photos, not only selected ones.
5. Some phones stop background apps aggressively. In Android's settings for Ember, set battery use to **Unrestricted**. [Don't kill my app](https://dontkillmyapp.com) has steps for many manufacturers.
6. Choose **Sync now** to check immediately.

**An old file didn't upload.** A folder set to **New files only** ignores files that were there before you added it. For folders from the file picker, Ember goes by modified time, which copying can preserve. Open the folder and choose **Everything here** under **Which files upload**.

**A failed file.** Open the folder, fix the cause shown on the file, and choose **Try failed files again**.

## Paste to Flare is missing from the text menu

Some apps put extra actions under the menu's overflow (⋮), and a few apps don't show other apps' actions at all. Share the text, or use the Quick Settings tile instead.

## Still stuck

Open an [issue](https://github.com/FlintSH/Ember/issues/new/choose) with your Android version, Ember version (**Settings → About**), Flare version, and what you see. Never include your token.
