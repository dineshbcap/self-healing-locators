#!/usr/bin/env bash
# Local, no-Jenkins-required equivalent of jenkins/Jenkinsfile.locator-patch.
#
# Run this from the ROOT OF THE CONSUMING PROJECT (the framework that depends
# on self-healing-locators) - not from this repo. Copy it in and adjust
# defaults below if your layout differs.
#
# Mirrors the Jenkins job stage-for-stage:
#   1. (optional, --run-tests) `mvn test` to produce target/healing-report.json
#   2. builds a classpath and runs LocatorPatchCli --apply against the chosen
#      platform's properties file
#   3. if that produced a real change, commits it on a fresh branch and opens
#      a PR via `gh pr create` - if nothing changed, exits cleanly with no PR
#
# A heal is a candidate fix, not a guaranteed one - review the diff before
# merging, same as the Jenkins job.
#
# Requires: mvn, java, git, and the gh CLI installed and authenticated
# (`gh auth login`) with push access to this repo's remote.
#
# Usage:
#   ./local-locator-pr.sh <android|ios> [--run-tests] [--report <path>] [--properties <path>]

set -euo pipefail

usage() {
    echo "Usage: $0 <android|ios> [--run-tests] [--report <path>] [--properties <path>]" >&2
    exit 2
}

[ $# -ge 1 ] || usage
PLATFORM="$1"; shift
case "$PLATFORM" in
    android|ios) ;;
    *) echo "First argument must be 'android' or 'ios', got: $PLATFORM" >&2; usage ;;
esac

RUN_TESTS=false
REPORT_FILE="target/healing-report.json"
PROPERTIES_FILE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --run-tests) RUN_TESTS=true; shift ;;
        --report) REPORT_FILE="$2"; shift 2 ;;
        --properties) PROPERTIES_FILE="$2"; shift 2 ;;
        *) echo "Unknown argument: $1" >&2; usage ;;
    esac
done

PROPERTIES_FILE="${PROPERTIES_FILE:-src/main/resources/locators/locators_${PLATFORM}.properties}"
BRANCH_NAME="auto/locator-fix-${PLATFORM}-$(date +%s)"

for cmd in mvn java git gh; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "Required command not found: $cmd" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "gh CLI is not authenticated - run 'gh auth login' first." >&2; exit 1; }

if [ -n "$(git status --porcelain)" ]; then
    echo "Working tree is not clean - commit or stash your changes first." >&2
    exit 1
fi

if [ "$RUN_TESTS" = true ]; then
    echo "Running tests to produce $REPORT_FILE ..."
    mvn test
fi

[ -f "$REPORT_FILE" ] || { echo "$REPORT_FILE not found - run your tests first (or pass --run-tests)." >&2; exit 1; }
[ -f "$PROPERTIES_FILE" ] || { echo "$PROPERTIES_FILE not found." >&2; exit 1; }

echo "Building classpath ..."
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt

echo "Applying healed locators for platform '$PLATFORM' ..."
java -cp "target/classes:$(cat target/classpath.txt)" \
    com.dinesh.healing.LocatorPatchCli \
    "$REPORT_FILE" "$PROPERTIES_FILE" "$PLATFORM" --apply

if git diff --quiet -- "$PROPERTIES_FILE"; then
    echo "No changes for platform '$PLATFORM' in this report - nothing to PR."
    exit 0
fi

git checkout -b "$BRANCH_NAME"
git add "$PROPERTIES_FILE"
git commit -m "Auto-fix healed locator(s) in $(basename "$PROPERTIES_FILE")"
git push -u origin "$BRANCH_NAME"

gh pr create \
    --title "Auto-fix: healed locator(s) ($PLATFORM)" \
    --body "Generated locally by \`LocatorPatchCli\` from $REPORT_FILE. A heal is a candidate fix, not a guaranteed one - review the diff before merging." \
    --base main --head "$BRANCH_NAME"
