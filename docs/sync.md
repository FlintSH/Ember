# Folder sync

Ember watches folders you choose and uploads new files to Flare on its own. Use it to back up your camera, send every screenshot to Flare the moment you take it, or collect recordings and documents from any folder.

## Add a folder

1. Open **Sync** and choose **Add a folder**.
2. Pick a source:
   - **Photo and video albums** list your phone's albums the way Google Photos shows device folders: Camera, Screenshots, Download, and folders other apps create. Ember needs access to photos and videos for these.
   - **Choose a folder** opens Android's file picker for any other folder, such as Recordings or Documents.
3. Choose **Which files upload**:
   - **New files only** uploads files added from now on. What's already there stays on your phone.
   - **Everything here** (for albums, **All** with the number of items) uploads the existing files too, oldest first. A large album can take a while. Ember paces uploads to stay within Flare's limits.

   You can change this later. See [Upload what was already there](#upload-what-was-already-there).
4. Set **Where uploads go** and **After each upload**, described below.
5. Choose **Start syncing**. Ember checks the folder immediately.

Photos from Google Photos only sync if they're stored on the phone, which is where the Camera album comes from. Photos that exist only in Google's cloud aren't files on your device.

### Options

| Option | What it does |
| --- | --- |
| **Visibility**, **Expires**, **Folder** | Override your [upload defaults](settings.md#upload-defaults) for this folder. The label shows what the default is. |
| **Copy the link** | Copies each new file's link as soon as it uploads, like a desktop screenshot tool. Files that were already in the folder when you added it don't. On by default for Screenshots. |
| **Notify for each file** | Shows a notification with the link for every new file. Otherwise, and for files that were already in the folder, Ember sends one summary per sync. On by default for Screenshots. |
| **Include photos**, **Include videos** | For albums. At least one stays on. |
| **Include subfolders** | For folders from the file picker. Ember looks up to 12 levels deep. |

Change these later by tapping the folder in **Sync**.

### Upload what was already there

If you added a folder with **New files only** and now want its older files too, tap the folder in **Sync**, choose **Everything here** under **Which files upload**, and confirm with **Upload them**. Ember uploads the files that were there before you added the folder, oldest first, and new files still go ahead of them. Files that already uploaded aren't sent again. The older files don't copy their links or notify one by one, so your clipboard stays as it was.

To stop, choose **New files only** again. Older files Ember hasn't started stay on your phone. One it already started, such as a file waiting to try again or a long video partly sent, still finishes, and files already in Flare stay there. **New files only** always counts from when you added the folder, and the folder shows that date and time.

## When Ember syncs

- **New photos, videos, and screenshots** start uploading a few seconds after they appear. Android tells Ember when its media library changes.
- **Every folder** is also checked on a schedule: every 15 minutes, hour (the default), 6 hours, or day. Change this in **Settings → Automatic sync**. Folders from the file picker are only checked on this schedule.
- **Sync now**, on the Sync screen, home screen, or launcher shortcut, checks right away.

By default Ember only syncs over Wi-Fi. You can allow mobile data, and choose **Only while charging**. **Sync now** doesn't wait for a charger, but still respects Wi-Fi only. Scheduled checks also wait while your battery is low.

Android limits how long background work may run, so Ember works through a large backlog in stretches of a few minutes and continues on its own. New files go ahead of files that were already in a folder, so a backlog holds them up for one stretch at most. A long video that doesn't finish in one stretch continues from the parts already uploaded. While Ember is open, a sync can run longer with a progress notification.

Turn everything off with the **Automatic sync** switch, or pause one folder with its own switch. With automatic sync off, nothing uploads on its own, but a sync you start with **Sync now**, by adding a folder, or by choosing **Everything here** still finishes.

## Which files upload

Each file uploads once. Ember remembers every file it uploaded, Flare permanently rejected, or you cancelled, so a file isn't sent twice, including after you disconnect and reconnect. To keep a waiting file from uploading, open it in **Library → Recent** and choose **Cancel upload**.

Ember skips:

- Files still being written. Albums use Android's own marker for this (on Android 9 and older, Ember waits 5 seconds after a change). For other folders, Ember waits until a file hasn't changed for 30 seconds, then checks again about a minute later instead of waiting for the next scheduled check.
- Hidden files, whose names start with a dot, and partial downloads ending in `.tmp`, `.part`, `.crdownload`, `.download`, or `.partial`.
- Empty files.

Editing a file after it uploaded doesn't upload it again. Deleting a file from your phone doesn't delete it from Flare, and deleting it in Flare doesn't remove it from your phone.

For **New files only**, albums use the time a file was added to the phone's media library. Folders from the file picker use each file's modified time, so a file copied in with an old modified time may not count as new. Switch the folder to **Everything here** to include it.

### Photo location data

Photos from albums upload without GPS location data. Android removes it for apps that don't hold the **Access media location** permission, and Ember doesn't ask for it, so public links don't reveal where photos were taken. Files from folders chosen with the file picker upload as they are.

## When something goes wrong

- **Flare out of reach**, such as no connection, a server that's down, or Flare's rate limit, pauses the sync. Ember tries again with increasing delays. Files don't fail this way, so a server you can only reach at home doesn't fail every photo you take elsewhere.
- **A file that keeps failing** for another temporary reason, such as a server error, waits behind newer files so the rest keep uploading. After 10 tries it counts as failed. When several files in a row fail like this, Ember pauses the sync instead.
- **A file Flare rejects**, for example because it's larger than your instance allows, counts as failed. The folder shows **failed** with a count. After fixing the cause, such as raising the limit in Flare, open the folder and choose **Try failed files again**, or choose **Try again** on one file in **Library → Recent**.
- **A refusal that would repeat**, such as a role that can't upload or a server error Ember can't retry, fails that file and pauses the folder until the next sync. The folder shows Flare's message, so one problem doesn't fail a whole album at once.
- **A file deleted before it uploaded** is skipped without a message. If it comes back, for example on a drive you reconnect, it uploads then.
- **A rejected token** stops all sync. Home shows **Reconnect**, and Ember sends a notification. Waiting files upload after you connect again.
- **Lost access**: if you turn off photo access, or Android revokes access to a picked folder, the folder shows a warning and Ember notifies you. Allow access again, or remove the folder and add it again.
- **A missing folder**, such as one on an SD card or USB drive that's been removed, shows **Ember can't find this folder**. Reconnect the storage, or remove the folder.
- **Only some photos visible**: on Android 14 and later, choosing **Select photos** when Android asks gives Ember only those photos. Albums can't sync fully that way. Choose **Allow all** in Ember's permissions instead.

See [troubleshooting](troubleshooting.md#sync-isnt-uploading) if a folder isn't syncing.

## Stop syncing a folder

Open the folder and choose **Stop syncing this folder**. Files already in Flare stay there, files still waiting to upload are dropped, and Ember releases its access to folders chosen with the file picker.

Ember also forgets which files it uploaded from the folder. If you add it again with **Everything here**, those files upload a second time. To include older files, [switch the folder to Everything here](#upload-what-was-already-there) instead of adding it again.

Stopping or pausing a folder during a sync, or switching it back to **New files only**, takes effect before the next file. A file that's already uploading finishes first.
