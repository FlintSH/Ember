# Architecture

Ember is a Kotlin app built with Jetpack Compose. It has three Gradle modules.

| Module | What it is |
| --- | --- |
| `:api` | A plain Kotlin (JVM) client for Flare's HTTP API, with no Android dependencies. It runs unchanged in unit tests and against a real Flare server. |
| `:app` | The Android app: UI, storage, background work, and Android integrations. |
| `:e2e` | Black-box UI Automator tests that drive an installed APK. See [testing](testing.md). |

## The Flare client (`:api`)

`FlareClient` wraps the routes a named token can use, plus `GET /api/folders` for the account upload token:

- `probeCapabilities()` checks access without side effects ([the requests](getting-started.md#connect)).
- `upload()` streams a file as one multipart request, or through the chunked upload API for files of 8 MiB or more, in 8 MiB parts that retry on their own. Next.js, which Flare runs on, cuts request bodies at 10 MiB, so no request or part may be larger. Pastes always use one request, because only it carries Flare's `X-Flare-Paste` flag.
- `listFiles()`, `createShortLink()`, `listShortLinks()`, `deleteShortLink()`, `listFolders()`.

Details that matter:

- **One-shot bodies.** Multipart bodies are marked one-shot so OkHttp never silently repeats a request that could create a second file.
- **Type checks.** `MimeTypes` sniffs file signatures and applies the same equivalence rules as Flare's `lib/security/file-validation.ts`, so uploads aren't rejected for a provider's wrong or generic type.
- **Idle connections.** Flare runs on Node, which closes idle keep-alive connections after 5 seconds, while OkHttp rechecks a pooled connection before a POST only after 10. `forFlare()` drops idle connections after 4 seconds, so an upload never goes out on a connection Flare already closed.
- **Rate limits.** `UploadStartLimiter` keeps upload starts under Flare's 30 per minute. A 429 can arrive while a body is still being written and surface as a broken connection, so Ember paces itself instead of relying on the error. One limiter is shared per server.
- **Errors.** `FlareException` subclasses map Flare's status codes and keep its `error` text for display.
- **Android compatibility.** The module compiles against the JDK 17 API, and the app's lint checks it against Android API levels, so it can't call newer JDK methods that old Android versions lack.

## The app (`:app`)

```text
ui/            Compose screens, the share sheet, and the Flare design system
data/
  account/     Connected account; token encrypted with the Android Keystore
  settings/    DataStore preferences and upload presets
  db/          Room: upload history and queue, sync sources, sync ledger, short links
  uploads/     Staging shared files, queueing, and the Uploader
  sync/        Scanners, the pure SyncPlanner, and the SyncEngine
  links/       Short links
work/          WorkManager workers, scheduling, notifications
system/        Clipboard, local network detection, permissions, tile, shortcuts
```

`AppContainer` wires everything together without a DI framework.

### Uploads

Shares, pastes, and picked files become rows in the `uploads` table and run in `UploadWorker`, a unique WorkManager chain that processes one upload at a time. Shared files are staged first, because Android revokes another app's read grant when the share sheet closes. The worker promotes itself to a foreground service while Android allows it, and otherwise runs as a regular job.

`Uploader` sends one row and records the outcome:

- Temporary failures put the row back in the queue and the worker returns `retry()`, so WorkManager backs off. When Flare can't be reached at all (`Failure.unreachable`), the attempt doesn't count. Other temporary failures give up after 8 attempts, or 10 for sync.
- A rejected token keeps rows queued and records an `AccountProblem`, which Home shows with **Reconnect**. A token the Keystore can no longer decrypt is recorded the same way.
- Permanent failures are recorded with a readable message: Flare's own text, or one of Ember's for local problems such as a full disk.
- Once Flare has a file, the bookkeeping runs under `NonCancellable`, so stopping the work at that moment doesn't send the file again.
- The sealed password stays on a row until it uploads or is removed, so retries keep it. If it can't be decrypted, the upload fails instead of going out without it.

Uploads blocked by something outside Ember, like Android 17's local network access, resume when Ember next comes to the foreground.

### Sync

`SyncEngine.run()` holds a process-wide lock, so periodic, media-triggered, and manual runs never upload the same file twice. For each enabled source it:

1. Scans: `MediaStoreScanner` queries an album's bucket, and `DocumentTreeScanner` walks a picked folder's document tree.
2. Plans with `SyncPlanner`, a pure function: skip files in the ledger, files older than the source's start, hidden or partial files, and files still being written; order oldest first. The start, `sinceMillis`, is `0` for **Everything here** and the time the folder was added for **New files only**, compared in whole seconds because MediaStore records them. A file with a queued upload, waiting to retry or resume, is planned whatever the start, and an album with one is scanned in full.
3. Uploads each file through `Uploader` and records it in the ledger as uploaded, permanently failed, or skipped (cancelled). Temporary failures stay out of the ledger and retry on a later run, after fresh files. A run stops early when Flare is out of reach or several files fail in a row. Deleted files aren't recorded, so a file on a drive that comes back still uploads.

Across sources, fresh files go before ones that failed before, and within each group, new files go before files that were already in a folder when it was added. Those older files upload without copying their link or notifying one by one, and the run's summary counts them.

Switching a folder between **New files only** and **Everything here** only moves its start, so it only affects files Ember hasn't started. The ledger stays, so files that already uploaded aren't sent again. Going back calls `SyncEngine.recount()`, which scans and plans the folder without uploading, so its waiting count is right even while sync is off or waiting for Wi-Fi.

One source that can't be scanned records why and the others continue. Before each file the engine re-reads its source, so pausing or removing a folder, or moving its start later, takes effect before the next file. Each run first puts rows cut off by process death back in the queue, and drops unfinished rows whose folder or file is gone.

`SyncWorker` runs the engine for at most 8 minutes, or 30 while in the foreground, then schedules a continuation. Continuing a sync someone started counts as user-initiated too, so it finishes while automatic sync is off. Scheduling uses three kinds of work: a periodic safety net, a one-time job triggered by MediaStore changes that re-arms itself after each run, and expedited jobs for **Sync now**.

### The share sheet

`ShareActivity` is a translucent activity that draws its own bottom sheet. It handles `SEND`, `SEND_MULTIPLE`, `PROCESS_TEXT` (through the `ProcessTextAlias`), and the Quick Settings tile's clipboard action. For editable text selections it returns the link with `setResult`, which replaces the selection.

## Design

Ember follows Flare's design system so the two feel like one product:

- Colors are Flare's `DARK_PALETTE` and `LIGHT_PALETTE` from `lib/customization/schema.ts`, mapped onto Material 3 roles in `ui/theme/Color.kt`. Dark is the default, as on a new Flare instance.
- Type is Inter, Flare's typeface, from one variable font file, with ExtraBold for the wordmark.
- Shapes follow Flare's `0.75rem` radius: 16dp cards, 10dp buttons and fields.
- Icons are Lucide, the set Flare's web interface uses, generated into `ui/icons/Lucide.kt` by `scripts/icons/generate_lucide.py`.
- Components mirror Flare's: bordered cards, uppercase eyebrows, light primary buttons, muted tab tracks, dashed hint cards, and the soft top glow. The flame gradient marks brand moments and progress.

The Ember mark is an outlined flame holding a glowing ember, drawn in Flare's flame gradient: `docs/brand/ember-mark.svg`, `ui/components/Brand.kt`, and the adaptive launcher icon in `res/drawable`.
