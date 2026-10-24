# Flare test fixture

`bootstrap.py` prepares a Flare server for Ember's integration tests. It uses only the
Python 3 standard library (3.8 or newer).

It does the following, in order:

1. Waits for `GET /api/health` to report `ok` (default timeout 180 seconds).
2. Runs first-run setup if the instance has no accounts yet: admin `admin@ember.test`,
   name `Ember Admin`, local storage, registrations left at Flare's default (open).
3. Signs in with Flare's credentials provider (NextAuth CSRF token, then the
   credentials callback) and keeps the session cookie in memory.
4. Turns OCR off in the instance settings (`--keep-ocr` skips this).
5. Revokes `ember-all` and `ember-upload-only` tokens left by earlier runs
   (`--keep-old-tokens` skips this), then creates them again:
   - `ember-all`: `files:read`, `files:upload`, `urls:read`, `urls:write`
   - `ember-upload-only`: `files:upload`
6. Reads the account upload token, generating one only if the account has none.
7. Finds or creates the top-level folder `Ember sync`.
8. Checks the new tokens with a cookie-free client, then writes the fixture file.

The script prints progress lines only. Token values go to the output file and nowhere
else.

## Run it locally

```sh
python3 scripts/flare-fixture/bootstrap.py \
  --url http://localhost:3300 \
  --out ~/.local/share/ember-flare-fixture/fixture.json
```

Options:

| Option | Default | Meaning |
| --- | --- | --- |
| `--url` | `http://localhost:3300` | Flare base URL |
| `--out` | required | Fixture JSON path, written with mode 0600 |
| `--email` | `admin@ember.test` | Admin email |
| `--password` | `ember-fixture-password-1` | Admin password (at least 8 characters) |
| `--timeout` | `180` | Seconds to wait for `/api/health` |
| `--request-timeout` | `30` | Seconds per HTTP request |
| `--keep-ocr` | off | Leave the OCR setting alone |
| `--keep-old-tokens` | off | Do not revoke `ember-*` tokens from earlier runs |

The script is safe to run again. A second run skips setup, signs in, replaces the two
named tokens, keeps the same account upload token and reuses the folder.

Exit status is 0 on success, 1 when a step fails and 2 for bad arguments. A failure
message names the step, the HTTP status and the server's error text, for example:

```
[flare-fixture] ERROR: sign-in failed: HTTP 401, error CredentialsSignin: wrong email or password. If this instance was set up earlier with a different password, pass --password.
```

## Output

```json
{
  "url": "http://localhost:3300",
  "adminEmail": "admin@ember.test",
  "tokens": {
    "all": "flr_...",
    "uploadOnly": "flr_...",
    "account": "<uuid>"
  },
  "folderId": "<folder id>"
}
```

- `all` and `uploadOnly` are named API tokens. Send them as `Authorization: Bearer <token>`.
- `account` is the legacy account upload token. It carries the account's full permissions
  on bearer-capable routes, including `/api/folders`.

## Run it in CI

`.github/workflows/ci.yml` runs PostgreSQL as a service and calls
`scripts/ci/start-flare.sh`, which starts the published Flare image on the
runner's network and runs this script:

```sh
FLARE_IMAGE=docker.io/flintsh/flare:3.0.0 scripts/ci/start-flare.sh "$RUNNER_TEMP/flare-fixture.json"
```

The live API job then passes the file to `./gradlew :api:integrationTest` with
`FLARE_FIXTURE`, and the emulator jobs read the `all` token from it. The token is
masked in the logs. Do not print the file or upload it as a build artifact.

## Things the tests should expect

- **Upload rate limit.** `POST /api/files` and `POST /api/files/chunks` share a limit of
  30 requests per minute per client. Flare identifies the client by `X-Forwarded-For`,
  then `X-Real-IP`, and otherwise uses one shared key, so every local client shares the
  same 30. Over the limit, Flare returns 429 with `Retry-After: 60`. Restarting Flare
  clears this limit.
- **Sign-in throttle.** Password sign-ins are limited to 30 per account and 100 per IP
  every 15 minutes. Successful sign-ins count too. The counter is stored in the database
  and survives restarts. Each bootstrap run uses one sign-in.
- **Scopes fail as 401, not 403.** A named token used outside its scopes gets
  `401 {"error":"Unauthorized"}`. For example, `ember-upload-only` on `GET /api/files`.
- **Named tokens cannot use `/api/folders`.** Use the account token or a session. To
  upload into the folder, send `X-Upload-Folder: <folderId>` (or `?folderId=`) with
  `POST /api/files`. Named tokens can do that.
- **Size units differ.** The upload response reports `size` in bytes. `GET /api/files`
  reports `size` in MiB.
- **Links use `NEXTAUTH_URL`.** `url`, `pageUrl`, `rawUrl` and `downloadUrl` are built from
  the server's `NEXTAUTH_URL`, not from the request host. On an Android emulator, run
  `adb reverse tcp:3300 tcp:3300` so `http://localhost:3300` inside the emulator reaches
  the host. Otherwise `http://10.0.2.2:3300` reaches the API, but returned links still
  say `localhost`.
- **Paste uploads.** Send `X-Flare-Paste: true` with a normal multipart upload. The
  account needs the `pastes.create` permission, which the fixture admin has.
