"""Gerrit account-id <-> GitHub login mapping.

Reads a small JSON file shaped like::

    {
      "mappings": {
        "1001": "octocat",
        "1002": "torvalds"
      }
    }

Keys are Gerrit ``Account.Id`` integers (stored as strings because JSON
forbids integer keys); values are GitHub login names.

The mapping is intentionally hand-curated: automating it via Gerrit external
ids or commit-email matching is a follow-up. For a demo, copying
``conf/github_users.json.example`` to ``conf/github_users.json`` and pointing
the ingester at it is enough.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Dict, Optional

logger = logging.getLogger(__name__)


class GitHubIdentityMap:
    """Bidirectional Gerrit account-id <-> GitHub login lookup."""

    def __init__(self, gerrit_to_github: Dict[int, str]):
        self._gerrit_to_github: Dict[int, str] = dict(gerrit_to_github)
        # GitHub logins are case-insensitive; normalize for reverse lookup.
        self._github_to_gerrit: Dict[str, int] = {
            login.lower(): account_id
            for account_id, login in self._gerrit_to_github.items()
        }

    @classmethod
    def empty(cls) -> "GitHubIdentityMap":
        return cls({})

    @classmethod
    def from_file(cls, path: str) -> "GitHubIdentityMap":
        if not os.path.exists(path):
            logger.warning(
                "GitHub identity map %s not found; ingest will skip every login", path
            )
            return cls.empty()
        with open(path, "r", encoding="utf-8") as fh:
            payload = json.load(fh)
        return cls.from_dict(payload)

    @classmethod
    def from_dict(cls, payload: dict) -> "GitHubIdentityMap":
        raw = payload.get("mappings") or {}
        if not isinstance(raw, dict):
            raise ValueError("'mappings' must be an object of {account_id: login}")
        out: Dict[int, str] = {}
        for k, v in raw.items():
            try:
                account_id = int(k)
            except (TypeError, ValueError) as exc:
                raise ValueError(
                    f"identity map key {k!r} is not a valid integer account id"
                ) from exc
            if not isinstance(v, str) or not v.strip():
                raise ValueError(
                    f"identity map value for {k!r} must be a non-empty string"
                )
            out[account_id] = v.strip()
        return cls(out)

    def github_for(self, account_id: int) -> Optional[str]:
        return self._gerrit_to_github.get(account_id)

    def gerrit_for(self, github_login: str) -> Optional[int]:
        if not github_login:
            return None
        return self._github_to_gerrit.get(github_login.lower())

    def github_logins(self):
        return list(self._gerrit_to_github.values())

    def __len__(self) -> int:
        return len(self._gerrit_to_github)

    def __contains__(self, github_login: str) -> bool:
        return self.gerrit_for(github_login) is not None
