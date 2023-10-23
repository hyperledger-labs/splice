# CircleCI

In the Canton Network team, we use CircleCI to manage continuous integration with tests, image building, image pushing, and ultimately deployment.

Currently we run our DevNet in a kubernetes cluster hosted on Google's GCE/GKE platform. Google also offers private docker registries via the Artifact Registry. All images we build are pushed to this registry.

Along the way, we have the ability to issue Slack notifications from within a job to our team channel based upon the end result of a workflow.

## GCP Auth

To authenticate to Google Cloud from CircleCI, we've created a service account with the appropriate permissions. Service accounts are administrable in Google Cloud [here](console.cloud.google.com/iam-admin/serviceaccounts).

For the CI service account, we've generated a private key and have injected it into CI builds via the `GCLOUD_SERVICE_KEY` environment variable. This variable is defined in the CircleCI Project Settings.

Within any job definition, the key file can then be fed into the `gcloud` CLI's auth command via standard input, like so:

```bash
echo $GCLOUD_SERVICE_KEY | gcloud auth activate-service-account --key-file=-
```

Note that the permissions granted to the service account can be changed in real-time, without having to recreate a new key.

Additionally, the key itself has no expiry. At a future point, we may want to consider regular key rotations to improve our security posture.

## Images

We build docker images for all applications as part of the `build` workflow, and push them to Google Artifact Registry. This is covered by the `docker-images-push` job. This job runs nightly as part of the `deploy` workflow.

## Deployments

The `deploy` workflow is used to trigger a deployment from CircleCI.

Note there is a script `search-ci-deployment.sh -n <WORKFLOW_NAME>` to search for the latest deployment in the workflow. This is quite useful as it avoids scrolling forever in the Circle-CI GUI, for example to get the link of the latest `deploy_testnet` workflow deployment.

### VPN Access

As our cluster management APIs are protected behind an IP whitelist, we make use of the `circleci_ip_ranges: true` configuration key to ensure the deployment job runs from behind a set of static, well-known CircleCI IP addresses ([ref](https://circleci.com/docs/ip-ranges)).

These IPs are whitelisted in Google Cloud.

### Scheduled Pipelines

To facilitate automatic nightly deployments, we use scheduled pipelines. CircleCI has an alternative feature known as scheduled _workflows_, but that is [being deprecated](https://circleci.com/docs/workflows#scheduling-a-workflow) and has designated EOL by end of 2022.

However, with scheduled pipelines, schedules are set up externally to `config.yaml`. So there is some additional tooling here in the `.circleci` directory to keep that configuration source-controlled and consistently applied automatically.

1. There is a payload in `run-schedule-pipeline.json` that defines the schedule's name, description, pipeline parameters, and timetable.
2. There is a script `setup-pipeline.sh` that creates-or-updates the schedule defined by (1), and
3. There is a CI job `update_pipeline_schedule` that runs during the `build` workflow that executes (2) automatically. This job is filtered to only run during a merge into the `main` branch.

With these three bits of tooling, we have a setup where the schedule can be modified in-repo, put in a pull-request for review, and enacted automatically after approval & merge.
