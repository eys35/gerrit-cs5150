PROG = "Seed dummy Gerrit accounts that match GitHub logins; write identity map."
DESCRIPTION = """
Demo-loop helper. Without any real users, the GitHub ingester writes rows
keyed on Gerrit Account.Ids that don't exist on the server, so the
recommender never surfaces them. This subcommand creates a Gerrit account
per GitHub login (idempotent) and writes the resulting identity map to disk.

Typical demo workflow::

    gerrit-maintenance projects seed-dummy-users \\
        --gerrit-url http://localhost:8080 \\
        --username admin --password secret \\
        --login octocat --login torvalds --login gaearon

    GITHUB_TOKEN=ghp_... gerrit-maintenance projects ingest-github \\
        --repo facebook/react

    gerrit-maintenance projects export-external-activity \\
        --out /var/gerrit/data/external-activity.json

After that, set ``algorithmicReviewer.externalActivityFile`` in
``gerrit.config`` and the reply dialog's reviewer suggestions will start
reflecting GitHub history. Sliding "Recent history" / "Contributions"
amplifies or silences those external signals.

Each account is created with username == login, email == login@<domain>,
and a humanized full name. No SSH keys or HTTP passwords are set; these
are intentionally inert placeholder identities.
"""


def add_arguments(parser):
    parser.add_argument(
        "--gerrit-url",
        help="Base URL of the Gerrit instance (e.g. http://localhost:8080).",
        dest="gerrit_url",
        required=True,
    )
    parser.add_argument(
        "--username",
        help="Gerrit HTTP username with the Create Account capability.",
        dest="username",
        required=True,
    )
    parser.add_argument(
        "--password",
        help="HTTP password for --username (Gerrit-generated, not the LDAP one).",
        dest="password",
        required=True,
    )
    parser.add_argument(
        "--login",
        help=(
            "GitHub login to seed. Repeat for each login; the resulting "
            "accounts are merged into --identity-map."
        ),
        dest="logins",
        action="append",
        default=[],
        required=True,
    )
    parser.add_argument(
        "--identity-map",
        help="Path to the JSON identity-map file to write/merge.",
        dest="identity_map_path",
        default="./conf/github_users.json",
    )
    parser.add_argument(
        "--email-domain",
        help=(
            "Domain used to synthesize email addresses for created accounts "
            "(default: example.com)."
        ),
        dest="email_domain",
        default="example.com",
    )
