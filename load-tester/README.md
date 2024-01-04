# Load Tester

This directory contains the code for the CN Load Tester, built around `k6.io` scripts.

Currently there is only one test, `generate-load.js`, whose purpose is to generate CN app load via repeated taps. It is deployed & run perpetually in CN clusters that have enabled the `K6_ENABLE_LOAD_GENERATOR` environment flag.

It is possible to run this script in a developer initiated, ad-hoc manner via `cncluster load_test`.

In the future we may optionally add other kinds of load tests for different workflows, at different scales, to run in an ad-hoc manner (i.e. developer initiated via `cncluster load_test`).

## Instructions

To run this test from your machine:

1. Add the following to your `.envrc.private` variables:
    - `export K6_OAUTH_DOMAIN="https://canton-network-dev.us.auth0.com"`
    - `export K6_OAUTH_CLIENT_ID="5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK"`
    - `export K6_USERS_PASSWORD=<PASSWORD>` where `<PASSWORD>` is the actual password found in the passwords gdoc
    - `export K6_VALIDATOR_ADMIN_PASSWORD=<PASSWORD>` where `<PASSWORD>` is the password for `admin@validator1.com`
    - `export K6_TEST_DURATION="5m"` (or a different duration if you wish)
2. Create & deploy a scratchnet cluster
3. Run `cncluster load_test` from the cluster's deployment directory
4. Visit `https://grafana.<SCRATCH>.network.canton.global/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-prometheus` to view results

The current test runs a workload of 10 VUs (virtual users; these are concurrent processes that represent user clients) that iteratively hit the `tap` endpoint for the total duration specified in K6_TEST_DURATION.

These options are configured in `src/config.ts`.

## Cluster Deployment

The load test is used to generate continuous load against a Canton Network cluster that has it enabled. To enable it, the env flag `K6_ENABLE_LOAD_GENERATOR` must be set to `true`.

Eventually we plan to run the load generator on the following clusters:

- `cidaily`
- `cidaily-testnet`
- `cilr`
- `devnet`
- `testnet`

The load test config does not support an infinite duration, so the test is run repeatedly.

The test runs a k8s `Job` that is scheduled via a `CronJob` in the `load-tester` namespace. See `cluster/pulumi/canton-network/src/scheduleLoadGenerator.ts` for details.

The `CronJob` is configured to start a new `Job` instance of the test every hour. The test itself is then configured to run for 58 minutes, so that each `Job` completes before the next one begins.

To temporarily stop a currently running load test, first suspend the `CronJob` via the k9s CLI or `kubectl patch cronjobs <cronjob-name> -p '{"spec" : {"suspend" : true }}'`.

After that, delete the currently running `Job` via the k9s CLI or `kubectl delete job <job-name>`.

To permanently disable the load tester for a cluster, set (or remove) its `K6_ENABLE_LOAD_GENERATOR=false` in its directory's `.envrc`.
