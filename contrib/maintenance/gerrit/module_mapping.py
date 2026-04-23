"""Map Gerrit file paths to canonical module IDs (v1: first path segment).
This module is shared by offline batch jobs (ingest-derived features) and should
stay in sync with any Java-side resolver added later for ReviewerRecommender.
See MODULE_MAPPING.md in this package for the full contract.
"""

from __future__ import annotations

from typing import Iterable

# Paths that carry no code-ownership / module signal (aligned with ingest skips).
_SKIP_PATHS = frozenset(
    {
        "/COMMIT_MSG",
        "/MERGE_LIST",
        "/PATCHSET_LEVEL",
    }
)

# Single-segment paths at repo root map here so every file yields a stable ID.
_ROOT_MODULE_ID = "__root__"


def path_to_module_id(file_path: str) -> str | None:
    """Return the v1 module ID for a single file path, or None if skipped.
    Rules:
    - Skip synthetic paths in ``_SKIP_PATHS``.
    - Strip leading ``/`` (Gerrit often uses paths like ``/src/Foo.java``).
    - If the path contains ``/``, the module ID is the first segment.
    - If there is no ``/``, the whole path (filename) is the module ID, unless
      empty, in which case use ``__root__``.
    """
    if not file_path:
        return None
    normalized = file_path.strip()
    if normalized in _SKIP_PATHS:
        return None
    while normalized.startswith("/"):
        normalized = normalized[1:]
    if not normalized:
        return None
    slash = normalized.find("/")
    if slash < 0:
        return normalized if normalized else _ROOT_MODULE_ID
    first = normalized[:slash]
    return first if first else _ROOT_MODULE_ID


def paths_to_module_ids(file_paths: Iterable[str]) -> frozenset[str]:
    """Collect unique module IDs for an iterable of file paths."""
    out: set[str] = set()
    for p in file_paths:
        mid = path_to_module_id(p)
        if mid is not None:
            out.add(mid)
    return frozenset(out)


def qualified_module_id(project: str, file_path: str) -> str | None:
    """Return ``project:module_id`` for cross-repo uniqueness, or None if skipped.
    *project* should be the Gerrit project name (e.g. ``plugins/my-plugin``).
    """
    mid = path_to_module_id(file_path)
    if mid is None:
        return None
    return f"{project}:{mid}"