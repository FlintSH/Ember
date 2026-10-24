#!/usr/bin/env python3
"""Prepare a Flare server for Ember integration tests.

Waits for the server, completes first-run setup when needed, signs in as the
fixture administrator, creates named API tokens, reads the account upload
token, ensures the "Ember sync" folder exists and writes everything the tests
need to a JSON file with mode 0600.

Standard library only. Token values are written to --out and never printed.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import re
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

DEFAULT_URL = "http://localhost:3300"
DEFAULT_EMAIL = "admin@ember.test"
DEFAULT_PASSWORD = "ember-fixture-password-1"
ADMIN_NAME = "Ember Admin"
FOLDER_NAME = "Ember sync"
USER_AGENT = "ember-flare-fixture-bootstrap/1"

# Output key -> (token name, scopes). Scope names come from Flare's
# lib/integrations/tokens.ts API_SCOPES.
NAMED_TOKENS = {
    "all": ("ember-all", ["files:read", "files:upload", "urls:read", "urls:write"]),
    "uploadOnly": ("ember-upload-only", ["files:upload"]),
}

SECRET_PATTERNS = [
    re.compile(r"flr_[A-Za-z0-9_-]+"),
    re.compile(r"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b"),
]


class StepError(Exception):
    """A bootstrap step failed. The message is safe to print."""


def log(message: str) -> None:
    print(f"[flare-fixture] {message}", flush=True)


def redact(text: str) -> str:
    for pattern in SECRET_PATTERNS:
        text = pattern.sub("[redacted]", text)
    return text


def parse_json(body: bytes):
    try:
        return json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        return None


def server_error_text(body: bytes) -> str:
    """Extract the server's error message without echoing secrets."""
    data = parse_json(body)
    if isinstance(data, dict):
        parts = []
        for key in ("error", "message"):
            value = data.get(key)
            if isinstance(value, str) and value:
                parts.append(value)
                break
        if isinstance(data.get("field"), str):
            parts.append(f"(field: {data['field']})")
        if isinstance(data.get("url"), str):
            parts.append(f"(url: {data['url']})")
        if parts:
            return redact(" ".join(parts))
    text = body.decode("utf-8", errors="replace").strip()
    text = re.sub(r"\s+", " ", text)
    if not text:
        return "(empty response body)"
    return redact(text[:300] + ("..." if len(text) > 300 else ""))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Surface 3xx responses instead of silently following them."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Response:
    def __init__(self, status: int, body: bytes, headers):
        self.status = status
        self.body = body
        self.headers = headers

    def json(self):
        return parse_json(self.body)


class FlareClient:
    def __init__(self, base_url: str, request_timeout: float, cookies: bool = True):
        self.base_url = base_url
        self.request_timeout = request_timeout
        self.jar = http.cookiejar.CookieJar()
        handlers: list[urllib.request.BaseHandler] = [NoRedirect()]
        if cookies:
            handlers.append(urllib.request.HTTPCookieProcessor(self.jar))
        self.opener = urllib.request.build_opener(*handlers)

    def request(
        self,
        method: str,
        path: str,
        *,
        json_body=None,
        form=None,
        headers=None,
        timeout=None,
        retry_rate_limit: bool = True,
    ) -> Response:
        url = self.base_url + path
        data = None
        request_headers = {"Accept": "application/json", "User-Agent": USER_AGENT}
        if json_body is not None:
            data = json.dumps(json_body).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode("utf-8")
            request_headers["Content-Type"] = "application/x-www-form-urlencoded"
        request_headers.update(headers or {})

        attempts = 3 if retry_rate_limit else 1
        attempt = 1
        while True:
            req = urllib.request.Request(url, data=data, method=method, headers=request_headers)
            try:
                with self.opener.open(req, timeout=timeout or self.request_timeout) as resp:
                    result = Response(resp.status, resp.read(), resp.headers)
            except urllib.error.HTTPError as error:
                result = Response(error.code, error.read(), error.headers)
            except (urllib.error.URLError, OSError) as error:
                reason = getattr(error, "reason", error)
                raise StepError(f"{method} {path}: could not reach {self.base_url} ({reason})") from None

            if result.status != 429 or attempt >= attempts:
                return result
            retry_after = result.headers.get("Retry-After", "")
            wait = int(retry_after) if retry_after.isdigit() else 60
            wait = max(1, min(wait, 65))
            log(f"{method} {path} was rate limited (HTTP 429); retrying in {wait}s")
            time.sleep(wait)
            attempt += 1

    def session_cookie_present(self) -> bool:
        return any("next-auth.session-token" in cookie.name for cookie in self.jar)


def expect(response: Response, step: str, statuses=(200,)) -> Response:
    if response.status not in statuses:
        raise StepError(f"{step} failed: HTTP {response.status}: {server_error_text(response.body)}")
    return response


def expect_json_object(response: Response, step: str) -> dict:
    data = response.json()
    if not isinstance(data, dict):
        raise StepError(f"{step}: expected a JSON object, got: {server_error_text(response.body)}")
    return data


def wait_for_health(client: FlareClient, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    log(f"waiting for {client.base_url}/api/health (timeout {int(timeout)}s)")
    last = "no response yet"
    next_progress = time.monotonic() + 15
    while True:
        try:
            response = client.request("GET", "/api/health", timeout=5, retry_rate_limit=False)
            data = response.json()
            if response.status == 200 and isinstance(data, dict):
                inner = data.get("data")
                status = inner.get("status") if isinstance(inner, dict) else data.get("status")
                if status == "ok":
                    log("server is healthy")
                    return
            last = f"HTTP {response.status}: {server_error_text(response.body)}"
        except StepError as error:
            last = str(error)
        now = time.monotonic()
        if now >= deadline:
            raise StepError(f"server did not become healthy within {int(timeout)}s (last result: {last})")
        if now >= next_progress:
            log(f"still waiting for health ({last})")
            next_progress = now + 15
        time.sleep(min(2.0, max(0.0, deadline - now)))


def ensure_setup(client: FlareClient, email: str, password: str) -> None:
    response = expect(client.request("GET", "/api/setup/check"), "setup check (GET /api/setup/check)")
    completed = expect_json_object(response, "setup check").get("completed")
    if completed is True:
        log("instance is already set up; skipping first-run setup")
        return

    log(f"running first-run setup (admin {email}, local storage)")
    payload = {
        "admin": {"name": ADMIN_NAME, "email": email, "password": password},
        "storage": {
            "provider": "local",
            "s3": {
                "bucket": "",
                "region": "",
                "accessKeyId": "",
                "secretAccessKey": "",
                "endpoint": "",
                "forcePathStyle": False,
            },
        },
        "registrations": {"enabled": True, "disabledMessage": ""},
    }
    response = client.request("POST", "/api/setup", json_body=payload)
    if response.status == 400 and "already completed" in server_error_text(response.body).lower():
        log("setup was completed by another request; continuing")
        return
    expect(response, "first-run setup (POST /api/setup)")
    data = expect_json_object(response, "first-run setup")
    if data.get("success") is not True:
        raise StepError(f"first-run setup returned an unexpected body: {server_error_text(response.body)}")
    log("first-run setup complete")


SIGN_IN_HINTS = {
    "CredentialsSignin": "wrong email or password. If this instance was set up earlier with a different password, pass --password.",
    "TooManyAttempts": "Flare throttles password sign-ins to 30 per account (and 100 per IP) per 15 minutes, counting successful ones. Wait 15 minutes or clear the AuthRateLimit table.",
    "TwoFactorRequired": "the account has two-factor authentication enabled; this script cannot sign in to it.",
    "PasskeyRequired": "the account requires a passkey; this script cannot sign in to it.",
}


def sign_in(client: FlareClient, email: str, password: str) -> dict:
    log("signing in with the credentials provider")
    response = expect(client.request("GET", "/api/auth/csrf"), "CSRF token (GET /api/auth/csrf)")
    csrf = expect_json_object(response, "CSRF token").get("csrfToken")
    if not isinstance(csrf, str) or not csrf:
        raise StepError("CSRF token (GET /api/auth/csrf): response had no csrfToken")

    response = client.request(
        "POST",
        "/api/auth/callback/credentials",
        form={"csrfToken": csrf, "email": email, "password": password, "json": "true"},
        retry_rate_limit=False,
    )
    data = response.json() if response.body else None
    target = data.get("url", "") if isinstance(data, dict) else ""
    query = urllib.parse.parse_qs(urllib.parse.urlparse(target).query) if target else {}
    error_code = (query.get("error") or [""])[0]
    if error_code:
        hint = SIGN_IN_HINTS.get(error_code, "see the Flare server log for details.")
        raise StepError(f"sign-in failed: HTTP {response.status}, error {error_code}: {hint}")
    if query.get("csrf") == ["true"]:
        raise StepError("sign-in failed: Flare rejected the CSRF token (the csrf cookie was not sent back). Check that --url uses the same host and scheme as the server.")
    if response.status != 200 or not client.session_cookie_present():
        raise StepError(f"sign-in failed: HTTP {response.status}, no session cookie was set: {server_error_text(response.body)}")

    response = expect(client.request("GET", "/api/auth/session"), "session check (GET /api/auth/session)")
    session = expect_json_object(response, "session check")
    user = session.get("user") if isinstance(session.get("user"), dict) else None
    if not user or str(user.get("email", "")).lower() != email.lower():
        raise StepError("session check: the session cookie did not produce a signed-in session for the admin account")
    if user.get("emailAccessRequired"):
        raise StepError("session check: Flare requires email verification for this account before it can use tokens")
    roles = ", ".join(str(role.get("name")) for role in user.get("roles", []) if isinstance(role, dict)) or "none"
    log(f"signed in as {user.get('email')} (roles: {roles})")
    permissions = set(user.get("permissions") or [])
    needed = {"tokens.manage", "folders.manage", "files.read", "files.upload", "links.read", "links.create", "pastes.create"}
    if "administrator" not in permissions and not needed <= permissions:
        log("warning: account is missing permissions: " + ", ".join(sorted(needed - permissions)))
    return user


def disable_ocr(client: FlareClient) -> None:
    response = client.request(
        "PATCH",
        "/api/settings",
        json_body={"settings": {"general": {"ocr": {"enabled": False}}}},
    )
    expect(response, "disable OCR (PATCH /api/settings)")
    log("OCR disabled in instance settings")


def revoke_previous_tokens(client: FlareClient) -> None:
    names = {name for name, _ in NAMED_TOKENS.values()}
    response = expect(client.request("GET", "/api/integrations"), "list tokens (GET /api/integrations)")
    tokens = expect_json_object(response, "list tokens").get("tokens") or []
    previous = [
        token for token in tokens
        if isinstance(token, dict) and token.get("name") in names and not token.get("revokedAt")
    ]
    for token in previous:
        response = client.request("POST", "/api/integrations", json_body={"action": "revoke-token", "id": token["id"]})
        if response.status == 400 and "already revoked" in server_error_text(response.body):
            continue
        expect(response, f"revoke previous token {token.get('name')} (POST /api/integrations)")
    if previous:
        log(f"revoked {len(previous)} token(s) left by earlier runs")


def create_named_token(client: FlareClient, name: str, scopes) -> str:
    response = client.request(
        "POST",
        "/api/integrations",
        json_body={"action": "create-token", "name": name, "scopes": list(scopes)},
    )
    expect(response, f"create token {name} (POST /api/integrations)")
    data = expect_json_object(response, f"create token {name}")
    secret = data.get("secret")
    token = data.get("token")
    if not isinstance(token, dict):
        token = {}
    if not isinstance(secret, str) or not secret.startswith("flr_"):
        raise StepError(f"create token {name}: response did not include a flr_ secret")
    log(f"created token {name} (id {token.get('id')}, scopes: {', '.join(token.get('scopes') or scopes)})")
    return secret


def account_upload_token(client: FlareClient) -> str:
    response = expect(client.request("GET", "/api/profile/upload-token"), "read upload token (GET /api/profile/upload-token)")
    value = expect_json_object(response, "read upload token").get("uploadToken")
    if isinstance(value, str) and value:
        log("read the existing account upload token")
        return value
    response = expect(client.request("POST", "/api/profile/upload-token"), "generate upload token (POST /api/profile/upload-token)")
    value = expect_json_object(response, "generate upload token").get("uploadToken")
    if not isinstance(value, str) or not value:
        raise StepError("generate upload token: response did not include uploadToken")
    log("generated a new account upload token")
    return value


def find_folder(client: FlareClient):
    response = expect(client.request("GET", "/api/folders"), "list folders (GET /api/folders)")
    folders = expect_json_object(response, "list folders").get("data") or []
    for folder in folders:
        if isinstance(folder, dict) and folder.get("name") == FOLDER_NAME and folder.get("parentId") is None:
            return folder.get("id")
    return None


def ensure_folder(client: FlareClient) -> str:
    folder_id = find_folder(client)
    if folder_id:
        log(f"reusing folder '{FOLDER_NAME}' (id {folder_id})")
        return folder_id
    response = client.request("POST", "/api/folders", json_body={"name": FOLDER_NAME, "parentId": None})
    if response.status == 409:
        folder_id = find_folder(client)
        if folder_id:
            log(f"reusing folder '{FOLDER_NAME}' (id {folder_id})")
            return folder_id
    expect(response, f"create folder '{FOLDER_NAME}' (POST /api/folders)")
    data = expect_json_object(response, "create folder").get("data")
    folder_id = data.get("id") if isinstance(data, dict) else None
    if not folder_id:
        raise StepError(f"create folder '{FOLDER_NAME}': response did not include an id")
    log(f"created folder '{FOLDER_NAME}' (id {folder_id})")
    return folder_id


def check_tokens(base_url: str, request_timeout: float, tokens: dict, folder_id: str) -> None:
    """Quick sanity checks with a cookie-free client, so only the token counts."""
    client = FlareClient(base_url, request_timeout, cookies=False)

    def bearer(value):
        return {"Authorization": f"Bearer {value}"}

    expect(client.request("GET", "/api/files?limit=1", headers=bearer(tokens["all"])), "check ember-all token (GET /api/files)")
    response = client.request("GET", "/api/files?limit=1", headers=bearer(tokens["uploadOnly"]))
    if response.status != 401:
        raise StepError(f"check ember-upload-only token: expected HTTP 401 for GET /api/files, got {response.status}")
    response = expect(client.request("GET", "/api/folders", headers=bearer(tokens["account"])), "check account token (GET /api/folders)")
    ids = [folder.get("id") for folder in (expect_json_object(response, "check account token").get("data") or []) if isinstance(folder, dict)]
    if folder_id not in ids:
        raise StepError("check account token: GET /api/folders did not include the Ember sync folder")
    log("token checks passed")


def write_secret_json(path: str, payload: dict) -> None:
    path = os.path.abspath(path)
    directory = os.path.dirname(path)
    os.makedirs(directory, exist_ok=True)
    # mkstemp creates the file with mode 0600; os.replace keeps that mode.
    fd, temp_path = tempfile.mkstemp(prefix=".flare-fixture-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temp_path, 0o600)
        os.replace(temp_path, path)
    except BaseException:
        try:
            os.unlink(temp_path)
        except FileNotFoundError:
            pass
        raise


def normalize_url(value: str) -> str:
    parsed = urllib.parse.urlparse(value.strip())
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        raise argparse.ArgumentTypeError(f"not an http(s) URL: {value!r}")
    return value.strip().rstrip("/")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Prepare a Flare server for Ember integration tests.")
    parser.add_argument("--url", type=normalize_url, default=DEFAULT_URL, help=f"Flare base URL (default {DEFAULT_URL})")
    parser.add_argument("--out", required=True, help="where to write the fixture JSON (mode 0600)")
    parser.add_argument("--email", default=DEFAULT_EMAIL, help=f"admin email (default {DEFAULT_EMAIL})")
    parser.add_argument("--password", default=DEFAULT_PASSWORD, help="admin password (default: the fixture password)")
    parser.add_argument("--timeout", type=float, default=180, help="seconds to wait for /api/health (default 180)")
    parser.add_argument("--request-timeout", type=float, default=30, help="seconds per HTTP request (default 30)")
    parser.add_argument("--keep-ocr", action="store_true", help="leave the instance OCR setting unchanged")
    parser.add_argument("--keep-old-tokens", action="store_true", help="do not revoke ember-* tokens from earlier runs")
    args = parser.parse_args(argv)

    if len(args.password) < 8:
        parser.error("--password must be at least 8 characters (Flare's setup rule)")

    client = FlareClient(args.url, args.request_timeout)
    try:
        wait_for_health(client, args.timeout)
        ensure_setup(client, args.email, args.password)
        sign_in(client, args.email, args.password)
        if not args.keep_ocr:
            disable_ocr(client)
        if not args.keep_old_tokens:
            revoke_previous_tokens(client)
        tokens = {key: create_named_token(client, name, scopes) for key, (name, scopes) in NAMED_TOKENS.items()}
        tokens["account"] = account_upload_token(client)
        folder_id = ensure_folder(client)
        check_tokens(args.url, args.request_timeout, tokens, folder_id)
        write_secret_json(
            args.out,
            {"url": args.url, "adminEmail": args.email, "tokens": tokens, "folderId": folder_id},
        )
    except StepError as error:
        print(f"[flare-fixture] ERROR: {error}", file=sys.stderr, flush=True)
        return 1
    except KeyboardInterrupt:
        print("[flare-fixture] interrupted", file=sys.stderr, flush=True)
        return 130
    log(f"wrote {os.path.abspath(args.out)} (mode 0600)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
