"""Tests for the seed-dummy-users helper."""

from __future__ import annotations

import json
import os

import pytest

from gerrit.tasks.seed_dummy_users import DummyUserSeeder


@pytest.fixture
def seeder(tmp_path):
    s = DummyUserSeeder(
        gerrit_url="http://localhost:8080",
        username="admin",
        password="secret",
        identity_map_path=str(tmp_path / "github_users.json"),
        email_domain="example.invalid",
    )
    return s


def _stub_responses(seeder, get_responses, put_responses):
    """Replace the HTTP layer with deterministic dict responses keyed by path."""
    get_q = list(get_responses)
    put_q = list(put_responses)

    def fake_get(path):
        return get_q.pop(0) if get_q else None

    def fake_put(path, body):
        return put_q.pop(0) if put_q else None

    seeder._get = fake_get
    seeder._put = fake_put


def test_creates_account_when_missing_and_writes_map(seeder, tmp_path):
    _stub_responses(
        seeder,
        get_responses=[None],  # /accounts/octocat -> 404
        put_responses=[{"_account_id": 1001, "username": "octocat"}],
    )

    result = seeder.run(["octocat"])

    assert result == {1001: "octocat"}
    on_disk = json.loads(open(seeder.identity_map_path).read())
    assert on_disk["mappings"] == {"1001": "octocat"}


def test_idempotent_for_existing_account(seeder, tmp_path):
    _stub_responses(
        seeder,
        get_responses=[{"_account_id": 1002, "username": "torvalds"}],
        put_responses=[],
    )

    result = seeder.run(["torvalds"])

    assert result == {1002: "torvalds"}


def test_skips_logins_already_in_map(seeder, tmp_path):
    # Pre-populate the identity map with octocat.
    with open(seeder.identity_map_path, "w") as fh:
        json.dump({"mappings": {"1001": "octocat"}}, fh)
    # No GET / PUT calls should fire for octocat.
    _stub_responses(
        seeder,
        get_responses=[None],
        put_responses=[{"_account_id": 1003, "username": "gaearon"}],
    )

    result = seeder.run(["octocat", "gaearon"])

    assert result == {1001: "octocat", 1003: "gaearon"}


def test_merges_into_existing_map_without_clobbering(seeder, tmp_path):
    with open(seeder.identity_map_path, "w") as fh:
        json.dump(
            {"mappings": {"1001": "octocat", "1002": "torvalds"}},
            fh,
        )
    _stub_responses(
        seeder,
        get_responses=[None],
        put_responses=[{"_account_id": 1003, "username": "gaearon"}],
    )

    seeder.run(["gaearon"])

    on_disk = json.loads(open(seeder.identity_map_path).read())
    assert on_disk["mappings"] == {
        "1001": "octocat",
        "1002": "torvalds",
        "1003": "gaearon",
    }


def test_failed_account_creation_is_skipped(seeder, tmp_path):
    _stub_responses(
        seeder,
        get_responses=[None, None],
        put_responses=[None, {"_account_id": 1003, "username": "gaearon"}],
    )

    result = seeder.run(["broken-login", "gaearon"])

    # broken-login is dropped because PUT returned None; gaearon makes it in.
    assert result == {1003: "gaearon"}


def test_atomic_write_does_not_leave_tmp(seeder, tmp_path):
    _stub_responses(
        seeder,
        get_responses=[None],
        put_responses=[{"_account_id": 1001, "username": "octocat"}],
    )

    seeder.run(["octocat"])

    assert os.path.exists(seeder.identity_map_path)
    assert not os.path.exists(seeder.identity_map_path + ".tmp")


def test_rejects_missing_credentials(tmp_path):
    with pytest.raises(ValueError):
        DummyUserSeeder(
            gerrit_url="http://localhost:8080",
            username="",
            password="",
            identity_map_path=str(tmp_path / "github_users.json"),
        )


def test_authenticated_endpoint_used_for_account_calls(tmp_path):
    s = DummyUserSeeder(
        gerrit_url="http://localhost:8080",
        username="admin",
        password="secret",
        identity_map_path=str(tmp_path / "github_users.json"),
    )
    assert s.gerrit_url.endswith("/a")


def test_humanizes_complex_logins(tmp_path):
    from gerrit.tasks.seed_dummy_users import _humanize

    assert _humanize("octocat") == "Octocat"
    assert _humanize("the-octocat") == "The Octocat"
    assert _humanize("addy_osmani") == "Addy Osmani"
    assert _humanize("") == ""
