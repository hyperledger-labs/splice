# Load Tester

This directory contains the code for the Splice Load Tester, built around `k6.io` scripts.

Currently there is only one test, `generate-load.js`, whose purpose is to generate Splice app load via repeated taps. It is deployed & run perpetually in Splice clusters that have enabled the `K6_ENABLE_LOAD_GENERATOR` environment flag.

It is possible to run this script in a developer initiated, ad-hoc manner via `cncluster load_test`.

In the future we may optionally add other kinds of load tests for different workflows, at different scales, to run in an ad-hoc manner (i.e. developer initiated via `cncluster load_test`).

## Instructions

To run this test from your machine:

1. Add the following to your `.envrc.private` variables:

   ```.bash
   export K6_OAUTH_DOMAIN="https://canton-network-dev.us.auth0.com"
   export K6_OAUTH_CLIENT_ID="5RJeTm41IwUs8VbbnZHxFEPjCX5ojfaK"
   export K6_TEST_DURATION="5m"

   # 60 iterations = 1 transfer/second
   export K6_ITERATIONS_PER_MINUTE="60"

   export K6_USERS_PASSWORD=""
   export K6_VALIDATOR_ADMIN_PASSWORD=""
   ```

   and use the [passwords gdoc](https://docs.google.com/document/d/1ajR8_SsSybl6GSrhGggOHEZPfCF0hzk0MDJMyziV7Vc/edit) to set
    - `K6_USERS_PASSWORD` to the password of `user@cn-load-tester.com`
    - `K6_VALIDATOR_ADMIN_PASSWORD` to the password of `admin@validator1.com`

2. Create & deploy a scratchnet cluster
3. Run `cncluster load_test` from the cluster's deployment directory
4. Visit `https://grafana.<HOSTNAME>/d/ccbb2351-2ae2-462f-ae0e-f2c893ad1028/k6-prometheus` to view results

The current test runs a workload of 10 users per validator that iteratively perform p2p transfers for the total duration specified in K6_TEST_DURATION. The rate at which users conduct transfers amongst each other is controlled by setting the iterations per minute.

These options are configured by passing in a JSON string via the environment, and parsed in `src/settings.ts`.

## Cluster Deployment

The load test is used to generate continuous load against a Splice cluster that has it enabled. To enable it, the env flag `K6_ENABLE_LOAD_GENERATOR` must be set to `true`.

Eventually we plan to run the load generator on the following clusters:

- `cidaily`
- `cilr`
- `devnet`
- `testnet`

The load test config does not support an infinite duration, so the test is run repeatedly.

The test runs a k8s `Job` that is scheduled via a `CronJob` in the `load-tester` namespace. See `cluster/pulumi/canton-network/src/scheduleLoadGenerator.ts` for details.

The `CronJob` is configured to start a new `Job` instance of the test every hour. The test itself is then configured to run for 58 minutes, so that each `Job` completes before the next one begins.

To temporarily stop a currently running load test, first suspend the `CronJob` via the k9s CLI or `kubectl patch cronjobs <cronjob-name> -p '{"spec" : {"suspend" : true }}'`.

After that, delete the currently running `Job` via the k9s CLI or `kubectl delete job <job-name>`.

To permanently disable the load tester for a cluster, set (or remove) its `K6_ENABLE_LOAD_GENERATOR=false` in its directory's `.envrc`.

## Multi-Validators

Running the load tester with _many_ validators:

1. Set the cluster vars
    - `K6_ENABLE_LOAD_GENERATOR=true`
    - `MULTIVALIDATOR_SIZE=<num>` (see cluster/README.md)
2. Deploy the base cluster
3. Apply the `multi-validator` stack (`cncluster apply_multi`)

Running this setup locally via `cncluster load_test` or against a non-devnet cluster is not supported.
