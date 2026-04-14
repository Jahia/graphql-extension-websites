#!/bin/bash

if [[ -f .env ]]; then
  source .env
  export $(cat .env | sed 's/=.*//g'| xargs)
else
  source .env.example
  export $(cat .env.example | sed 's/=.*//g'| xargs)
fi
JAHIA_VERSION=${JAHIA_VERSION:-LATEST}
JAHIA_IMAGE=${JAHIA_IMAGE:-ghcr.io/jahia/jahia-ee-dev:8-SNAPSHOT}
TESTS_IMAGE=${TESTS_IMAGE:-jahia/graphql-extension-websites:latest}
MODULE_ID=${MODULE_ID:-graphql-extension-websites}
MANIFEST=${MANIFEST:-provisioning-manifest-snapshot.yml}
JAHIA_URL=${JAHIA_URL:-http://jahia:8080}
SUPER_USER_PASSWORD=${SUPER_USER_PASSWORD:-root1234}
JAHIA_LICENSE=${JAHIA_LICENSE:-""}
JAHIA_HOST=${JAHIA_HOST:-jahia}
