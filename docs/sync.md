# Folder sync

Ember watches folders you choose and uploads new files to Flare on its own. Use it to back up your camera, send every screenshot to Flare the moment you take it, or collect recordings and documents from any folder.

## Add a folder

1. Open **Sync** and choose **Add a folder**.
2. Pick a source:
   - **Photo and video albums** list your phone's albums the way Google Photos shows device folders: Camera, Screenshots, Download, and folders other apps create. Ember needs access to photos and videos for these.
   - **Choose a folder** opens Android's file picker for any other folder, such as Recordings or Documents.
3. Choose **Start with**:
   - **New files only** uploads files added from now on. What's already there stays on your phone.
   - **Everything here** uploads the existing files too, oldest first. A large album can take a while. Ember paces uploads to stay within Flare's limits.
4. Set **Where uploads go** and **After each upload**, described below.
5. Choose **Start syncing**. Ember checks the folder immediately.

Photos from Google Photos only sync if they're stored on the phone, which is where the Camera album comes from. Photos that exist only in Google's cloud aren't files on your device.

### Options

| Option | What it does |
| --- | --- |
| **Visibility**, **Expires**, **Folder** | Override your [upload defaults](settings.md#upload-defaults) for this folder. The label shows what the default is. |
| **Copy the link** | Copies each new file's link as soon as it uploads, like a desktop screenshot tool. On by default for Screenshots. |
| **Notify for each file** | Shows a notification with the link for every upload. Otherwise Ember sends one summary per sync. On by default for Screenshots. |
| **Include photos**, **Include videos** | For albums. At least one stays on. |
| **Include subfolders** | For folders from the file picker. Ember looks up to 12 levels deep. |

Change these later by tapping the folder in **Sync**.

## When Ember syncs

- **New photos, videos, and screenshots** start uploading a few seconds after they appear. Android tells Ember when its media library changes.
- **Every folder** is also checked on a schedule: every 15 minutes, hour (the default), 6 hours, or day. Change this in **Settings → Automatic sync**. Folders from the file picker are only checked on this schedule.
- **Sync now**, on the Sync screen, home screen, or launcher shortcut, checks right away.

By default Ember only syncs over Wi-Fi. You can allow mobile data, and choose **Only while charging**. **Sync now** doesn't wait for a charger, but still respects Wi-Fi only. Scheduled checks also wait while your battery is low.

Android limits how long background work may run, so Ember works through a large backlog in stretches of a few minutes and continues on its own. While Ember is open, a sync can run longer with a progress notification.

Turn everything off with the **Automatic sync** switch, or pause one folder with its own switch.

## Which files upload

Each file uploads once. Ember remembers every file it uploaded or Flare permanently rejected, so a file isn't sent twice, including after you disconnect and reconnect.

Ember skips:

- Files still being written. Albums use Android's own marker for this. For other folders, Ember waits until a file hasn't changed for 30 seconds.
- Hidden files, whose names start with a dot, and partial downloads ending in `.tmp`, `.part`, `.crdownload`, `.download`, or `.partial`.
- Empty files.

Editing a file after it uploaded doesn't upload it again. Deleting a file from your phone doesn't delete it from Flare, and deleting it in Flare doesn't remove it from your phone.

For **New files only**, albums use the time a file was added to the phone's media library. Folders from the file picker use each file's modified time, so a file copied in with an old modified time may not count as new. Remove the folder and add it with **Everything here** to include it.

### Photo location data

Photos from albums upload without GPS location data. Android removes it for apps that don't hold the **Access media location** permission, and Ember doesn't ask for it, so public links don't reveal where photos were taken. Files from folders chosen with the file picker upload as they are.

## When something goes wrong

- **Temporary problems**, such as no connection, a server error, or Flare's rate limit, pause the sync. Ember retries with increasing delays and the file uploads later.
- **A file Flare rejects**, for example because it's larger than your instance allows, counts as failed. The folder shows **failed** with a count. Open the folder and choose **Try failed files again** after fixing the cause, such as raising the limit in Flare.
- **A rejected token** stops all sync and shows a notification. Reconnect in **Settings**.
- **Lost access**: if you turn off photo access, or Android revokes access to a picked folder, the folder shows a warning and Ember notifies you. Allow access again, or remove the folder and add it again.
- **Only some photos visible**: on Android 14 and later, choosing **Select photos** when Android asks gives Ember only those photos. Albums can't sync fully that way. Choose **Allow all** in Ember's permissions instead.

See [troubleshooting](troubleshooting.md#sync-isnt-uploading) if a folder isn't syncing.

## Stop syncing a folder

Open the folder and choose **Stop syncing this folder**. Files already in Flare stay there, and Ember releases its access to folders chosen with the file picker.
