# Library and links

**Library** has three tabs: what this phone uploaded, your Flare library, and your short links.

## Recent

Every upload from this phone, newest first: shares, pastes, picked files, and synced files. Each row shows its status:

| Status | Meaning |
| --- | --- |
| **Uploading** | In progress, with a progress bar. |
| **Waiting** | Queued until there's a connection. |
| **Retrying soon** | The last attempt didn't finish. Ember tries again automatically. |
| **Uploaded** | Done. The copy button copies the link. |
| **Too large**, **Not allowed**, **Rejected by Flare**, **Token rejected**, **File unavailable**, **Upload failed** | Didn't upload. Tap the row for Flare's explanation. |
| **Cancelled** | You cancelled it before it started. |

Tap a row for details and actions: **Copy link**, **Copy raw file link**, **Share**, **Open**, **Try again** for failures, **Cancel upload** while it's waiting, and **Remove from history**. Removing an entry only affects this phone; the file stays in Flare.

**Clear finished** removes uploaded, failed, and cancelled entries from the list.

## Files

Your Flare library, newest first, 30 at a time as you scroll. **Search** matches file names and text Flare found in images (OCR).

This tab needs a token with **Read files**. Without it, Ember explains how to add the scope.

A lock means the file is private, and a key means it has a password. Tap a file to copy or share its link, or copy the raw file link for files anyone can open.

Previews only load for public images without a password. Flare doesn't let tokens load private thumbnails; that needs a signed-in browser. Links to private files only work for you, signed in to Flare.

## Links

Your short links with their targets and click counts.

- With **Read short links**, Ember lists all of your account's links.
- Without it, Ember lists the links created on this phone.

**Shorten a link** creates one, if your token has **Manage short links**. Tap a link to copy or share it, open its target, or **Delete link**. Deleting stops the short link from redirecting; it doesn't affect the target.

Click counts include link previews and bots, as Flare counts every redirect.
