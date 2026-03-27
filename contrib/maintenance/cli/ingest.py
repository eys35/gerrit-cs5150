PROG = "Ingest Gerrit review activity into local SQLite store."
DESCRIPTION = """
Pull change history, reviewer assignments, label votes, and per-file
metadata from the Gerrit REST API and persist them to a local SQLite
database.  The database feeds the algorithmic reviewer recommender's
familiarity and engagement scoring.

By default the ingest is incremental: only changes updated after the
last recorded timestamp for each project are fetched.  Use --full to
re-ingest everything from scratch.

"""


def add_arguments(parser):
    parser.add_argument(
        "--gerrit-url",
        help="Base URL of the Gerrit instance (e.g. http://localhost:8080).",
        dest="gerrit_url",
        required=True,
    )
    parser.add_argument(
        "--db",
        help="Path to the SQLite database file to write (created if absent).",
        dest="db_path",
        default="./reviewer-activity.db",
    )
    parser.add_argument(
        "--username",
        help="Gerrit HTTP username for authentication (optional).",
        dest="username",
        default=None,
    )
    parser.add_argument(
        "--password",
        help="Gerrit HTTP password for authentication (optional).",
        dest="password",
        default=None,
    )

    mode = parser.add_mutually_exclusive_group()
    mode.add_argument(
        "--incremental",
        help=(
            "Only ingest changes updated since the last recorded timestamp "
            "per project (default)."
        ),
        dest="incremental",
        action="store_true",
        default=True,
    )
    mode.add_argument(
        "--full",
        help="Re-ingest all changes from scratch, ignoring last-updated state.",
        dest="incremental",
        action="store_false",
    )
