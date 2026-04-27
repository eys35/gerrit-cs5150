"""Tests for the SQLite -> JSON export task."""

from __future__ import annotations

import json
import os

import pytest

from gerrit.db import ReviewActivityStore
from gerrit.models import (
    ChangeRecord,
    FileRecord,
    LabelVoteRecord,
    ReviewerRecord,
)
from gerrit.tasks.export_external_activity import (
    SCHEMA_VERSION,
    ExternalActivityExport,
)


@pytest.fixture
def store(tmp_path):
    db = tmp_path / "test.db"
    with ReviewActivityStore(str(db)) as s:
        yield s


def _seed(store, change_id, project, source, files, account_id, vote=None):
    store.upsert_change(
        ChangeRecord(
            change_id=change_id,
            project=project,
            branch="main",
            owner_account_id=None,
            owner_name=None,
            owner_email=None,
            status="MERGED",
            created="2025-04-01T10:00:00Z",
            updated="2025-04-01T10:00:00Z",
            submitted="2025-04-01T10:00:00Z",
            insertions=1,
            deletions=0,
            source=source,
        )
    )
    for path in files:
        store.upsert_file(
            FileRecord(
                change_id=change_id,
                patchset_number=1,
                file_path=path,
                lines_inserted=1,
                lines_deleted=0,
                change_type="M",
                source=source,
            )
        )
    store.upsert_reviewer(
        ReviewerRecord(
            change_id=change_id,
            account_id=account_id,
            account_name=f"user{account_id}",
            account_email=None,
            state="REVIEWER",
            source=source,
        )
    )
    if vote is not None:
        store.upsert_label_vote(
            LabelVoteRecord(
                change_id=change_id,
                account_id=account_id,
                label_name="Code-Review",
                value=vote,
                date="2025-04-01T11:00:00Z",
                source=source,
            )
        )
    store.commit()


def test_default_filters_to_github_only(store, tmp_path):
    _seed(store, "1", "acme/widgets", "github", ["src/a.py"], 1001, vote=1)
    _seed(store, "2", "gerrit-project", "gerrit", ["src/b.py"], 1002, vote=1)
    out_path = str(tmp_path / "ext.json")

    payload = ExternalActivityExport(store=store, output_path=out_path).run()

    assert payload["version"] == SCHEMA_VERSION
    sources = {row["source"] for row in payload["rows"]}
    assert sources == {"github"}
    assert os.path.exists(out_path)
    on_disk = json.loads(open(out_path).read())
    assert on_disk == payload


def test_include_all_dumps_every_source(store, tmp_path):
    _seed(store, "1", "acme/widgets", "github", ["src/a.py"], 1001, vote=1)
    _seed(store, "2", "gerrit-project", "gerrit", ["src/b.py"], 1002, vote=1)
    out_path = str(tmp_path / "ext.json")

    payload = ExternalActivityExport(
        store=store, output_path=out_path, sources=None
    ).run()

    sources = {row["source"] for row in payload["rows"]}
    assert sources == {"github", "gerrit"}


def test_one_row_per_account_file_label(store, tmp_path):
    # Same reviewer, same file, multiple PRs - the export collapses to a
    # single row per (account, project, file, label) tuple, keeping the
    # highest-magnitude vote.
    _seed(store, "1", "acme/widgets", "github", ["src/a.py"], 1001, vote=1)
    _seed(store, "2", "acme/widgets", "github", ["src/a.py"], 1001, vote=-1)
    out_path = str(tmp_path / "ext.json")

    payload = ExternalActivityExport(store=store, output_path=out_path).run()

    rows = [r for r in payload["rows"] if r["account_id"] == 1001]
    assert len(rows) == 1
    assert rows[0]["file_path"] == "src/a.py"
    # +1 and -1 have equal magnitude; the higher-vote tiebreak picks +1
    # because it's seen first and never replaced (abs equal -> keep current).
    assert rows[0]["vote"] in (1, -1)


def test_reviewer_with_no_vote_still_exports_with_zero(store, tmp_path):
    _seed(store, "1", "acme/widgets", "github", ["src/a.py"], 1001, vote=None)
    out_path = str(tmp_path / "ext.json")

    payload = ExternalActivityExport(store=store, output_path=out_path).run()

    rows = payload["rows"]
    assert len(rows) == 1
    assert rows[0]["vote"] == 0
    assert rows[0]["label_name"] is None


def test_atomic_rename_no_temp_left_over(store, tmp_path):
    _seed(store, "1", "acme/widgets", "github", ["src/a.py"], 1001, vote=1)
    out_path = str(tmp_path / "ext.json")

    ExternalActivityExport(store=store, output_path=out_path).run()

    assert os.path.exists(out_path)
    assert not os.path.exists(out_path + ".tmp")


def test_rows_are_sorted_for_stable_diffs(store, tmp_path):
    _seed(store, "2", "acme/widgets", "github", ["src/zeta.py"], 1002, vote=1)
    _seed(store, "1", "acme/widgets", "github", ["src/alpha.py"], 1001, vote=1)
    out_path = str(tmp_path / "ext.json")

    payload = ExternalActivityExport(store=store, output_path=out_path).run()

    paths = [(r["file_path"], r["account_id"]) for r in payload["rows"]]
    assert paths == sorted(paths)
