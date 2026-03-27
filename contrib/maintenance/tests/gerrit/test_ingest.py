import json
from unittest.mock import MagicMock, patch

import pytest

from gerrit.db import ReviewActivityStore
from gerrit.tasks.ingest import GerritRestIngestion

@pytest.fixture
def store(tmp_path):
    db = tmp_path / "test.db"
    with ReviewActivityStore(str(db)) as s:
        yield s


def _make_change(
    number,
    project,
    status="MERGED",
    updated="2025-03-01 10:00:00.000000000",
    files=None,
    reviewers=None,
    labels=None,
):
    """Return a minimal Gerrit REST change dict."""
    return {
        "_number": number,
        "change_id": f"I{number:040x}",
        "project": project,
        "branch": "main",
        "status": status,
        "created": "2025-01-01 10:00:00.000000000",
        "updated": updated,
        "submitted": updated if status == "MERGED" else None,
        "insertions": 10,
        "deletions": 2,
        "owner": {
            "_account_id": 1000,
            "name": "Alice",
            "email": "alice@example.com",
        },
        "revisions": {
            "abc123": {
                "_number": 1,
                "files": files
                or {
                    "src/Main.java": {
                        "status": "M",
                        "lines_inserted": 10,
                        "lines_deleted": 2,
                    }
                },
            }
        },
        "reviewers": reviewers or {"REVIEWER": []},
        "labels": labels or {},
    }


def _rest_response(payload):
    """Encode payload as a Gerrit REST XSSI-prefixed response."""
    return b")]}'\\n" + json.dumps(payload).encode()


def _ingestion_with_mock_get(store, project, changes, gerrit_url="http://localhost"):
    ingestion = GerritRestIngestion(
        gerrit_url=gerrit_url,
        store=store,
        projects=[project],
    )
    call_count = {"n": 0}

    def fake_get(path, params=None):
        call_count["n"] += 1
        if call_count["n"] == 1:
            return changes
        return []

    ingestion._get = fake_get
    return ingestion



def test_change_record_is_stored(store):
    changes = [_make_change(number=42, project="myproject")]
    _ingestion_with_mock_get(store, "myproject", changes).run(incremental=False)

    row = store._conn.execute(
        "SELECT * FROM changes WHERE change_id = '42'"
    ).fetchone()
    assert row is not None
    assert row["project"] == "myproject"
    assert row["status"] == "MERGED"
    assert row["owner_name"] == "Alice"


def test_file_records_are_stored(store):
    changes = [
        _make_change(
            number=1,
            project="p",
            files={
                "src/A.java": {"status": "A", "lines_inserted": 5, "lines_deleted": 0},
                "src/B.java": {"status": "M", "lines_inserted": 2, "lines_deleted": 1},
            },
        )
    ]
    _ingestion_with_mock_get(store, "p", changes).run(incremental=False)

    files = store._conn.execute(
        "SELECT file_path, change_type FROM files WHERE change_id = '1' ORDER BY file_path"
    ).fetchall()
    assert len(files) == 2
    paths = [f["file_path"] for f in files]
    assert "src/A.java" in paths
    assert "src/B.java" in paths


def test_commit_msg_file_is_skipped(store):
    changes = [
        _make_change(
            number=2,
            project="p",
            files={
                "/COMMIT_MSG": {"status": "A", "lines_inserted": 1, "lines_deleted": 0},
                "README.md": {"status": "M", "lines_inserted": 3, "lines_deleted": 1},
            },
        )
    ]
    _ingestion_with_mock_get(store, "p", changes).run(incremental=False)

    paths = [
        r["file_path"]
        for r in store._conn.execute(
            "SELECT file_path FROM files WHERE change_id = '2'"
        ).fetchall()
    ]
    assert "/COMMIT_MSG" not in paths
    assert "README.md" in paths


def test_reviewer_records_are_stored(store):
    changes = [
        _make_change(
            number=3,
            project="p",
            reviewers={
                "REVIEWER": [
                    {"_account_id": 10, "name": "Bob", "email": "bob@example.com"}
                ],
                "CC": [
                    {"_account_id": 11, "name": "Carol", "email": "carol@example.com"}
                ],
            },
        )
    ]
    _ingestion_with_mock_get(store, "p", changes).run(incremental=False)

    rows = store._conn.execute(
        "SELECT account_id, state FROM reviewers WHERE change_id = '3' ORDER BY account_id"
    ).fetchall()
    assert len(rows) == 2
    # account_id=10 (Bob) is REVIEWER, account_id=11 (Carol) is CC
    assert rows[0]["state"] == "REVIEWER"
    assert rows[1]["state"] == "CC"


def test_label_votes_are_stored(store):
    changes = [
        _make_change(
            number=4,
            project="p",
            labels={
                "Code-Review": {
                    "all": [
                        {
                            "_account_id": 20,
                            "value": 2,
                            "date": "2025-03-01 10:00:00.000000000",
                        },
                        {
                            "_account_id": 21,
                            "value": -1,
                            "date": "2025-03-01 09:00:00.000000000",
                        },
                    ]
                }
            },
        )
    ]
    _ingestion_with_mock_get(store, "p", changes).run(incremental=False)

    votes = store._conn.execute(
        "SELECT account_id, value FROM label_votes WHERE change_id = '4' ORDER BY value"
    ).fetchall()
    assert len(votes) == 2
    assert votes[0]["value"] == -1
    assert votes[1]["value"] == 2


def test_zero_label_votes_are_not_stored(store):
    changes = [
        _make_change(
            number=5,
            project="p",
            labels={
                "Code-Review": {
                    "all": [{"_account_id": 30, "value": 0}]
                }
            },
        )
    ]
    _ingestion_with_mock_get(store, "p", changes).run(incremental=False)

    count = store._conn.execute(
        "SELECT COUNT(*) FROM label_votes WHERE change_id = '5'"
    ).fetchone()[0]
    assert count == 0


def test_incremental_uses_latest_updated(store):
    """When incremental=True, the since= argument should include the last timestamp."""
    # Seed a prior change so the store knows the latest timestamp.
    from gerrit.models import ChangeRecord

    store.upsert_change(
        ChangeRecord(
            change_id="old",
            project="proj",
            branch="main",
            owner_account_id=None,
            owner_name=None,
            owner_email=None,
            status="MERGED",
            created=None,
            updated="2025-01-15 00:00:00.000000000",
            submitted=None,
            insertions=0,
            deletions=0,
        )
    )
    store.commit()

    ingestion = GerritRestIngestion(
        gerrit_url="http://localhost",
        store=store,
        projects=["proj"],
    )

    captured = {}

    def fake_get(path, params=None):
        captured["params"] = params
        return []

    ingestion._get = fake_get
    ingestion.run(incremental=True)

    # The 'q' param should contain an 'after:' clause.
    q_values = [v for k, v in captured.get("params", []) if k == "q"]
    assert any("after:" in q for q in q_values), (
        f"Expected 'after:' in query params, got: {captured.get('params')}"
    )


def test_full_run_ignores_latest_updated(store):
    """When incremental=False, the since= argument should NOT be passed."""
    from gerrit.models import ChangeRecord

    store.upsert_change(
        ChangeRecord(
            change_id="old",
            project="proj",
            branch="main",
            owner_account_id=None,
            owner_name=None,
            owner_email=None,
            status="MERGED",
            created=None,
            updated="2025-01-15 00:00:00.000000000",
            submitted=None,
            insertions=0,
            deletions=0,
        )
    )
    store.commit()

    ingestion = GerritRestIngestion(
        gerrit_url="http://localhost",
        store=store,
        projects=["proj"],
    )

    captured = {}

    def fake_get(path, params=None):
        captured["params"] = params
        return []

    ingestion._get = fake_get
    ingestion.run(incremental=False)

    q_values = [v for k, v in captured.get("params", []) if k == "q"]
    assert all("after:" not in q for q in q_values), (
        f"Did not expect 'after:' in full-ingest query, got: {captured.get('params')}"
    )


def test_http_error_does_not_crash_ingestion(store):
    """A failing HTTP response should log a warning but not raise."""
    import urllib.error

    ingestion = GerritRestIngestion(
        gerrit_url="http://localhost",
        store=store,
        projects=["proj"],
    )

    http_error = urllib.error.HTTPError(
        url="http://localhost/changes/",
        code=503,
        msg="Service Unavailable",
        hdrs=None,
        fp=None,
    )
    # Patch urlopen so the real _get method handles the exception via its
    # try/except, rather than bypassing it by replacing _get entirely.
    with patch("urllib.request.urlopen", side_effect=http_error):
        ingestion.run(incremental=False)


def test_multiple_projects_all_ingested(store):
    ingestion = GerritRestIngestion(
        gerrit_url="http://localhost",
        store=store,
        projects=["proj-a", "proj-b"],
    )

    def fake_get(path, params=None):
        q = next((v for k, v in (params or []) if k == "q"), "")
        if "proj-a" in q:
            return [_make_change(number=101, project="proj-a")]
        if "proj-b" in q:
            return [_make_change(number=202, project="proj-b")]
        return []

    ingestion._get = fake_get
    ingestion.run(incremental=False)

    for change_id in ("101", "202"):
        row = store._conn.execute(
            "SELECT change_id FROM changes WHERE change_id = ?", (change_id,)
        ).fetchone()
        assert row is not None, f"Expected change {change_id} to be stored"


def test_xssi_prefix_stripped():
    """_get should parse a XSSI-prefixed response correctly."""
    payload = [{"_number": 1}]
    # Real Gerrit XSSI prefix is 5 bytes: )]}'  + actual newline.
    raw = b")]}'\n" + json.dumps(payload).encode()

    ingestion = GerritRestIngestion(
        gerrit_url="http://localhost",
        store=MagicMock(),
        projects=[],
    )

    class FakeResponse:
        def read(self):
            return raw

        def __enter__(self):
            return self

        def __exit__(self, *_):
            pass

    with patch("urllib.request.urlopen", return_value=FakeResponse()):
        result = ingestion._get("/changes/", [])

    assert result == payload
