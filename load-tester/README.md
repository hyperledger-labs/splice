# Load Tester

This directory contains the code for the Splice Load Tester, built around `k6.io` scripts.

Currently there is only one test, `generate-load.js`, whose purpose is to generate Splice app load via repeated p2p transfers. It is deployed as a Kubernetes Deployment in Splice clusters that have `loadTester.enable: true` in their cluster config YAML. An optional adaptive controller can automatically scale load to find maximum throughput (see Adaptive Load Testing below).

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

The load test is deployed as a Kubernetes `Deployment` in the `load-tester` namespace, running continuously in clusters that have it enabled. Configuration is managed via `cluster/pulumi/canton-network/src/scheduleLoadGenerator.ts` and the cluster's YAML config.

To enable the load tester for a cluster, set `loadTester.enable: true` in the cluster's config YAML.


To temporarily stop a running load test, scale down the Deployment via k9s or `kubectl scale deployment -n load-tester load-tester --replicas=0`. Scale back to 1 to resume.

To permanently disable the load tester for a cluster, set `loadTester.enable: false` (or remove the `loadTester` section) in the cluster's config YAML.

## Adaptive Load Testing

When `adaptiveScenario.enabled` is set to `true`, the load tester runs an adaptive controller (see `cluster/images/load-tester/entrypoint.sh`) that automatically scales virtual users (VUs) up and down based on transfer failure rates:

- **Scale up:** Every 5 minutes with no new failures, VUs increase by `scaleUpStep` (default: 2), up to `maxVUs`.
- **Scale down:** When failures are detected, VUs decrease by `scaleDownStep` (default: 5), down to `minVUs`.

This allows the test to find the maximum sustainable throughput for the cluster.

### Configuration

The adaptive scenario is configured in the cluster's `config.yaml` under `loadTester.adaptiveScenario`:

### Active Window

The adaptive controller only ramps up during a daily time window, configured by `windowStartUTC` (default: `"03:00"`) and `windowDurationMinutes` (default: `120`). Outside this window, adaptive VUs are scaled to 0.

This is designed for test clusters where an automatic upgrade runs at a known time (e.g., CILR upgrades at 02:00 UTC) -- the adaptive test window starts after the upgrade completes, giving a clean daily performance signal. The baseline `generate_load` scenario continues running at all times.

### Forcing an Immediate Ramp-Up

To trigger the adaptive load test immediately, edit the Deployment to set `windowStartUTC` to the current time (or a few minutes in the future). The controller re-evaluates the window on every loop iteration, so the change takes effect after the current sleep cycle (at most 5 minutes).

## Multi-Validators

Running the load tester with _many_ validators:

1. Set the cluster vars
    - `loadTester.enable: true` in the cluster's config YAML
    - `MULTIVALIDATOR_SIZE=<num>` (see cluster/README.md)
2. Deploy the base cluster
3. Apply the `multi-validator` stack (`cncluster apply_multi`)

Running this setup locally via `cncluster load_test` or against a non-devnet cluster is not supported.
