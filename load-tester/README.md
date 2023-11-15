# Load Tester

This directory contains the code for the CN Load Tester, built around `k6.io` scripts.

## Instructions

To run this test:

1. Create & deploy a scratchnet cluster
2. Run `GCP_CLUSTER_BASENAME=<SCRATCH> ./run-k6.sh`
3. Visit `https://grafana.<SCRATCH>.network.canton.global/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-prometheus` to view results

The current test runs a workload of 10 VUs (virtual users; these are concurrent processes that represent user clients) that iteratively hit the `tap` endpoint for a total duration of 5 minutes.

These options are configured in `src/test/load-test.ts`.

## Explanation

The `setup.ts` script should be run first, outside of k6. This does prerequisite tasks like logging in and getting a JWT bearer token for users. The result of this is written to a file (`dist/test/users.json`), which is then picked up by the load tester to load tokens for VUs.

`src/test/load-test.ts` is the actual k6 script, which we run with the helper npm command. k6 does not actually execute its test in NodeJS, but rather uses its own JS VM executor. Thus stuff like external package support is extremely limited, everything must be bundled in the final JS file (and transpiled from TS).

The dashboard is taken from https://grafana.com/grafana/dashboards/19665-k6-prometheus/
