"""Ingest GitHub PR + review activity into the local SQLite store.

The output rows reuse the same tables (``changes``, ``files``, ``reviewers``,
``label_votes``) the Gerrit ingester writes, distinguished by
``source = 'github'``. The Java reviewer scorer can then read both sources
through a single query.

Mapping conventions::

    PR             -> ChangeRecord(change_id="gh:{owner}/{repo}#{number}",
                                   project="{owner}/{repo}",
                                   source="github")
    PR file        -> FileRecord(patchset_number=1, source="github")
    PR review      -> ReviewerRecord(state="REVIEWER")
                    + LabelVoteRecord(label_name="Code-Review",
                                      value=+1/-1 from APPROVED/CHANGES_REQUESTED)
    Requested rvr  -> ReviewerRecord(state="REVIEWER")  (no vote)

PRs whose author or reviewer has no entry in the identity map are recorded
with ``owner_account_id = None`` (for changes) or skipped (for reviewers/votes)
because the Java scorer keys everything on ``Account.Id``.

Pure stdlib so the module stays drop-in alongside the existing Gerrit
ingester (no ``requests`` dependency).
"""

from __future__ import annotations

import base64
import json
import logging
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Iterable, Optional

from gerrit.db import ReviewActivityStore
from gerrit.identity_map import GitHubIdentityMap
from gerrit.models import (
    ChangeRecord,
    FileRecord,
    LabelVoteRecord,
    ReviewerRecord,
)

logger = logging.getLogger(__name__)

SOURCE = "github"

_DEFAULT_BASE_URL = "https://api.github.com"
_PER_PAGE = 100
# Map a GitHub review state to a synthetic Code-Review label vote so the
# existing engagement scorer (which keys on label-vote presence + sign) lights
# up. COMMENTED reviews carry no vote signal and are intentionally dropped.
_REVIEW_STATE_TO_VOTE = {
    "APPROVED": 1,
    "CHANGES_REQUESTED": -1,
    "DISMISSED": 0,
}


def _pr_change_id(owner: str, repo: str, number: int) -> str:
    return f"gh:{owner}/{repo}#{number}"


class GitHubRestIngestion:
    """Pull recent PR activity for a list of GitHub repos.

    Parameters
    ----------
    repos:
        Iterable of ``"owner/repo"`` strings.
    store:
        An open :class:`gerrit.db.ReviewActivityStore`.
    identity_map:
        Mapping of GitHub login -> Gerrit account id. Logins missing from the
        map are dropped on a per-row basis (with a debug log). Pass
        :meth:`GitHubIdentityMap.empty` to disable mapping (useful for
        smoke-testing the network path).
    token:
        A GitHub personal access token / app token. Optional; without one
        you're limited to 60 unauthenticated requests/hr.
    base_url:
        Override for GitHub Enterprise installs. Defaults to
        ``https://api.github.com``.
    """

    def __init__(
        self,
        repos: Iterable[str],
        store: ReviewActivityStore,
        identity_map: GitHubIdentityMap,
        token: Optional[str] = None,
        base_url: str = _DEFAULT_BASE_URL,
    ):
        self.repos = [r.strip() for r in repos if r and r.strip()]
        self.store = store
        self.identity_map = identity_map
        self.base_url = base_url.rstrip("/")
        self._auth_header: Optional[str] = None
        if token:
            # GitHub accepts both `token <pat>` and `Bearer <pat>` for
            # classic PATs and fine-grained tokens; Bearer is the documented
            # spelling for fine-grained / GitHub App tokens.
            self._auth_header = f"Bearer {token}"
        elif token == "":
            logger.warning("Empty GitHub token treated as anonymous (60 req/hr cap).")

    def run(
        self, incremental: bool = True, max_prs_per_repo: int = 200, by_user: bool = False
    ) -> None:
        if by_user:
            self._run_by_user(max_prs_per_user=max_prs_per_repo)
            return
        for repo in self.repos:
            try:
                owner, name = repo.split("/", 1)
            except ValueError:
                logger.warning("Skipping malformed repo spec: %s", repo)
                continue
            since = (
                self.store.latest_change_updated_for_source(repo, SOURCE)
                if incremental
                else None
            )
            if since:
                logger.info("Ingesting %s incrementally since %s", repo, since)
            else:
                logger.info("Ingesting %s (full)", repo)
            self._ingest_repo(owner, name, since=since, max_prs=max_prs_per_repo)

    def _run_by_user(self, max_prs_per_user: int) -> None:
        logins = self.identity_map.github_logins()
        if not logins:
            logger.warning(
                "No mapped GitHub logins in identity map; nothing to ingest in --by-user mode."
            )
            return
        for login in logins:
            logger.info("Ingesting by user %s (recent public events)", login)
            self._ingest_user(login, max_prs=max_prs_per_user)

    def _ingest_user(self, login: str, max_prs: int) -> None:
        ingested = 0
        seen_pr_urls = set()
        page = 1
        # /users/{login}/events/public exposes only recent public activity, so
        # this mode is naturally bounded and doesn't need incremental state.
        while ingested < max_prs:
            events = self._get(
                f"/users/{login}/events/public",
                {"per_page": _PER_PAGE, "page": page},
            )
            if not events:
                break
            for ev in events:
                pr_url = _event_pr_api_url(ev)
                if not pr_url or pr_url in seen_pr_urls:
                    continue
                seen_pr_urls.add(pr_url)
                pr = self._get_absolute(pr_url)
                if not pr:
                    continue
                repo_full = ((pr.get("base") or {}).get("repo") or {}).get("full_name") or ""
                try:
                    owner, name = repo_full.split("/", 1)
                except ValueError:
                    continue
                self._process_pr(owner, name, pr)
                ingested += 1
                if ingested >= max_prs:
                    break
            self.store.commit()
            if len(events) < _PER_PAGE:
                break
            page += 1
        logger.info("  Done: %d PRs ingested for user %s", ingested, login)

    # --- repo-level walk ------------------------------------------------

    def _ingest_repo(
        self, owner: str, name: str, since: Optional[str], max_prs: int
    ) -> None:
        ingested = 0
        page = 1
        # GitHub's `pulls` endpoint doesn't support `since`, but it returns
        # results sorted by `updated_at` descending when we ask for it; we
        # short-circuit as soon as we cross `since`.
        while ingested < max_prs:
            prs = self._get(
                f"/repos/{owner}/{name}/pulls",
                {
                    "state": "all",
                    "sort": "updated",
                    "direction": "desc",
                    "per_page": _PER_PAGE,
                    "page": page,
                },
            )
            if not prs:
                break

            stop = False
            for pr in prs:
                if since and pr.get("updated_at") and pr["updated_at"] <= since:
                    stop = True
                    break
                self._process_pr(owner, name, pr)
                ingested += 1
                if ingested >= max_prs:
                    break

            self.store.commit()
            if stop or len(prs) < _PER_PAGE:
                break
            page += 1

        logger.info("  Done: %d PRs ingested for %s/%s", ingested, owner, name)

    # --- per-PR processing ---------------------------------------------

    def _process_pr(self, owner: str, name: str, pr: dict) -> None:
        number = pr.get("number")
        if number is None:
            return
        change_id = _pr_change_id(owner, name, number)
        repo_full = f"{owner}/{name}"

        author_login = ((pr.get("user") or {}).get("login")) or ""
        owner_account_id = self.identity_map.gerrit_for(author_login)

        status = self._pr_status(pr)
        submitted = pr.get("merged_at") if status == "MERGED" else None

        self.store.upsert_change(
            ChangeRecord(
                change_id=change_id,
                project=repo_full,
                branch=((pr.get("base") or {}).get("ref")) or "",
                owner_account_id=owner_account_id,
                owner_name=author_login or None,
                owner_email=None,
                status=status,
                created=pr.get("created_at"),
                updated=pr.get("updated_at"),
                submitted=submitted,
                insertions=pr.get("additions"),
                deletions=pr.get("deletions"),
                source=SOURCE,
            )
        )

        self._process_files(owner, name, number, change_id)
        self._process_requested_reviewers(pr, change_id)
        self._process_reviews(owner, name, number, change_id)

    @staticmethod
    def _pr_status(pr: dict) -> str:
        if pr.get("merged_at"):
            return "MERGED"
        state = (pr.get("state") or "").upper()
        if state == "CLOSED":
            return "ABANDONED"
        return "NEW"

    def _process_files(
        self, owner: str, name: str, number: int, change_id: str
    ) -> None:
        files = (
            self._get(
                f"/repos/{owner}/{name}/pulls/{number}/files",
                {"per_page": _PER_PAGE},
            )
            or []
        )
        for f in files:
            path = f.get("filename")
            if not path:
                continue
            self.store.upsert_file(
                FileRecord(
                    change_id=change_id,
                    patchset_number=1,
                    file_path=path,
                    lines_inserted=f.get("additions"),
                    lines_deleted=f.get("deletions"),
                    change_type=_translate_file_status(f.get("status")),
                    source=SOURCE,
                )
            )

    def _process_requested_reviewers(self, pr: dict, change_id: str) -> None:
        for user in (pr.get("requested_reviewers") or []):
            login = user.get("login")
            account_id = self.identity_map.gerrit_for(login or "")
            if account_id is None:
                logger.debug(
                    "Skipping requested reviewer %s (not in identity map)", login
                )
                continue
            self.store.upsert_reviewer(
                ReviewerRecord(
                    change_id=change_id,
                    account_id=account_id,
                    account_name=login,
                    account_email=None,
                    state="REVIEWER",
                    source=SOURCE,
                )
            )

    def _process_reviews(
        self, owner: str, name: str, number: int, change_id: str
    ) -> None:
        reviews = (
            self._get(
                f"/repos/{owner}/{name}/pulls/{number}/reviews",
                {"per_page": _PER_PAGE},
            )
            or []
        )
        # GitHub returns a row per review (including comments); we want one
        # ReviewerRecord per reviewer, but a label-vote per actionable review.
        seen_reviewer = set()
        for r in reviews:
            login = ((r.get("user") or {}).get("login")) or ""
            account_id = self.identity_map.gerrit_for(login)
            if account_id is None:
                continue
            if account_id not in seen_reviewer:
                self.store.upsert_reviewer(
                    ReviewerRecord(
                        change_id=change_id,
                        account_id=account_id,
                        account_name=login,
                        account_email=None,
                        state="REVIEWER",
                        source=SOURCE,
                    )
                )
                seen_reviewer.add(account_id)
            state = (r.get("state") or "").upper()
            vote = _REVIEW_STATE_TO_VOTE.get(state)
            if vote is None or vote == 0:
                continue
            self.store.upsert_label_vote(
                LabelVoteRecord(
                    change_id=change_id,
                    account_id=account_id,
                    label_name="Code-Review",
                    value=vote,
                    date=r.get("submitted_at"),
                    source=SOURCE,
                )
            )

    # --- HTTP -----------------------------------------------------------

    def _get(self, path: str, params: Optional[dict] = None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        return self._get_url(url)

    def _get_absolute(self, url: str):
        return self._get_url(url)

    def _get_url(self, url: str):
        req = urllib.request.Request(url)
        req.add_header("Accept", "application/vnd.github+json")
        req.add_header("X-GitHub-Api-Version", "2022-11-28")
        if self._auth_header:
            req.add_header("Authorization", self._auth_header)

        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                self._respect_rate_limit(resp.headers)
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            self._respect_rate_limit(exc.headers)
            if exc.code == 403 and exc.headers.get("X-RateLimit-Remaining") == "0":
                logger.warning(
                    "GitHub rate-limit exhausted at %s; aborting this batch", url
                )
            else:
                logger.warning("HTTP %s for %s: %s", exc.code, url, exc.reason)
            return None
        except Exception as exc:  # noqa: BLE001
            logger.warning("Request failed for %s: %s", url, exc)
            return None

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            logger.warning("JSON decode error for %s: %s", url, exc)
            return None

    @staticmethod
    def _respect_rate_limit(headers) -> None:
        """Sleep briefly when GitHub says we're nearly out of budget.

        We don't try to be clever - just back off when the remaining quota is
        in single digits, which is enough for an offline cron without
        introducing a hard fail.
        """
        if not headers:
            return
        try:
            remaining = int(headers.get("X-RateLimit-Remaining") or "999")
        except ValueError:
            return
        if remaining > 5:
            return
        try:
            reset = int(headers.get("X-RateLimit-Reset") or "0")
        except ValueError:
            return
        wait = max(0, reset - int(time.time())) + 1
        # Cap so a misconfigured run doesn't sleep forever.
        wait = min(wait, 60)
        if wait > 0:
            logger.info(
                "GitHub rate-limit near zero (remaining=%d); sleeping %ds",
                remaining,
                wait,
            )
            time.sleep(wait)


def _translate_file_status(status: Optional[str]) -> str:
    """Translate GitHub PR-file ``status`` to the Gerrit-style change-type letter
    the existing scorer uses."""
    if not status:
        return "M"
    s = status.lower()
    if s == "added":
        return "A"
    if s == "removed":
        return "D"
    if s == "renamed":
        return "R"
    if s == "copied":
        return "C"
    return "M"


# Used only by tests / local debugging - constructs the basic-auth header that
# older GitHub clients use, kept here so an alternate path is easy to wire in.
def _legacy_basic_auth_header(username: str, token: str) -> str:
    creds = base64.b64encode(f"{username}:{token}".encode()).decode()
    return f"Basic {creds}"


def _event_pr_api_url(event: dict) -> Optional[str]:
    """Extract the PR API URL from a user-event payload, if present."""
    payload = event.get("payload") or {}
    pr = payload.get("pull_request") or {}
    url = pr.get("url")
    if url:
        return url
    issue = payload.get("issue") or {}
    issue_pr = issue.get("pull_request") or {}
    return issue_pr.get("url")
