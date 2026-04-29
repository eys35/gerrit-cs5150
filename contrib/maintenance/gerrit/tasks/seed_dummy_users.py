"""Create dummy Gerrit accounts that match GitHub logins, write identity map.

Closes the demo loop: without this, the GitHub ingester writes rows tagged
with Gerrit ``Account.Id`` values that don't correspond to any account the
running Gerrit JVM knows about, so the reviewer recommender never surfaces
them. Running ``seed-dummy-users`` once before the first ingest is enough to
make the slider visibly affect rankings.

For each GitHub login passed on the CLI:

  1. PUT /a/accounts/<login> with a generated email + display name. Gerrit
     creates the account and returns the assigned ``_account_id``.
  2. If the username already exists, GET /a/accounts/<login> to recover the
     existing id (idempotent).
  3. Append/update ``conf/github_users.json`` with the resulting
     ``account_id -> login`` mapping.

Authenticated REST is required because account creation isn't anonymous.
We're not trying to bypass that: this is a developer/demo helper, not a
production tool.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import urllib.error
import urllib.parse
import urllib.request
from typing import Dict, Iterable, Optional, Tuple

logger = logging.getLogger(__name__)

_XSSI_PREFIX = b")]}'\n"


class DummyUserSeeder:
    """Creates dummy accounts and updates the identity map.

    Parameters
    ----------
    gerrit_url:
        Base URL of the target Gerrit. The ``/a/`` authenticated prefix is
        added automatically when credentials are supplied.
    username / password:
        HTTP Basic credentials of an admin (or anyone with the
        ``Create Account`` capability). Required because account creation is
        always authenticated.
    identity_map_path:
        Path to the JSON identity-map file. Created if missing, merged in
        place if present.
    email_domain:
        Used to synthesize an email address for each dummy account, e.g.
        ``octocat@<email_domain>``. Defaults to ``example.invalid``.
    """

    def __init__(
        self,
        gerrit_url: str,
        username: str,
        password: str,
        identity_map_path: str,
        email_domain: str = "example.com",
    ):
        if not username or not password:
            raise ValueError("seed-dummy-users requires --username and --password")
        self.gerrit_url = gerrit_url.rstrip("/")
        if "/a" not in self.gerrit_url.split("/")[-1]:
            self.gerrit_url = self.gerrit_url + "/a"
        creds = base64.b64encode(f"{username}:{password}".encode()).decode()
        self._auth_header = f"Basic {creds}"
        self.identity_map_path = identity_map_path
        self.email_domain = email_domain

    def run(self, logins: Iterable[str]) -> Dict[int, str]:
        existing = self._load_existing_map()
        # Reverse for a quick "is this login already mapped?" check.
        already_mapped = {v.lower(): k for k, v in existing.items()}

        for login in logins:
            login = (login or "").strip()
            if not login:
                continue
            if login.lower() in already_mapped:
                logger.info(
                    "Login %s already mapped to account %d; skipping",
                    login,
                    already_mapped[login.lower()],
                )
                continue

            account_id, created = self._ensure_account(login)
            if account_id is None:
                logger.warning("Could not create or look up account for %s", login)
                continue
            existing[account_id] = login
            already_mapped[login.lower()] = account_id
            logger.info(
                "%s account_id=%d for login %s",
                "Created" if created else "Found existing",
                account_id,
                login,
            )

        self._write_map(existing)
        return existing

    # --- account REST ---------------------------------------------------

    def _ensure_account(self, login: str) -> Tuple[Optional[int], bool]:
        """Return ``(account_id, created)``; ``account_id`` is ``None`` on failure."""
        existing_id = self._get_account_id(login)
        if existing_id is not None:
            return existing_id, False

        body = {
            "name": _humanize(login),
            "email": f"{login}@{self.email_domain}",
            # No SSH key, no HTTP password - this is a placeholder identity for
            # the recommender to hang external activity off of.
        }
        info = self._put(f"/accounts/{urllib.parse.quote(login, safe='')}", body)
        if info is None:
            return None, False
        return int(info.get("_account_id", 0)) or None, True

    def _get_account_id(self, login: str) -> Optional[int]:
        info = self._get(f"/accounts/{urllib.parse.quote(login, safe='')}")
        if info is None:
            return None
        return int(info.get("_account_id", 0)) or None

    # --- identity-map IO -----------------------------------------------

    def _load_existing_map(self) -> Dict[int, str]:
        if not os.path.exists(self.identity_map_path):
            return {}
        with open(self.identity_map_path, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        raw = payload.get("mappings") or {}
        out: Dict[int, str] = {}
        for k, v in raw.items():
            try:
                out[int(k)] = str(v)
            except (TypeError, ValueError):
                logger.warning("Ignoring invalid identity-map entry %r -> %r", k, v)
        return out

    def _write_map(self, mapping: Dict[int, str]) -> None:
        os.makedirs(
            os.path.dirname(os.path.abspath(self.identity_map_path)) or ".",
            exist_ok=True,
        )
        # Sort numerically by account id for stable diffs.
        sorted_items = sorted(mapping.items(), key=lambda kv: kv[0])
        payload = {
            "_comment": [
                "Generated by `gerrit-maintenance projects seed-dummy-users`.",
                "Maps Gerrit Account.Id -> GitHub login. Edit by hand if",
                "you want to attach more logins to existing accounts.",
            ],
            "mappings": {str(k): v for k, v in sorted_items},
        }
        tmp = self.identity_map_path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, indent=2)
            fh.write("\n")
        os.replace(tmp, self.identity_map_path)
        logger.info(
            "Wrote %d identity-map entries to %s",
            len(sorted_items),
            self.identity_map_path,
        )

    # --- HTTP -----------------------------------------------------------

    def _request(
        self,
        method: str,
        path: str,
        body: Optional[dict] = None,
    ) -> Optional[dict]:
        url = self.gerrit_url + path
        data: Optional[bytes] = None
        if body is not None:
            data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Authorization", self._auth_header)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            # 404 from GET /accounts/<login> just means "not yet created".
            if method == "GET" and exc.code == 404:
                return None
            detail = ""
            try:
                body = exc.read().decode("utf-8", errors="replace").strip()
                if body:
                    detail = f" body={body}"
            except Exception:  # noqa: BLE001
                pass
            logger.warning(
                "HTTP %s %s -> %s: %s%s", method, url, exc.code, exc.reason, detail
            )
            return None
        except Exception as exc:  # noqa: BLE001
            logger.warning("Request failed: %s %s: %s", method, url, exc)
            return None

        if raw.startswith(_XSSI_PREFIX):
            raw = raw[len(_XSSI_PREFIX):]
        if not raw:
            return {}
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning("Cannot decode JSON from %s %s: %s", method, url, exc)
            return None

    def _get(self, path: str) -> Optional[dict]:
        return self._request("GET", path)

    def _put(self, path: str, body: dict) -> Optional[dict]:
        return self._request("PUT", path, body=body)


def _humanize(login: str) -> str:
    """Turn ``the-octocat`` into ``The Octocat`` for a vaguely-real-looking display name."""
    parts = [p for p in login.replace("_", "-").split("-") if p]
    if not parts:
        return login
    return " ".join(p[:1].upper() + p[1:] for p in parts)
