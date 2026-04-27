PROG = "Ingest GitHub PR + review activity into the local SQLite store."
DESCRIPTION = """
Pull pull-request history (files, requested reviewers, completed reviews)
from a list of GitHub repositories and persist them to the same SQLite
database used by the Gerrit ingester. Rows are tagged with
`source = 'github'` so the algorithmic reviewer recommender can read both
sources at once.

Logins are mapped to Gerrit account ids via a small JSON file
(see `conf/github_users.json.example`); rows for unmapped logins are
dropped on a per-row basis so the database stays consistent with what the
Java scorer expects.

By default the ingest is incremental and walks the most recent updates
for each repo, stopping when it crosses the latest `updated` timestamp
already in the store for that repo + source.
"""


def add_arguments(parser):
    parser.add_argument(
        "--repo",
        help=(
            "GitHub repository to ingest, in `owner/name` form. "
            "Can be specified multiple times."
        ),
        dest="repos",
        action="append",
        default=[],
        required=True,
    )
    parser.add_argument(
        "--db",
        help="Path to the SQLite database file to write (created if absent).",
        dest="db_path",
        default="./reviewer-activity.db",
    )
    parser.add_argument(
        "--identity-map",
        help=(
            "Path to the JSON identity-map file mapping Gerrit account ids "
            "to GitHub logins. See conf/github_users.json.example."
        ),
        dest="identity_map_path",
        default="./conf/github_users.json",
    )
    parser.add_argument(
        "--token",
        help=(
            "GitHub token (PAT or fine-grained). May also be supplied via the "
            "GITHUB_TOKEN environment variable; the CLI flag wins if both are "
            "set. Without a token GitHub allows only 60 requests per hour."
        ),
        dest="token",
        default=None,
    )
    parser.add_argument(
        "--base-url",
        help="Override for GitHub Enterprise installations.",
        dest="base_url",
        default="https://api.github.com",
    )
    parser.add_argument(
        "--max-prs-per-repo",
        help=(
            "Cap on the number of PRs walked per repo per run. Useful for "
            "demos and bounding API spend."
        ),
        dest="max_prs_per_repo",
        type=int,
        default=200,
    )

    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--incremental",
        help="Stop once we cross the latest stored `updated` timestamp (default).",
        dest="incremental",
        action="store_true",
        default=True,
    )
    mode.add_argument(
        "--full",
        help="Re-ingest up to --max-prs-per-repo regardless of what's stored.",
        dest="incremental",
        action="store_false",
    )
