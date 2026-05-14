# Configuration: environment variables (see conf/daily-offline-ingest.env.example).
# Optional: source a file first, e.g. cron:
#   0 2 * * * . /etc/default/gerrit-offline-ingest && /path/to/daily-offline-ingest.sh
# Or set OFFLINE_INGEST_ENV_FILE to that path when invoking without systemd.

set -euo pipefail

if [[ -n "${OFFLINE_INGEST_ENV_FILE:-}" && -f "${OFFLINE_INGEST_ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${OFFLINE_INGEST_ENV_FILE}"
  set +a
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAINT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${MAINT_DIR}"

: "${GERRIT_SITE:=/var/gerrit}"
: "${INGEST_DB:=${GERRIT_SITE}/data/reviewer-activity.db}"
: "${EXTERNAL_ACTIVITY_JSON:=${GERRIT_SITE}/data/external-activity.json}"
: "${GITHUB_IDENTITY_MAP:=${MAINT_DIR}/conf/github_users.json}"
: "${PYTHON:=python3}"

die() {
  echo "daily-offline-ingest: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<EOF
Usage: ${0##*/}

Required environment:
  GERRIT_URL          Base URL of the Gerrit instance (e.g. https://review.example.com)

Optional:
  GERRIT_SITE         Gerrit site path for gerrit-maintenance -d (default: ${GERRIT_SITE})
  INGEST_DB           SQLite DB path (default: under GERRIT_SITE/data/)
  EXTERNAL_ACTIVITY_JSON  Output JSON for the JVM (default: under GERRIT_SITE/data/)
  GERRIT_HTTP_USER / GERRIT_HTTP_PASSWORD   REST credentials
  GITHUB_TOKEN        If set with repos or by-user mode, runs ingest-github after Gerrit ingest
  GITHUB_REPOS        Space-separated owner/name entries (e.g. "org/a org/b")
  GITHUB_INGEST_BY_USER  Set to 1 to use --by-user instead of --repo (uses identity map logins)
  GITHUB_IDENTITY_MAP Path to github_users.json (default: ${GITHUB_IDENTITY_MAP})
  PYTHON              Python interpreter (default: python3)
  OFFLINE_INGEST_ENV_FILE  If set, source this file before reading variables above

Project filters (passed to gerrit-maintenance projects):
  OFFLINE_INGEST_PROJECTS   Space-separated project names -> repeated -p
  OFFLINE_INGEST_SKIP       Space-separated -> repeated --skip
EOF
  exit 1
}

[[ "${1:-}" != "-h" && "${1:-}" != "--help" ]] || usage

[[ -n "${GERRIT_URL:-}" ]] || die "GERRIT_URL is not set"

mkdir -p "$(dirname "${INGEST_DB}")" "$(dirname "${EXTERNAL_ACTIVITY_JSON}")"

run_maint() {
  "${PYTHON}" "${MAINT_DIR}/gerrit-maintenance.py" -d "${GERRIT_SITE}" "$@"
}

project_flags=()
if [[ -n "${OFFLINE_INGEST_PROJECTS:-}" ]]; then
  for p in ${OFFLINE_INGEST_PROJECTS}; do
    project_flags+=(-p "${p}")
  done
fi
if [[ -n "${OFFLINE_INGEST_SKIP:-}" ]]; then
  for s in ${OFFLINE_INGEST_SKIP}; do
    project_flags+=(--skip "${s}")
  done
fi

echo "daily-offline-ingest: Gerrit REST ingest -> ${INGEST_DB}" >&2
ingest_cmd=(
  projects "${project_flags[@]}"
  ingest
  --gerrit-url "${GERRIT_URL}"
  --db "${INGEST_DB}"
  --incremental
)
if [[ -n "${GERRIT_HTTP_USER:-}" ]]; then
  ingest_cmd+=(--username "${GERRIT_HTTP_USER}" --password "${GERRIT_HTTP_PASSWORD:-}")
fi
run_maint "${ingest_cmd[@]}"

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  if [[ "${GITHUB_INGEST_BY_USER:-0}" == "1" ]]; then
    echo "daily-offline-ingest: GitHub ingest (--by-user)" >&2
    gh_cmd=(
      projects "${project_flags[@]}"
      ingest-github
      --db "${INGEST_DB}"
      --identity-map "${GITHUB_IDENTITY_MAP}"
      --by-user
      --incremental
    )
    gh_cmd+=(--token "${GITHUB_TOKEN}")
    run_maint "${gh_cmd[@]}"
  elif [[ -n "${GITHUB_REPOS:-}" ]]; then
    echo "daily-offline-ingest: GitHub ingest (repos)" >&2
    gh_cmd=(
      projects "${project_flags[@]}"
      ingest-github
      --db "${INGEST_DB}"
      --identity-map "${GITHUB_IDENTITY_MAP}"
      --incremental
    )
    for r in ${GITHUB_REPOS}; do
      gh_cmd+=(--repo "${r}")
    done
    gh_cmd+=(--token "${GITHUB_TOKEN}")
    run_maint "${gh_cmd[@]}"
  else
    echo "daily-offline-ingest: GITHUB_TOKEN set but neither GITHUB_REPOS nor GITHUB_INGEST_BY_USER=1; skipping GitHub ingest" >&2
  fi
fi

echo "daily-offline-ingest: export JSON -> ${EXTERNAL_ACTIVITY_JSON}" >&2
run_maint projects "${project_flags[@]}" export-external-activity \
  --db "${INGEST_DB}" \
  --out "${EXTERNAL_ACTIVITY_JSON}"

echo "daily-offline-ingest: done" >&2
