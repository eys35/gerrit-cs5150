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
    source: str = "gerrit"


@dataclass
class FileRecord:
    change_id: str
    patchset_number: int
    file_path: str
    lines_inserted: Optional[int]
    lines_deleted: Optional[int]
    change_type: Optional[str]
    source: str = "gerrit"


@dataclass
class ReviewerRecord:
    change_id: str
    account_id: int
    account_name: Optional[str]
    account_email: Optional[str]
    state: str  # REVIEWER or CC
    source: str = "gerrit"


@dataclass
class LabelVoteRecord:
    change_id: str
    account_id: int
    label_name: str
    value: int
    date: Optional[str]
    source: str = "gerrit"


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
