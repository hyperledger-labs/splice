#!/usr/bin/env bash

set -eou pipefail

if [ -z "${AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID-}" ]; then
  echo "Enter Client ID of API Explorer Application from https://manage.auth0.com/dashboard/us/canton-network-test/applications/:"
  read AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID
  echo "Consider adding this line to your \`.envrc.private\` to avoid this prompt in the future:"
  echo "export AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID=\"$AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID\""
fi

if [ -z "${AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET-}" ]; then
  echo "Enter Client Secret of API Explorer Application from https://manage.auth0.com/dashboard/us/canton-network-test/applications/:"
  read AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET
  echo "Consider adding this line to your \`.envrc.private\` to avoid this prompt in the future:"
  echo "export AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET=\"$AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET\""
fi

sbt -DAUTH0_MANAGEMENT_API_CLIENT_ID=${AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID} -DAUTH0_MANAGEMENT_API_CLIENT_SECRET=${AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET}
