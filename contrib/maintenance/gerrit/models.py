from dataclasses import dataclass
from typing import Optional


@dataclass
class ChangeRecord:
    change_id: str
    project: str
    branch: str
    owner_account_id: Optional[int]
    owner_name: Optional[str]
    owner_email: Optional[str]
    status: str
    created: Optional[str]
    updated: Optional[str]
    submitted: Optional[str]
    insertions: Optional[int]
    deletions: Optional[int]


@dataclass
class FileRecord:
    change_id: str
    patchset_number: int
    file_path: str
    lines_inserted: Optional[int]
    lines_deleted: Optional[int]
    change_type: Optional[str]


@dataclass
class ReviewerRecord:
    change_id: str
    account_id: int
    account_name: Optional[str]
    account_email: Optional[str]
    state: str  # REVIEWER or CC


@dataclass
class LabelVoteRecord:
    change_id: str
    account_id: int
    label_name: str
    value: int
    date: Optional[str]


@dataclass
class CommitRecord:
    repo: str
    commit_sha: str
    author_name: Optional[str]
    author_email: Optional[str]
    commit_ts: str
    subject: str


@dataclass
class CommitFileRecord:
    commit_sha: str
    repo: str
    file_path: str
    change_type: str  # A=added, M=modified, D=deleted, R=renamed




@dataclass
class ModuleEdgeRecord:
    """Directed dependency between v1 module IDs within one Gerrit project."""

    project: str
    from_module: str
    to_module: str


@dataclass
class ReviewerModuleScoreRecord:
    """Precomputed reviewer expertise for a module (batch job output)."""

    project: str
    account_id: int
    module_id: str
    score: float
    updated: Optional[str] = None