# Share, paste, and shorten

Ember adds Flare to the places you already share from: the share menu, the text selection menu, Quick Settings, and long-pressing Ember's icon.

## Upload from another app

1. In any app, choose **Share**, then **Upload to Flare**.
2. Ember shows the files, with options for this upload:
   - **Visibility**, **Expires**, and **Folder**. Each starts at your [upload defaults](settings.md#upload-defaults), and the label shows what the default is.
   - **Password**, which people opening a public link must enter. Flare allows up to 72 bytes.
3. Choose **Upload**.

When the upload finishes, Ember copies the link and shows **Done. Link copied.** with **Share** and **Open**. For several files, it copies all the links, one per line.

You don't have to wait for the upload. Once **Preparing upload** finishes, close the sheet and the upload continues in the background. A notification shows progress, then the link with **Copy link** and **Share**. Tapping it opens the link. While Ember prepares the files, the sheet stays open, because closing it then would cancel the copy.

To skip the review step and upload right away with your defaults, turn off **Settings → Sharing → Review before uploading**.

### What happens behind the scenes

- Ember copies each shared file into its own storage first. Android revokes another app's permission to read a file once the share sheet closes, so the copy is what lets an upload finish in the background, or retry after you lose signal. Ember deletes the copy when the upload finishes or you remove it from history. Preparing needs free space for the file plus a 200 MB margin.
- Files of 24 MiB or more upload through Flare's chunked upload API in 8 MiB parts. A dropped connection only repeats the part it interrupted.
- Ember checks each file's real type before uploading. Flare rejects a file whose bytes don't match the type it's sent with, and some apps report types wrongly.
- Flare allows 30 upload starts per minute from one address. Ember paces itself under that, across shares and sync together.
- If Flare can't be reached, uploads wait and try again later until it can. Other temporary failures, such as a server error, retry up to 8 times. Permanent problems, such as a file over your instance's size limit, show in **Library → Recent** with Flare's own explanation. Trying a failed upload again keeps the password you set.
- If Flare stops accepting your token, uploads wait instead of failing. Home shows **Reconnect**, and everything continues once you connect again.
- If preparing a file fails, for example because the phone is out of space, free up space and choose **Try again** while the sheet is still open. After it closes, share the file again.

A share can include up to 100 files. Ember refuses `file://` paths, which modern apps don't use for sharing, because reading them would use Ember's own permissions.

## Paste text

There are four ways to create a paste:

- **Select text** in any app and choose **Paste to Flare** from the menu. You may need to open the menu's overflow (⋮) to find it.
- **Share text** to **Upload to Flare**.
- Choose **New paste** on Ember's home screen.
- Use the **Paste to Flare** Quick Settings tile. See [Quick Settings](#quick-settings).

Ember suggests a file name: your default from **Settings → Pastes**, with `.json`, `.html`, or `.sh` when the text clearly is one. Change it before creating the paste. A useful extension helps people open the download, though Flare stores pastes as plain text.

When you select text in a field you can edit, such as a chat box, Ember replaces your selection with the paste's link and returns to the app. Your clipboard stays as it was. Turn this off with **Settings → Pastes → Replace selected text with the link**. In text you can't edit, such as a web page, Ember copies the link instead.

Creating pastes needs the `pastes.create` permission on your Flare account. If your role doesn't include it, Ember shows Flare's message.

## Shorten a link

Share a link to **Upload to Flare**, or select one and choose **Paste to Flare**. When the text is a single link and your token can manage short links, Ember offers **Short link** first, and you can switch to **Paste**.

You can also choose **Shorten link** on the home screen, or long-press Ember's icon and choose **Shorten link**.

Short links look like `https://files.example.com/u/aB3_dE`. Ember uses the address Flare puts in its own links, which matters when you connect through a local address but share a public one. Until your first upload, Ember uses the address you connected with.

Flare creates a new code each time, so shortening the same link twice gives two short links.

## Quick Settings

Add the **Paste to Flare** tile:

1. Swipe down twice to open Quick Settings, then choose the pencil icon.
2. Drag **Paste to Flare** into your tiles.

Tapping it opens Ember's sheet with your clipboard's contents, ready to paste or shorten. Android only lets an app read the clipboard while it's open in front of you, which is why the tile opens a sheet instead of uploading silently. Android shows a notice when Ember reads the clipboard.

## Launcher shortcuts

Long-press Ember's icon for **New paste**, **Shorten link**, **Upload files**, and **Sync now**. Drag one to your home screen to keep it there.

## Upload from Ember

Choose **Upload files** on the home screen, then **Photos and videos** for Android's photo picker, or **Files** for everything else. Uploads use your defaults and appear in **Library → Recent**.
