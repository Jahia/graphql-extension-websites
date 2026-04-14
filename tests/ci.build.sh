#!/bin/bash
source ./set-env.sh

if [[ -e ../target ]]; then
  cp -R ../target/*-SNAPSHOT.jar ./artifacts/
fi

version=$(node -p "require('./package.json').devDependencies['@jahia/cypress']")
echo Using @jahia/cypress@$version...
npx --yes --package @jahia/cypress@$version ci.build
