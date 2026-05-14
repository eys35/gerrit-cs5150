PROG = "Export the activity store to a JSON snapshot the JVM scorer reads."
DESCRIPTION = """
Read rows from the SQLite activity store (default: only `source = 'github'`)
and write a flat JSON snapshot for the Gerrit reviewer recommender. The
recommender loads this file at request time to factor external (e.g. GitHub)
review history into its file-familiarity, engagement, and cross-repo
signals - exactly the signals scaled by the reply dialog's weight sliders.

For a once-daily pipeline (Gerrit ingest, optional GitHub ingest, then this
export), use ``contrib/maintenance/scripts/daily-offline-ingest.sh`` with
``contrib/maintenance/systemd/gerrit-offline-ingest.timer`` or your own cron.

Manual equivalent::

    gerrit-maintenance projects ingest --gerrit-url ... --db ...
    gerrit-maintenance projects ingest-github --repo X/Y ...   # optional
    gerrit-maintenance projects export-external-activity \\
        --db ./reviewer-activity.db \\
        --out /var/gerrit/data/external-activity.json

The Gerrit JVM is configured with::

    [algorithmicReviewer]
        externalActivityFile = /var/gerrit/data/external-activity.json

If the file is absent or the config key unset, the scorer behaves as today.
"""


def add_arguments(parser):
    parser.add_argument(
        "--db",
        help="Path to the SQLite database file written by the ingester.",
        dest="db_path",
        default="./reviewer-activity.db",
    )
    parser.add_argument(
        "--out",
        help="Path to write the JSON snapshot to (created/overwritten).",
        dest="output_path",
        default="./external-activity.json",
    )
    parser.add_argument(
        "--source",
        help=(
            "Restrict the export to specific source name(s) (e.g. 'github'). "
            "Repeat for multiple sources. Default is 'github' only."
        ),
        dest="sources",
        action="append",
        default=[],
    )
    parser.add_argument(
        "--include-all",
        help=(
            "Export every source, including 'gerrit'. Useful for inspection; "
            "the JVM scorer ignores 'gerrit' rows because it already reads "
            "Gerrit history from NoteDb."
        ),
        dest="include_all",
        action="store_true",
        default=False,
    )
