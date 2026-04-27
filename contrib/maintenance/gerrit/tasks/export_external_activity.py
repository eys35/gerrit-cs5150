"""Export the SQLite store to a small JSON snapshot the JVM can read.

Why JSON, not JDBC? The Java reviewer scorer already depends on Gson and
ships in the Gerrit JAR; pulling in ``sqlite-jdbc`` would mean a new
WORKSPACE dependency and a runtime classpath addition for every Gerrit
install. The data is also tiny (thousands of rows for a demo), read-mostly,
and regenerated on a cron — there's no operational reason to query it from
the request path. A flat JSON file the scorer mmaps once on startup is the
minimum viable contract.

The output schema is intentionally narrow: one row per
``(account_id, project, file_path, label_name)`` with the highest-magnitude
vote that account cast on a change touching that file. This is the exact
shape the bridging methods in ``ReviewerRecommender`` consume; anything
richer should be added on the Java side later.

The default export filters to ``source = 'github'`` because Gerrit-source
rows are already visible to the scorer through NoteDb. Pass ``--include-all``
to dump every row, e.g. for inspection.
"""

from __future__ import annotations

import datetime
import json
import logging
import os
from typing import Iterable, Optional

from gerrit.db import ReviewActivityStore

logger = logging.getLogger(__name__)

SCHEMA_VERSION = 1


class ExternalActivityExport:
    """Read the activity store, emit a flat JSON snapshot."""

    def __init__(
        self,
        store: ReviewActivityStore,
        output_path: str,
        sources: Optional[Iterable[str]] = ("github",),
    ):
        self.store = store
        self.output_path = output_path
        # ``None`` means "every source"; otherwise a tuple/list of source names.
        self.sources = list(sources) if sources is not None else None

    def run(self) -> dict:
        rows = list(self._query_rows())
        payload = {
            "version": SCHEMA_VERSION,
            "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(
                timespec="seconds"
            ),
            "sources": self.sources or "all",
            "rows": rows,
        }
        # Write to a sibling temp file then rename, so the Java side never
        # observes a half-written JSON document.
        tmp_path = self.output_path + ".tmp"
        os.makedirs(os.path.dirname(self.output_path) or ".", exist_ok=True)
        with open(tmp_path, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, separators=(",", ":"))
        os.replace(tmp_path, self.output_path)
        logger.info(
            "Wrote %d external-activity rows to %s", len(rows), self.output_path
        )
        return payload

    def _query_rows(self):
        # We want one row per (account_id, project, file_path, label) tuple
        # that has BOTH a reviewer record and at least one file touched on
        # that change. The vote is optional - a reviewer with no label vote
        # still contributes to the file-familiarity signal even if their
        # engagement signal is zero (mirrors the Gerrit-side behaviour).
        params: list = []
        where = ["r.account_id IS NOT NULL"]
        if self.sources is not None:
            placeholders = ",".join("?" for _ in self.sources)
            where.append(f"r.source IN ({placeholders})")
            params.extend(self.sources)
        sql = f"""
            SELECT
                r.account_id        AS account_id,
                c.project           AS project,
                f.file_path         AS file_path,
                COALESCE(lv.label_name, '')  AS label_name,
                COALESCE(lv.value, 0)        AS vote,
                r.source            AS source
            FROM reviewers r
            JOIN changes  c  ON c.change_id  = r.change_id
            JOIN files    f  ON f.change_id  = r.change_id
            LEFT JOIN label_votes lv
                   ON lv.change_id  = r.change_id
                  AND lv.account_id = r.account_id
            WHERE {' AND '.join(where)}
        """

        # SQLite returns one row per file × per label; collapse to the highest
        # absolute vote so a single APPROVED + COMMENTED pair doesn't count
        # twice. Done in Python to keep the SQL portable.
        seen: dict = {}
        cursor = self.store._conn.execute(sql, params)
        for row in cursor:
            key = (
                row["account_id"],
                row["project"],
                row["file_path"],
                row["label_name"],
                row["source"],
            )
            existing = seen.get(key)
            if existing is None or abs(row["vote"]) > abs(existing["vote"]):
                seen[key] = {
                    "account_id": int(row["account_id"]),
                    "project": row["project"],
                    "file_path": row["file_path"],
                    "label_name": row["label_name"] or None,
                    "vote": int(row["vote"]),
                    "source": row["source"],
                }
        # Sort for stable diffs across runs.
        return sorted(
            seen.values(),
            key=lambda r: (
                r["source"],
                r["project"],
                r["file_path"],
                r["account_id"],
            ),
        )
