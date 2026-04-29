import sqlite3
from typing import Optional

from gerrit.models import (
    ChangeRecord,
    CommitFileRecord,
    CommitRecord,
    FileRecord,
    LabelVoteRecord,
    ReviewerRecord,
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS changes (
    change_id           TEXT    PRIMARY KEY,
    project             TEXT    NOT NULL,
    branch              TEXT    NOT NULL,
    owner_account_id    INTEGER,
    owner_name          TEXT,
    owner_email         TEXT,
    status              TEXT    NOT NULL,
    created             TEXT,
    updated             TEXT,
    submitted           TEXT,
    insertions          INTEGER,
    deletions           INTEGER,
    source              TEXT    NOT NULL DEFAULT 'gerrit'
);

CREATE TABLE IF NOT EXISTS files (
    change_id       TEXT    NOT NULL,
    patchset_number INTEGER NOT NULL,
    file_path       TEXT    NOT NULL,
    lines_inserted  INTEGER,
    lines_deleted   INTEGER,
    change_type     TEXT,
    source          TEXT    NOT NULL DEFAULT 'gerrit',
    PRIMARY KEY (change_id, patchset_number, file_path)
);

CREATE TABLE IF NOT EXISTS reviewers (
    change_id       TEXT    NOT NULL,
    account_id      INTEGER NOT NULL,
    account_name    TEXT,
    account_email   TEXT,
    state           TEXT    NOT NULL,
    source          TEXT    NOT NULL DEFAULT 'gerrit',
    PRIMARY KEY (change_id, account_id)
);

CREATE TABLE IF NOT EXISTS label_votes (
    change_id   TEXT    NOT NULL,
    account_id  INTEGER NOT NULL,
    label_name  TEXT    NOT NULL,
    value       INTEGER NOT NULL,
    date        TEXT,
    source      TEXT    NOT NULL DEFAULT 'gerrit',
    PRIMARY KEY (change_id, account_id, label_name)
);

CREATE TABLE IF NOT EXISTS commits (
    repo         TEXT NOT NULL,
    commit_sha   TEXT NOT NULL,
    author_name  TEXT,
    author_email TEXT,
    commit_ts    TEXT NOT NULL,
    subject      TEXT NOT NULL,
    PRIMARY KEY (repo, commit_sha)
);

CREATE TABLE IF NOT EXISTS commit_files (
    commit_sha   TEXT NOT NULL,
    repo         TEXT NOT NULL,
    file_path    TEXT NOT NULL,
    change_type  TEXT NOT NULL,
    PRIMARY KEY (commit_sha, repo, file_path)
);

CREATE INDEX IF NOT EXISTS idx_changes_project    ON changes(project);
CREATE INDEX IF NOT EXISTS idx_changes_updated    ON changes(updated);
CREATE INDEX IF NOT EXISTS idx_files_change       ON files(change_id);
CREATE INDEX IF NOT EXISTS idx_reviewers_account  ON reviewers(account_id);
CREATE INDEX IF NOT EXISTS idx_votes_account      ON label_votes(account_id);
CREATE INDEX IF NOT EXISTS idx_commits_repo       ON commits(repo);
CREATE INDEX IF NOT EXISTS idx_commit_files_repo  ON commit_files(repo);
"""


class ReviewActivityStore:
    """Context-manager wrapper around a SQLite database for review activity.
    """

    def __init__(self, db_path: str):
        self.db_path = db_path
        self._conn: Optional[sqlite3.Connection] = None

    def __enter__(self) -> "ReviewActivityStore": 
        self._conn = sqlite3.connect(self.db_path)
        self._conn.row_factory = sqlite3.Row
        # allow concurrency (reads during writes)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.execute("PRAGMA foreign_keys=ON")
        self._init_schema()
        return self

    def __exit__(self, *_) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None

    def _init_schema(self) -> None:
        assert self._conn is not None
        self._conn.executescript(_SCHEMA)
        # Idempotent migration for stores created before the `source` column
        # was added. SQLite's `ADD COLUMN` is not transactional with `IF NOT
        # EXISTS`, so we probe each table first.
        for table in ("changes", "files", "reviewers", "label_votes"):
            cols = {
                row[1]
                for row in self._conn.execute(f"PRAGMA table_info({table})")
            }
            if "source" not in cols:
                self._conn.execute(
                    f"ALTER TABLE {table} ADD COLUMN source TEXT "
                    f"NOT NULL DEFAULT 'gerrit'"
                )
        self._conn.commit()

    def upsert_change(self, r: ChangeRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO changes
               (change_id, project, branch, owner_account_id, owner_name,
                owner_email, status, created, updated, submitted,
                insertions, deletions, source)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                r.change_id,
                r.project,
                r.branch,
                r.owner_account_id,
                r.owner_name,
                r.owner_email,
                r.status,
                r.created,
                r.updated,
                r.submitted,
                r.insertions,
                r.deletions,
                r.source,
            ),
        )

    def upsert_file(self, r: FileRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO files
               (change_id, patchset_number, file_path,
                lines_inserted, lines_deleted, change_type, source)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (
                r.change_id,
                r.patchset_number,
                r.file_path,
                r.lines_inserted,
                r.lines_deleted,
                r.change_type,
                r.source,
            ),
        )

    def upsert_reviewer(self, r: ReviewerRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO reviewers
               (change_id, account_id, account_name, account_email, state, source)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (
                r.change_id,
                r.account_id,
                r.account_name,
                r.account_email,
                r.state,
                r.source,
            ),
        )

    def upsert_label_vote(self, r: LabelVoteRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO label_votes
               (change_id, account_id, label_name, value, date, source)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (
                r.change_id,
                r.account_id,
                r.label_name,
                r.value,
                r.date,
                r.source,
            ),
        )

    def upsert_commit(self, r: CommitRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO commits
               (repo, commit_sha, author_name, author_email, commit_ts, subject)
               VALUES (?, ?, ?, ?, ?, ?)""",
            (
                r.repo,
                r.commit_sha,
                r.author_name,
                r.author_email,
                r.commit_ts,
                r.subject,
            ),
        )

    def upsert_commit_file(self, r: CommitFileRecord) -> None:
        assert self._conn is not None
        self._conn.execute(
            """INSERT OR REPLACE INTO commit_files
               (commit_sha, repo, file_path, change_type)
               VALUES (?, ?, ?, ?)""",
            (r.commit_sha, r.repo, r.file_path, r.change_type),
        )

    def commit(self) -> None:
        assert self._conn is not None
        self._conn.commit()


    def latest_change_updated(self, project: str) -> Optional[str]:
        """Return the most recent ``updated`` timestamp seen for *project*.

        Used for incremental ingestion so subsequent runs only fetch changes
        newer than the last recorded one.
        """
        assert self._conn is not None
        row = self._conn.execute(
            "SELECT MAX(updated) FROM changes WHERE project = ?", (project,)
        ).fetchone()
        return row[0] if row else None

    def latest_change_updated_for_source(
        self, project: str, source: str
    ) -> Optional[str]:
        """Like :meth:`latest_change_updated`, scoped to a specific data source.

        Lets the GitHub ingester run alongside the Gerrit one without one
        clobbering the other's incremental high-water mark.
        """
        assert self._conn is not None
        row = self._conn.execute(
            "SELECT MAX(updated) FROM changes WHERE project = ? AND source = ?",
            (project, source),
        ).fetchone()
        return row[0] if row else None
