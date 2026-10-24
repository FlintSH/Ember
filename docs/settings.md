# Settings

## Connection

Shows the server, what kind of token Ember uses, and what it can do:

| Line | Needs |
| --- | --- |
| Upload files and pastes | **Upload files** |
| Browse your library | **Read files** |
| Create short links | **Manage short links** |
| Choose folders from a list | The account upload token. Named tokens can't list folders. |

**Not in token** means the token lacks that scope. **Not allowed by role** means your Flare account's roles forbid it.

- **Rename** changes the name Ember shows for this server.
- **Check access** runs the checks again, for example after you create a token with more scopes.
- **Token guide** opens [creating a token](getting-started.md#create-a-token).

## Upload defaults

Used for every share, paste, and synced folder unless you choose otherwise there.

**Flare default** sends nothing for that option, so Flare decides from your account settings, your default upload profile, or the profile your token is bound to. That's usually what you want with a profile-bound token, because Flare rejects uploads that override a bound profile's options.

### Visibility

**Public** files open for anyone with the link. **Private** files only open for you, signed in to Flare.

### Expires

**Never**, or after **1 hour**, **1 day**, **1 week**, or **1 month** (a calendar month in UTC). Expiring uploads need the matching permission on your Flare account, which most roles include.

### When it expires

**Delete the file**, or **Make it private**. Ember only sends this when the upload expires.

### Folder

Where uploads land in your Flare library: **Flare default**, **Unfiled**, or a folder.

- With the account upload token, Ember lists your folders by path, such as `Projects / Website`.
- With a named token, choose **Use a folder link**. In Flare, open the folder and copy its **Upload here** link, or copy the folder ID, and paste it.

### Upload profile

Tokens can't list upload profiles. To always use one profile, bind your token to it in Flare. Otherwise choose **Use a profile ID**, or **No profile** to skip your default profile.

## Sharing

| Setting | Default | What it does |
| --- | --- | --- |
| **Copy link after upload** | On | Copies the link when a share, paste, or short link finishes. Synced folders have their own **Copy the link** option. |
| **Link to copy** | Share page | **Share page** shows a preview, download button, and password prompt. **Raw file** links to the file itself, for embedding. |
| **Review before uploading** | On | Off uploads shared files and text right away with your defaults. |
| **Notify when uploads finish** | On | Shows the link with **Copy link** and **Share**. Synced folders have their own **Notify for each file** option. |

## Pastes

| Setting | Default | What it does |
| --- | --- | --- |
| **Default file name** | `paste.txt` | Also `paste.md`, `snippet.txt`, or `notes.md`. You can change the name for each paste. |
| **Replace selected text with the link** | On | With **Paste to Flare** in a text field you can edit. |

## Automatic sync

| Setting | Default | What it does |
| --- | --- | --- |
| **Upload over** | Wi-Fi only | Or any network, including mobile data. |
| **Only while charging** | Off | Scheduled syncs wait for a charger. |
| **Check folders every** | 1 hour | 15 minutes, 1 hour, 6 hours, or 1 day. New photos and screenshots upload within seconds either way. |

See [folder sync](sync.md) for how these apply.

## Appearance

**Theme**: **Dark**, Flare's default, **Light**, or **Match this phone**.

## About

The version and release channel, links to Ember on GitHub, this documentation, issue reporting, Flare itself, and open source licenses.

## Disconnect

Forgets your token and stops uploads and sync. Upload history and sync folders stay on the phone. See [disconnecting](getting-started.md#disconnect-or-switch-servers).
