#!/usr/bin/env bash
# Real local approximation of what dev/main's own branch protection
# requires from CodeQL + dependency-review — per explicit direction
# ("we should have a ci/cd / security audit done locally before we do
# pushes"). Deliberately separate from .githooks/pre-push (which runs on
# every push): this is real network/CPU work (a full CodeQL database
# build + analysis per language), meant to run explicitly before a push
# that's heading toward a release/PR into a protected branch, not on
# every routine commit.
#
# Requires the `gh codeql` extension (one-time setup):
#   gh extension install github/gh-codeql
#   gh codeql pack download codeql/java-queries codeql/python-queries
#
# The java-kotlin leg builds the app for real (assembleDebug) to let
# CodeQL's autobuild extractor trace it — on a machine where Chaquopy
# can't auto-detect a build-time Python 3.12, set CHAQUOPY_BUILD_PYTHON
# first (see the chaquopy-build-cycle convention and this repo's own
# app/build.gradle.kts, which reads that env var directly — no file
# edit needed for this like every other local build here).
#
# What this does NOT fully replicate (real gaps, not oversights):
#   - GitHub's own dependency-review-action diffs the *live* dependency
#     graph GitHub computes for a real PR (base ref vs head ref) — that
#     snapshot only exists once a PR is open, there's no local
#     equivalent that sees exactly what GitHub sees. pip-audit below
#     covers the closest real local analog (known-CVE lookup against
#     python-core's actual resolved dependencies), but it's not the
#     same check GitHub runs, and there's currently no equivalent
#     vulnerability scan for the Gradle/Kotlin dependency graph here.
#   - This runs CodeQL's `security-extended` suite locally, matching
#     codeql.yml's own `queries: security-extended` config — same
#     query pack, same category split (java-kotlin / python) — but a
#     local single-language-at-a-time run, not GitHub's own matrix job.

set -e

cd "$(git rev-parse --show-toplevel)"
mkdir -p build/security-audit
DB_DIR="build/security-audit"

# `gh codeql database create`'s --command runs via the native OS shell
# (cmd.exe on Windows, not Git Bash), so a bash-style `./gradlew` fails
# there ("'.' is not recognized...") even though it's exactly right for
# every other build step in this repo — gradlew.bat is the real fix,
# not a workaround, matching this repo's own README ("On Windows, use
# gradlew.bat instead of ./gradlew").
GRADLE_CMD="./gradlew"
if [ "$(uname -s | cut -c1-5)" = "MINGW" ] || [ "$(uname -s | cut -c1-4)" = "MSYS" ]; then
    # A bare relative "gradlew.bat" (no leading .\) isn't reliably found
    # by cmd.exe through gh-codeql's own process-spawning wrapper (real,
    # observed failure: "'gradlew.bat' is not recognized..." even though
    # `Running command in <repo root>` logged the right cwd) -- an
    # absolute Windows path sidesteps whatever's going on there instead
    # of relying on cwd-relative resolution at all.
    GRADLE_CMD="$(cygpath -w "$(pwd)/gradlew.bat" 2>/dev/null || echo "$(pwd)\\gradlew.bat")"
fi

echo "== security-audit: CodeQL java-kotlin =="
rm -rf "$DB_DIR/codeql-java-kotlin"
# CodeQL extracts by tracing the *real* compiler invocations a build
# makes -- an incremental/up-to-date build (the normal, fast case for
# ./gradlew after any earlier local build) skips recompiling anything
# and gives it nothing to see at all ("could not process any of it", a
# real result hit running this for the first time, not a hypothetical).
# `clean` first forces every module to genuinely recompile under the
# tracer. -Dkotlin.compiler.execution.strategy=in-process addresses a
# second, separate real gotcha (the Kotlin compiler normally running
# inside its own long-lived, tracer-invisible daemon process) -- but
# even with both of those, this has so far still failed on this actual
# Windows machine ("could not process any of it" persisted through a
# clean build with real recompilation confirmed in the log) — a real,
# currently-unresolved gap in gh-codeql's Windows build tracer with this
# specific Gradle+Kotlin+Chaquopy setup, not a config mistake being
# guessed at further. Best-effort, not fatal to the rest of this script
# — codeql.yml's own real Ubuntu-runner CI job is what actually gates
# merges for this leg; this is a local bonus when it works, not the
# authoritative check, and shouldn't block the parts of this script that
# do work reliably (python, pip-audit) from running.
if gh codeql database create "$DB_DIR/codeql-java-kotlin" \
  --language=java-kotlin \
  --command="$GRADLE_CMD clean assembleDebug --no-daemon -Dkotlin.compiler.execution.strategy=in-process" \
  --overwrite \
  && gh codeql database analyze "$DB_DIR/codeql-java-kotlin" \
  codeql/java-queries:codeql-suites/java-security-extended.qls \
  --format=sarifv2.1.0 \
  --output="$DB_DIR/java-kotlin-results.sarif" \
  --download
then
    JAVA_KOTLIN_OK=1
else
    JAVA_KOTLIN_OK=0
    echo
    echo "!! java-kotlin CodeQL extraction failed locally (known gap on this" >&2
    echo "!! machine — see this script's own comment above). Real CodeQL" >&2
    echo "!! coverage for this leg still runs in CI on every push/PR — this" >&2
    echo "!! is not a substitute for that passing, just a local bonus when" >&2
    echo "!! it works. Continuing with the parts of this audit that do." >&2
    echo
fi

echo "== security-audit: CodeQL python =="
rm -rf "$DB_DIR/codeql-python"
gh codeql database create "$DB_DIR/codeql-python" \
  --language=python \
  --source-root=python-core \
  --overwrite
gh codeql database analyze "$DB_DIR/codeql-python" \
  codeql/python-queries:codeql-suites/python-security-extended.qls \
  --format=sarifv2.1.0 \
  --output="$DB_DIR/python-results.sarif" \
  --download

echo "== security-audit: python-core dependency vulnerability scan (pip-audit) =="
if ! python -m pip show pip-audit >/dev/null 2>&1; then
    python -m pip install --quiet pip-audit
fi
if [ -f python-core/requirements.txt ]; then
    python -m pip_audit -r python-core/requirements.txt || true
else
    echo "No python-core/requirements.txt found — skipping (check app/build.gradle.kts's chaquopy { pip { ... } } block by hand instead)."
fi

echo
echo "== security-audit: summarizing SARIF results =="
if [ "$JAVA_KOTLIN_OK" = "1" ]; then
    SARIF_FILES="$DB_DIR/java-kotlin-results.sarif $DB_DIR/python-results.sarif"
else
    echo "$DB_DIR/java-kotlin-results.sarif: skipped (extraction failed, see above)"
    SARIF_FILES="$DB_DIR/python-results.sarif"
fi
for f in $SARIF_FILES; do
    count=$(python -c "
import json, sys
data = json.load(open('$f'))
n = sum(len(run.get('results', [])) for run in data.get('runs', []))
print(n)
" 2>/dev/null || echo "?")
    echo "$f: $count finding(s)"
done
echo
echo "Full results: $SARIF_FILES"
echo "(open in VS Code's SARIF viewer, or 'gh codeql database analyze ... --format=csv' for a quick read)"
