#!/usr/bin/python3

# Copyright (C) 2024 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
import logging
import os
import sys

import cli.gc
import cli.export_external_activity
import cli.ingest
import cli.ingest_github
import cli.seed_dummy_users

from gerrit.db import ReviewActivityStore
from gerrit.identity_map import GitHubIdentityMap
from gerrit.site import Site
from gerrit.tasks.export_external_activity import ExternalActivityExport
from gerrit.tasks.gc import BatchGitGarbageGollection
from gerrit.tasks.ingest import GerritRestIngestion
from gerrit.tasks.ingest_github import GitHubRestIngestion
from gerrit.tasks.seed_dummy_users import DummyUserSeeder
from git.gc import GitGarbageCollectionProvider

logging.basicConfig(
    level=logging.INFO,
    stream=sys.stdout,
    format="%(asctime)s [%(levelname)s] %(message)s",
)


def _run_projects_gc(args):
    site = Site(args[0].site)
    projects = (
        args[0].projects
        if args[0].projects
        else site.get_projects(args[0].skip_projects)
    )
    BatchGitGarbageGollection(
        site,
        projects,
        GitGarbageCollectionProvider.get(args[0].pack_refs, args[0].config),
    ).run(args[1])


def _run_projects_ingest(args):
    a = args[0]
    site = Site(a.site)
    projects = a.projects if a.projects else list(site.get_projects(a.skip_projects))
    with ReviewActivityStore(a.db_path) as store:
        GerritRestIngestion(
            gerrit_url=a.gerrit_url,
            store=store,
            projects=projects,
            username=a.username,
            password=a.password,
        ).run(incremental=a.incremental)


def _run_projects_ingest_github(args):
    a = args[0]
    identity_map = GitHubIdentityMap.from_file(a.identity_map_path)
    token = a.token or os.environ.get("GITHUB_TOKEN")
    with ReviewActivityStore(a.db_path) as store:
        GitHubRestIngestion(
            repos=a.repos,
            store=store,
            identity_map=identity_map,
            token=token,
            base_url=a.base_url,
        ).run(incremental=a.incremental, max_prs_per_repo=a.max_prs_per_repo)


def _run_projects_export_external_activity(args):
    a = args[0]
    sources = None if a.include_all else (a.sources or ["github"])
    with ReviewActivityStore(a.db_path) as store:
        ExternalActivityExport(
            store=store, output_path=a.output_path, sources=sources
        ).run()


def _run_projects_seed_dummy_users(args):
    a = args[0]
    DummyUserSeeder(
        gerrit_url=a.gerrit_url,
        username=a.username,
        password=a.password,
        identity_map_path=a.identity_map_path,
        email_domain=a.email_domain,
    ).run(a.logins)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "-d",
        "--site",
        help="Path to Gerrit site",
        dest="site",
        action="store",
        default="/var/gerrit",
    )
    parser.set_defaults(func=lambda x: parser.print_usage())

    subparsers = parser.add_subparsers()

    parser_projects = subparsers.add_parser(
        "projects",
        help="Tools for working with Gerrit projects.",
    )
    parser_projects.add_argument(
        "-p",
        "--project",
        help=(
            "Which project to gc. Can be used multiple times. If not given, all "
            "attrs=projects (except for `--skipped` ones) will be gc'ed."
        ),
        dest="projects",
        action="append",
        default=[],
    )
    parser_projects.add_argument(
        "-s",
        "--skip",
        help="Which project to skip. Can be used multiple times.",
        dest="skip_projects",
        action="append",
        default=[],
    )
    parser_projects.set_defaults(func=lambda x: parser_projects.print_usage())

    subparsers_projects = parser_projects.add_subparsers()
    parser_projects_gc = subparsers_projects.add_parser(
        "gc",
        prog=cli.gc.PROG,
        description=cli.gc.DESCRIPTION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    cli.gc.add_arguments(parser_projects_gc)
    parser_projects_gc.add_argument(
        "-c",
        "--config",
        help="Git config options to apply.",
        dest="config",
        action="append",
        default=[],
    )
    parser_projects_gc.set_defaults(func=_run_projects_gc)

    parser_projects_ingest = subparsers_projects.add_parser(
        "ingest",
        prog=cli.ingest.PROG,
        description=cli.ingest.DESCRIPTION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    cli.ingest.add_arguments(parser_projects_ingest)
    parser_projects_ingest.set_defaults(func=_run_projects_ingest)

    parser_projects_ingest_gh = subparsers_projects.add_parser(
        "ingest-github",
        prog=cli.ingest_github.PROG,
        description=cli.ingest_github.DESCRIPTION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    cli.ingest_github.add_arguments(parser_projects_ingest_gh)
    parser_projects_ingest_gh.set_defaults(func=_run_projects_ingest_github)

    parser_projects_export = subparsers_projects.add_parser(
        "export-external-activity",
        prog=cli.export_external_activity.PROG,
        description=cli.export_external_activity.DESCRIPTION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    cli.export_external_activity.add_arguments(parser_projects_export)
    parser_projects_export.set_defaults(func=_run_projects_export_external_activity)

    parser_projects_seed = subparsers_projects.add_parser(
        "seed-dummy-users",
        prog=cli.seed_dummy_users.PROG,
        description=cli.seed_dummy_users.DESCRIPTION,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    cli.seed_dummy_users.add_arguments(parser_projects_seed)
    parser_projects_seed.set_defaults(func=_run_projects_seed_dummy_users)

    args = parser.parse_known_args()
    args[0].func(args)


if __name__ == "__main__":
    main()
