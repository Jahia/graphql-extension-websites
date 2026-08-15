#!/bin/bash
# `set -e` matters here. This script used to guard the jar copy with `if [[ -e ../target ]]` and
# nothing else, so two paths silently produced a test image built around a STALE module jar:
#   1. ../target absent (no build run yet) - the copy was skipped and the script carried on;
#   2. ../target present but holding no SNAPSHOT jar (e.g. only `mvn test` was run, which makes
#      target/test-classes but no artifact) - the glob did not expand, cp failed to stderr, and
#      the script carried on anyway.
# Either way Cypress then ran against whatever version the Jahia image already carried, so a run
# could green-light a fix while testing a jar that did not contain it. Fail loudly instead.
set -euo pipefail

source ./set-env.sh

shopt -s nullglob
jars=(../target/*-SNAPSHOT.jar)
shopt -u nullglob

if [[ ${#jars[@]} -eq 0 ]]; then
    echo "ERROR: no ../target/*-SNAPSHOT.jar found." >&2
    echo "       Build the module first:  (cd .. && mvn clean install)" >&2
    echo "       Refusing to build the test image against a stale jar in ./artifacts/." >&2
    exit 1
fi

echo "Copying module artifact(s) into ./artifacts/:"
for jar in "${jars[@]}"; do
    echo "  $(basename "${jar}")"
done
cp -R "${jars[@]}" ./artifacts/

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
