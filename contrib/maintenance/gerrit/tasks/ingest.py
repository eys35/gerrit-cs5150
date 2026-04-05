import base64
import json
import logging
import urllib.error
import urllib.parse
import urllib.request
from typing import Optional

from gerrit.db import ReviewActivityStore
from gerrit.models import (
    ChangeRecord,
    FileRecord,
    LabelVoteRecord,
    ReviewerRecord,
)

logger = logging.getLogger(__name__)

_XSSI_PREFIX = b")]}'\n"
# Number of changes to request per REST page.
_PAGE_SIZE = 100
# Commit batch size before flushing to disk.
_FLUSH_EVERY = 500
# Files that carry no reviewer-expertise signal.
_SKIP_FILES = frozenset({"/COMMIT_MSG", "/MERGE_LIST", "/PATCHSET_LEVEL"})


class GerritRestIngestion:
    """Ingest review activity for a list of Gerrit projects.

    Parameters
    ----------
    gerrit_url:
        Base URL of the Gerrit instance, e.g. ``http://localhost:8080``.
        The authenticated REST endpoint (``/a/``) is used automatically
        when *username* and *password* are provided.
    store:
        An open :class:`~gerrit.db.ReviewActivityStore` context.
    projects:
        Iterable of project names to ingest.
    username / password:
        HTTP Basic credentials.  Optional; anonymous access is used when
        omitted.
    """

    def __init__(
        self,
        gerrit_url: str,
        store: ReviewActivityStore,
        projects,
        username: Optional[str] = None,
        password: Optional[str] = None,
    ):
        self.gerrit_url = gerrit_url.rstrip("/")
        self.store = store
        self.projects = list(projects)
        self._auth_header: Optional[str] = None
        if username and password:
            creds = base64.b64encode(f"{username}:{password}".encode()).decode()
            self._auth_header = f"Basic {creds}"
            # Prefer the authenticated REST endpoint for consistent access.
            if "/a" not in self.gerrit_url.split("/")[-1]:
                self.gerrit_url = self.gerrit_url + "/a"

    def run(self, incremental: bool = True) -> None:
        """Ingest all configured projects.

        Parameters
        ----------
        incremental:
            When ``True`` (default), only fetch changes updated since the
            last recorded timestamp for each project.  Pass ``False`` to
            re-ingest everything from scratch.
        """
        for project in self.projects:
            logger.info("Ingesting project: %s", project)
            since = self.store.latest_change_updated(project) if incremental else None
            if since:
                logger.info("  Incremental from: %s", since)
            self._ingest_project(project, since=since)

    def _ingest_project(self, project: str, since: Optional[str]) -> None:
        start = 0
        total = 0
        pending = 0

        while True:
            changes = self._fetch_changes(project, since=since, start=start)
            if not changes:
                break

            for change in changes:
                self._process_change(change)
                total += 1
                pending += 1
                if pending >= _FLUSH_EVERY:
                    self.store.commit()
                    pending = 0

            self.store.commit()
            pending = 0

            if len(changes) < _PAGE_SIZE:
                break
            start += len(changes)

        logger.info("  Done: %d changes ingested for %s", total, project)

    def _fetch_changes(
        self, project: str, since: Optional[str], start: int
    ) -> list:
        query = f"project:{project} -is:wip"
        if since:
            query += f" after:{since}"

        params = [
            ("q", query),
            ("o", "DETAILED_ACCOUNTS"),
            ("o", "DETAILED_LABELS"),
            ("o", "ALL_REVISIONS"),
            ("o", "ALL_FILES"),
            ("n", str(_PAGE_SIZE)),
            ("S", str(start)),
        ]
        return self._get("/changes/", params) or []

    def _get(self, path: str, params: Optional[list] = None) -> Optional[object]:
        url = self.gerrit_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)

        req = urllib.request.Request(url)
        if self._auth_header:
            req.add_header("Authorization", self._auth_header)
        req.add_header("Accept", "application/json")

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            logger.warning("HTTP %s for %s: %s", exc.code, url, exc.reason)
            return None
        except Exception as exc:  # noqa: BLE001
            logger.warning("Request failed for %s: %s", url, exc)
            return None

        if raw.startswith(b")]}'"):
            # Strip the 5-byte XSSI prefix (")]}'\\n").
            raw = raw[5:]

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning("JSON decode error for %s: %s", url, exc)
            return None

    def _process_change(self, c: dict) -> None:
        # use the stable numeric change number as the primary key so that
        # it matches what the Java scorer side can correlate.
        change_key = str(c.get("_number", c.get("change_id", "")))

        owner = c.get("owner") or {}
        self.store.upsert_change(
            ChangeRecord(
                change_id=change_key,
                project=c.get("project", ""),
                branch=c.get("branch", ""),
                owner_account_id=owner.get("_account_id"),
                owner_name=owner.get("name"),
                owner_email=owner.get("email"),
                status=c.get("status", ""),
                created=c.get("created"),
                updated=c.get("updated"),
                submitted=c.get("submitted"),
                insertions=c.get("insertions"),
                deletions=c.get("deletions"),
            )
        )

        self._process_revisions(change_key, c.get("revisions") or {})
        self._process_reviewers(change_key, c.get("reviewers") or {})
        self._process_labels(change_key, c.get("labels") or {})

    def _process_revisions(self, change_key: str, revisions: dict) -> None:
        for _rev_sha, rev_data in revisions.items():
            patchset_number = rev_data.get("_number", 1)
            for file_path, file_info in (rev_data.get("files") or {}).items():
                if file_path in _SKIP_FILES:
                    continue
                self.store.upsert_file(
                    FileRecord(
                        change_id=change_key,
                        patchset_number=patchset_number,
                        file_path=file_path,
                        lines_inserted=file_info.get("lines_inserted"),
                        lines_deleted=file_info.get("lines_deleted"),
                        # REST uses 'status' for the change type letter;
                        # absent means Modified.
                        change_type=file_info.get("status", "M"),
                    )
                )

    def _process_reviewers(self, change_key: str, reviewers_map: dict) -> None:
        for state_key, accounts in reviewers_map.items():
            for acct in accounts:
                acct_id = acct.get("_account_id")
                if acct_id is None:
                    continue
                self.store.upsert_reviewer(
                    ReviewerRecord(
                        change_id=change_key,
                        account_id=acct_id,
                        account_name=acct.get("name"),
                        account_email=acct.get("email"),
                        state=state_key,
                    )
                )

    def _process_labels(self, change_key: str, labels: dict) -> None:
        for label_name, label_info in labels.items():
            for vote in label_info.get("all") or []:
                value = vote.get("value")
                if value is None or value == 0:
                    continue
                acct_id = vote.get("_account_id")
                if acct_id is None:
                    continue
                self.store.upsert_label_vote(
                    LabelVoteRecord(
                        change_id=change_key,
                        account_id=acct_id,
                        label_name=label_name,
                        value=value,
                        date=vote.get("date"),
                    )
                )
