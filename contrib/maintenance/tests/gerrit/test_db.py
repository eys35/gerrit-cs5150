# Copyright (C) 2025 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import pytest

from gerrit.db import ReviewActivityStore
from gerrit.models import (
    ChangeRecord,
    CommitFileRecord,
    CommitRecord,
    FileRecord,
    LabelVoteRecord,
    ReviewerRecord,
)


@pytest.fixture
def store(tmp_path):
    db = tmp_path / "test.db"
    with ReviewActivityStore(str(db)) as s:
        yield s


# ---------------------------------------------------------------------------
# ChangeRecord
# ---------------------------------------------------------------------------


def test_upsert_change_and_retrieve(store):
    r = ChangeRecord(
        change_id="42",
        project="myproject",
        branch="main",
        owner_account_id=7,
        owner_name="Alice",
        owner_email="alice@example.com",
        status="MERGED",
        created="2025-01-01 10:00:00.000000000",
        updated="2025-01-02 10:00:00.000000000",
        submitted="2025-01-02 10:00:00.000000000",
        insertions=10,
        deletions=3,
    )
    store.upsert_change(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM changes WHERE change_id = '42'"
    ).fetchone()
    assert row is not None
    assert row["project"] == "myproject"
    assert row["owner_name"] == "Alice"
    assert row["insertions"] == 10


def test_upsert_change_is_idempotent(store):
    r = ChangeRecord(
        change_id="1",
        project="p",
        branch="b",
        owner_account_id=None,
        owner_name=None,
        owner_email=None,
        status="NEW",
        created=None,
        updated="2025-03-01 00:00:00.000000000",
        submitted=None,
        insertions=0,
        deletions=0,
    )
    store.upsert_change(r)
    store.upsert_change(r)  # second upsert should not error or duplicate
    store.commit()

    count = store._conn.execute(
        "SELECT COUNT(*) FROM changes WHERE change_id = '1'"
    ).fetchone()[0]
    assert count == 1


def test_upsert_change_updates_existing(store):
    r = ChangeRecord(
        change_id="99",
        project="p",
        branch="b",
        owner_account_id=1,
        owner_name="Old",
        owner_email=None,
        status="NEW",
        created=None,
        updated="2025-01-01 00:00:00.000000000",
        submitted=None,
        insertions=0,
        deletions=0,
    )
    store.upsert_change(r)

    updated = ChangeRecord(
        change_id="99",
        project="p",
        branch="b",
        owner_account_id=1,
        owner_name="New",
        owner_email="new@example.com",
        status="MERGED",
        created=None,
        updated="2025-02-01 00:00:00.000000000",
        submitted="2025-02-01 00:00:00.000000000",
        insertions=5,
        deletions=1,
    )
    store.upsert_change(updated)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM changes WHERE change_id = '99'"
    ).fetchone()
    assert row["owner_name"] == "New"
    assert row["status"] == "MERGED"


# ---------------------------------------------------------------------------
# FileRecord
# ---------------------------------------------------------------------------


def test_upsert_file(store):
    r = FileRecord(
        change_id="10",
        patchset_number=1,
        file_path="src/Main.java",
        lines_inserted=50,
        lines_deleted=5,
        change_type="M",
    )
    store.upsert_file(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM files WHERE change_id = '10'"
    ).fetchone()
    assert row is not None
    assert row["file_path"] == "src/Main.java"
    assert row["lines_inserted"] == 50


def test_upsert_file_multiple_patchsets(store):
    for ps in [1, 2, 3]:
        store.upsert_file(
            FileRecord(
                change_id="20",
                patchset_number=ps,
                file_path="README.md",
                lines_inserted=ps,
                lines_deleted=0,
                change_type="M",
            )
        )
    store.commit()

    count = store._conn.execute(
        "SELECT COUNT(*) FROM files WHERE change_id = '20'"
    ).fetchone()[0]
    assert count == 3


# ---------------------------------------------------------------------------
# ReviewerRecord
# ---------------------------------------------------------------------------


def test_upsert_reviewer(store):
    r = ReviewerRecord(
        change_id="5",
        account_id=42,
        account_name="Bob",
        account_email="bob@example.com",
        state="REVIEWER",
    )
    store.upsert_reviewer(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM reviewers WHERE change_id = '5' AND account_id = 42"
    ).fetchone()
    assert row is not None
    assert row["state"] == "REVIEWER"


def test_reviewer_deduplication(store):
    for _ in range(3):
        store.upsert_reviewer(
            ReviewerRecord(
                change_id="5",
                account_id=42,
                account_name="Bob",
                account_email="bob@example.com",
                state="REVIEWER",
            )
        )
    store.commit()

    count = store._conn.execute(
        "SELECT COUNT(*) FROM reviewers WHERE change_id = '5' AND account_id = 42"
    ).fetchone()[0]
    assert count == 1


# ---------------------------------------------------------------------------
# LabelVoteRecord
# ---------------------------------------------------------------------------


def test_upsert_label_vote(store):
    r = LabelVoteRecord(
        change_id="7",
        account_id=99,
        label_name="Code-Review",
        value=2,
        date="2025-03-01 12:00:00.000000000",
    )
    store.upsert_label_vote(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM label_votes WHERE change_id = '7'"
    ).fetchone()
    assert row is not None
    assert row["value"] == 2
    assert row["label_name"] == "Code-Review"


# ---------------------------------------------------------------------------
# CommitRecord & CommitFileRecord
# ---------------------------------------------------------------------------


def test_upsert_commit(store):
    r = CommitRecord(
        repo="myproject",
        commit_sha="abc123",
        author_name="Carol",
        author_email="carol@example.com",
        commit_ts="2025-01-15T09:00:00Z",
        subject="Fix something",
    )
    store.upsert_commit(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM commits WHERE commit_sha = 'abc123'"
    ).fetchone()
    assert row is not None
    assert row["author_name"] == "Carol"


def test_upsert_commit_file(store):
    r = CommitFileRecord(
        commit_sha="abc123",
        repo="myproject",
        file_path="src/Foo.java",
        change_type="M",
    )
    store.upsert_commit_file(r)
    store.commit()

    row = store._conn.execute(
        "SELECT * FROM commit_files WHERE commit_sha = 'abc123'"
    ).fetchone()
    assert row is not None
    assert row["file_path"] == "src/Foo.java"


# ---------------------------------------------------------------------------
# latest_change_updated
# ---------------------------------------------------------------------------


def test_latest_change_updated_empty(store):
    result = store.latest_change_updated("no-such-project")
    assert result is None


def test_latest_change_updated_returns_max(store):
    for ts, status in [
        ("2025-01-01 00:00:00.000000000", "NEW"),
        ("2025-06-15 12:00:00.000000000", "MERGED"),
        ("2025-03-10 08:00:00.000000000", "ABANDONED"),
    ]:
        store.upsert_change(
            ChangeRecord(
                change_id=ts,
                project="proj",
                branch="main",
                owner_account_id=None,
                owner_name=None,
                owner_email=None,
                status=status,
                created=None,
                updated=ts,
                submitted=None,
                insertions=0,
                deletions=0,
            )
        )
    store.commit()

    latest = store.latest_change_updated("proj")
    assert latest == "2025-06-15 12:00:00.000000000"


def test_latest_change_updated_ignores_other_projects(store):
    store.upsert_change(
        ChangeRecord(
            change_id="a",
            project="proj-a",
            branch="main",
            owner_account_id=None,
            owner_name=None,
            owner_email=None,
            status="MERGED",
            created=None,
            updated="2025-09-01 00:00:00.000000000",
            submitted=None,
            insertions=0,
            deletions=0,
        )
    )
    store.commit()

    assert store.latest_change_updated("proj-b") is None



# ---------------------------------------------------------------------------
# Module edges and reviewer_module_scores
# ---------------------------------------------------------------------------


def test_upsert_module_edge(store):
    from gerrit.models import ModuleEdgeRecord

    store.upsert_module_edge(
        ModuleEdgeRecord(project="p", from_module="a", to_module="b")
    )
    store.commit()
    row = store._conn.execute(
        "SELECT * FROM module_edges WHERE project = 'p'"
    ).fetchone()
    assert row["from_module"] == "a"
    assert row["to_module"] == "b"


def test_delete_module_edges_for_project(store):
    from gerrit.models import ModuleEdgeRecord

    store.upsert_module_edge(
        ModuleEdgeRecord(project="p", from_module="x", to_module="y")
    )
    store.delete_module_edges_for_project("p")
    store.commit()
    n = store._conn.execute("SELECT COUNT(*) FROM module_edges").fetchone()[0]
    assert n == 0


def test_reviewer_module_scores_sum(store):
    from gerrit.models import ReviewerModuleScoreRecord

    store.upsert_reviewer_module_score(
        ReviewerModuleScoreRecord(
            project="gerrit", account_id=1, module_id="java", score=2.0, updated="t1"
        )
    )
    store.upsert_reviewer_module_score(
        ReviewerModuleScoreRecord(
            project="gerrit", account_id=1, module_id="java", score=1.0, updated="t2"
        )
    )
    store.upsert_reviewer_module_score(
        ReviewerModuleScoreRecord(
            project="gerrit", account_id=1, module_id="polygerrit-ui", score=5.0
        )
    )
    store.upsert_reviewer_module_score(
        ReviewerModuleScoreRecord(
            project="gerrit", account_id=2, module_id="java", score=10.0
        )
    )
    store.commit()

    s = store.sum_reviewer_scores_for_modules("gerrit", ["java", "polygerrit-ui"])
    assert s[1] == pytest.approx(6.0)
    assert s[2] == pytest.approx(10.0)


def test_sum_reviewer_scores_empty_modules(store):
    assert store.sum_reviewer_scores_for_modules("p", []) == {}


def test_delete_reviewer_module_scores_for_project(store):
    from gerrit.models import ReviewerModuleScoreRecord

    store.upsert_reviewer_module_score(
        ReviewerModuleScoreRecord(
            project="p", account_id=1, module_id="m", score=1.0
        )
    )
    store.delete_reviewer_module_scores_for_project("p")
    store.commit()
    n = store._conn.execute(
        "SELECT COUNT(*) FROM reviewer_module_scores"
    ).fetchone()[0]
    assert n == 0