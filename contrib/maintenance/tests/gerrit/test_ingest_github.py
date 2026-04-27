"""Tests for the GitHub ingester.

We stub the network at the ``_get`` boundary so the tests stay deterministic
and offline. The ingester's responsibility under test is:

  * mapping GitHub PR/file/review payloads to the existing ``ChangeRecord``,
    ``FileRecord``, ``ReviewerRecord`` and ``LabelVoteRecord`` shapes;
  * dropping reviewer/vote rows when the GitHub login isn't in the identity
    map;
  * tagging every row with ``source = 'github'`` so the rows coexist with
    Gerrit-source rows;
  * stopping the PR walk when the incremental high-water mark is reached.
"""

from __future__ import annotations

from typing import List

import pytest

from gerrit.db import ReviewActivityStore
from gerrit.identity_map import GitHubIdentityMap
from gerrit.tasks.ingest_github import GitHubRestIngestion


@pytest.fixture
def store(tmp_path):
    db = tmp_path / "test.db"
    with ReviewActivityStore(str(db)) as s:
        yield s


def _identity_map() -> GitHubIdentityMap:
    return GitHubIdentityMap(
        {
            1001: "octocat",
            1002: "torvalds",
            1003: "gaearon",
        }
    )


def _make_pr(
    number: int,
    *,
    user: str = "octocat",
    state: str = "closed",
    merged_at: str = "2025-04-01T10:00:00Z",
    updated_at: str = "2025-04-01T10:00:00Z",
    base_ref: str = "main",
    requested_reviewers=None,
    additions: int = 5,
    deletions: int = 1,
) -> dict:
    return {
        "number": number,
        "state": state,
        "merged_at": merged_at,
        "created_at": "2025-03-30T10:00:00Z",
        "updated_at": updated_at,
        "user": {"login": user},
        "base": {"ref": base_ref},
        "requested_reviewers": requested_reviewers or [],
        "additions": additions,
        "deletions": deletions,
    }


def _ingestion(
    store,
    repos,
    identity_map=None,
    responses_by_path=None,
):
    """Build an ingestion whose ``_get`` returns whatever the test queues up.

    ``responses_by_path`` maps a path-prefix string to a list of payloads;
    each call pops the head off so paginated walks behave as expected.
    """
    ingestion = GitHubRestIngestion(
        repos=repos,
        store=store,
        identity_map=identity_map or _identity_map(),
        token="fake-token",
    )
    queues = {prefix: list(responses) for prefix, responses in (responses_by_path or {}).items()}
    # Match the longest prefix first so `/pulls/42/files` doesn't get
    # absorbed by the broader `/pulls` queue.
    sorted_prefixes = sorted(queues, key=len, reverse=True)

    def fake_get(path: str, params=None) -> List[dict]:
        for prefix in sorted_prefixes:
            if path.startswith(prefix):
                queue = queues[prefix]
                return queue.pop(0) if queue else []
        return []

    ingestion._get = fake_get
    return ingestion


def test_pr_is_recorded_with_github_source(store):
    pr = _make_pr(number=42, user="octocat")
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[pr], []],
            "/repos/acme/widgets/pulls/42/files": [[]],
            "/repos/acme/widgets/pulls/42/reviews": [[]],
        },
    ).run(incremental=False)

    row = store._conn.execute(
        "SELECT * FROM changes WHERE change_id = 'gh:acme/widgets#42'"
    ).fetchone()
    assert row is not None
    assert row["project"] == "acme/widgets"
    assert row["status"] == "MERGED"
    assert row["source"] == "github"
    assert row["owner_account_id"] == 1001
    assert row["owner_name"] == "octocat"


def test_open_and_closed_status_translation(store):
    open_pr = _make_pr(number=1, state="open", merged_at=None)
    closed_unmerged = _make_pr(number=2, state="closed", merged_at=None)
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[open_pr, closed_unmerged], []],
            "/repos/acme/widgets/pulls/1/files": [[]],
            "/repos/acme/widgets/pulls/1/reviews": [[]],
            "/repos/acme/widgets/pulls/2/files": [[]],
            "/repos/acme/widgets/pulls/2/reviews": [[]],
        },
    ).run(incremental=False)

    rows = {
        r["change_id"]: r["status"]
        for r in store._conn.execute(
            "SELECT change_id, status FROM changes ORDER BY change_id"
        )
    }
    assert rows["gh:acme/widgets#1"] == "NEW"
    assert rows["gh:acme/widgets#2"] == "ABANDONED"


def test_files_are_stored_with_translated_change_type(store):
    pr = _make_pr(number=7)
    files = [
        {"filename": "src/a.py", "status": "added", "additions": 5, "deletions": 0},
        {"filename": "src/b.py", "status": "modified", "additions": 2, "deletions": 1},
        {"filename": "src/c.py", "status": "removed", "additions": 0, "deletions": 9},
        {"filename": "src/d.py", "status": "renamed", "additions": 1, "deletions": 1},
    ]
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[pr], []],
            "/repos/acme/widgets/pulls/7/files": [files],
            "/repos/acme/widgets/pulls/7/reviews": [[]],
        },
    ).run(incremental=False)

    rows = list(
        store._conn.execute(
            "SELECT file_path, change_type, source FROM files "
            "WHERE change_id = 'gh:acme/widgets#7' ORDER BY file_path"
        )
    )
    assert [(r["file_path"], r["change_type"]) for r in rows] == [
        ("src/a.py", "A"),
        ("src/b.py", "M"),
        ("src/c.py", "D"),
        ("src/d.py", "R"),
    ]
    assert all(r["source"] == "github" for r in rows)


def test_reviews_create_reviewer_and_label_vote_for_mapped_users(store):
    pr = _make_pr(
        number=10,
        requested_reviewers=[{"login": "torvalds"}, {"login": "stranger"}],
    )
    reviews = [
        {
            "user": {"login": "torvalds"},
            "state": "APPROVED",
            "submitted_at": "2025-04-01T11:00:00Z",
        },
        {
            "user": {"login": "gaearon"},
            "state": "CHANGES_REQUESTED",
            "submitted_at": "2025-04-01T11:05:00Z",
        },
        {
            "user": {"login": "torvalds"},
            "state": "COMMENTED",
            "submitted_at": "2025-04-01T11:10:00Z",
        },
        {
            "user": {"login": "ghost"},
            "state": "APPROVED",
            "submitted_at": "2025-04-01T11:15:00Z",
        },
    ]
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[pr], []],
            "/repos/acme/widgets/pulls/10/files": [[]],
            "/repos/acme/widgets/pulls/10/reviews": [reviews],
        },
    ).run(incremental=False)

    reviewers = {
        (r["account_id"], r["state"], r["source"])
        for r in store._conn.execute(
            "SELECT account_id, state, source FROM reviewers "
            "WHERE change_id = 'gh:acme/widgets#10'"
        )
    }
    # torvalds (1002) appears via both requested_reviewers and reviews,
    # gaearon (1003) only via reviews. Stranger and ghost are unmapped and
    # must NOT appear.
    assert reviewers == {(1002, "REVIEWER", "github"), (1003, "REVIEWER", "github")}

    votes = list(
        store._conn.execute(
            "SELECT account_id, label_name, value FROM label_votes "
            "WHERE change_id = 'gh:acme/widgets#10' ORDER BY account_id"
        )
    )
    assert [(v["account_id"], v["label_name"], v["value"]) for v in votes] == [
        (1002, "Code-Review", 1),
        (1003, "Code-Review", -1),
    ]


def test_unmapped_pr_author_is_kept_with_null_account_id(store):
    pr = _make_pr(number=11, user="someone-not-in-map")
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[pr], []],
            "/repos/acme/widgets/pulls/11/files": [[]],
            "/repos/acme/widgets/pulls/11/reviews": [[]],
        },
    ).run(incremental=False)

    row = store._conn.execute(
        "SELECT owner_account_id, owner_name FROM changes "
        "WHERE change_id = 'gh:acme/widgets#11'"
    ).fetchone()
    assert row is not None
    assert row["owner_account_id"] is None
    assert row["owner_name"] == "someone-not-in-map"


def test_incremental_stops_at_existing_high_water_mark(store):
    # Pre-populate the store with a "previously-ingested" PR.
    initial = _make_pr(number=1, updated_at="2025-04-01T10:00:00Z")
    _ingestion(
        store,
        repos=["acme/widgets"],
        responses_by_path={
            "/repos/acme/widgets/pulls": [[initial], []],
            "/repos/acme/widgets/pulls/1/files": [[]],
            "/repos/acme/widgets/pulls/1/reviews": [[]],
        },
    ).run(incremental=False)

    # Now run incrementally with one newer PR followed by the older one.
    newer = _make_pr(number=2, updated_at="2025-04-02T10:00:00Z")
    older = _make_pr(number=1, updated_at="2025-04-01T10:00:00Z")
    paths_called: List[str] = []

    ingestion = GitHubRestIngestion(
        repos=["acme/widgets"],
        store=store,
        identity_map=_identity_map(),
        token="fake-token",
    )

    def fake_get(path, params=None):
        paths_called.append(path)
        if path == "/repos/acme/widgets/pulls":
            return [newer, older]
        if path.endswith("/files") or path.endswith("/reviews"):
            return []
        return []

    ingestion._get = fake_get
    ingestion.run(incremental=True, max_prs_per_repo=50)

    # We must process PR #2 (newer) but skip PR #1 (already at HWM), so its
    # files/reviews endpoints should never be hit.
    assert "/repos/acme/widgets/pulls/2/files" in paths_called
    assert "/repos/acme/widgets/pulls/1/files" not in paths_called

    # Both rows in the DB now: #1 from the initial seed, #2 from this run.
    ids = sorted(
        r["change_id"]
        for r in store._conn.execute("SELECT change_id FROM changes")
    )
    assert ids == ["gh:acme/widgets#1", "gh:acme/widgets#2"]


def test_max_prs_per_repo_caps_walk(store):
    prs = [
        _make_pr(number=n, updated_at=f"2025-04-{30 - n:02d}T10:00:00Z")
        for n in range(1, 11)
    ]

    ingestion = GitHubRestIngestion(
        repos=["acme/widgets"],
        store=store,
        identity_map=_identity_map(),
        token="fake-token",
    )

    def fake_get(path, params=None):
        if path == "/repos/acme/widgets/pulls":
            return prs
        return []

    ingestion._get = fake_get
    ingestion.run(incremental=False, max_prs_per_repo=3)

    count = store._conn.execute(
        "SELECT COUNT(*) AS n FROM changes WHERE source = 'github'"
    ).fetchone()["n"]
    assert count == 3


def test_identity_map_loads_from_file(tmp_path):
    p = tmp_path / "github_users.json"
    p.write_text(
        '{"mappings": {"1001": "octocat", "1002": "torvalds"}}',
        encoding="utf-8",
    )
    m = GitHubIdentityMap.from_file(str(p))
    assert m.gerrit_for("octocat") == 1001
    # Reverse lookup is case-insensitive (GitHub logins are).
    assert m.gerrit_for("OCTOCAT") == 1001
    assert m.github_for(1002) == "torvalds"
    assert m.gerrit_for("not-a-user") is None


def test_identity_map_missing_file_returns_empty(tmp_path):
    m = GitHubIdentityMap.from_file(str(tmp_path / "does-not-exist.json"))
    assert len(m) == 0
