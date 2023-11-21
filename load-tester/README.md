# Load Tester

This directory contains the code for the CN Load Tester, built around `k6.io` scripts.

## Instructions

To run this test from your machine:

1. Add the following to your `.envrc.private` variables:
    - `export K6_AUTH0_DOMAIN="https://canton-network-dev.us.auth0.com"`
    - `export K6_AUTH0_CLIENT_ID="5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK"`
    - `export K6_LOAD_TEST_USER=user@cn-load-tester.com:<PASSWORD>` where `<PASSWORD>` is the actual password found in the passwords gdoc
2. Create & deploy a scratchnet cluster
3. Run `cncluster k6` from the cluster's deployment directory
4. Visit `https://grafana.<SCRATCH>.network.canton.global/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-prometheus` to view results

The current test runs a workload of 10 VUs (virtual users; these are concurrent processes that represent user clients) that iteratively hit the `tap` endpoint for a total duration of 5 minutes.

These options are configured in `src/config.ts`.
