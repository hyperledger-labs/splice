# Load Tester

This directory contains the code for the CN Load Tester, built around `k6.io` scripts.

## Instructions

To run this test:

1. Set `K6_LOAD_TEST_USER` var in your `.envrc.private` to `user@cn-load-tester.com:<PASSWORD>`, where `<PASSWORD>` is the actual password found in the passwords gdoc
2. Create & deploy a scratchnet cluster
3. Run `GCP_CLUSTER_BASENAME=<SCRATCH> ./run-k6.sh`
4. Visit `https://grafana.<SCRATCH>.network.canton.global/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-prometheus` to view results

The current test runs a workload of 10 VUs (virtual users; these are concurrent processes that represent user clients) that iteratively hit the `tap` endpoint for a total duration of 5 minutes.

These options are configured in `src/config.ts`.
