# Preflight Failures Investigation Checklist

This checklist describes the steps you should go through when
investigating a the failure of a preflight.

The steps are similar to [investigating flakes](support/FLAKE_CHECKLIST.md)

## Tracking Preflight failures

Follow the same process as with [tracking flakes](https://github.com/DACH-NY/canton-network-node/blob/main/support/FLAKE_CHECKLIST.md#tracking-flakes).
The differences are as follow:
- In the title of the issue include the name of the test
- In the body of the issue specify the cluster where it failed
- In the body of the issue include the CircleCI output that includes the specific test failures. This will make issues easier to search.

## Investigating failures

- Same rules apply as for the flakes, make sure to first be familiar with how to investigate a flake.
- Remember that the preflights run with the same version as the one deployed in the cluster, therefore before investigating the code make sure to check out the cluster-specific branch that is in sync with the deployed version.


1. Check when was the  deployment for the cluster, and if it could've introduced new features/changed the test thus triggering the failures. The age of the pods can be a decent indication
2. Using either k9s (from a cluster directory like `cluster/deployment/cidaily`) or through the [gcloud ui](https://console.cloud.google.com/kubernetes/workload/overview?project=da-cn-scratchnet) validate the current deployment status and check if any components are failing or restarting.
3. The CI job has a step *Displaying commands to download gcloud logs*. This prints the `cncluster gcloud_logs` command to download the logs for each app in our cluster for the timeframe the test ran.
Downloading all of them can be slow so usually you want to identify the potentially relevant apps based on the tests that failed.
5. Attach the downloaded logs to the issue to help future debug efforts.
6. Investigate the logs locally or on the google cloud logs UI (selecting the same interval as printed by the `cncluster gcloud_logs` download commands). Search for ERRORS and any other meaningful logs that showcase the root cause. For more details on how to track the root cause check the flake checklist.
7. Add any relevant logs found to the github issue, building a timeline for the failure or showcasing the invalid state of the system.
