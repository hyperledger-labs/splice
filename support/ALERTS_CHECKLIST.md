# Alert Investigation Checklist

A guide to investigating Grafana alerts. This document is a companion to the [Flake Checklist](support/FLAKE_CHECKLIST.md) and the [Preflight Checklist](support/PREFLIGHT_CHECKLIST.md) docs.

## Tracking Alerts

1. Perform a preliminary investigation by checking out the logs of the affected components.
2. Because alert instances don't have unique referenceable URLs (unlike CircleCI runs), use the gcloud log explorer link of a basic query to serve as the starting point of further investigation. The basic query should just include:
   - Timestamp (this can be "Around a time" with +-15 mins from the alert firing time)
   - `resource.labels.cluster_name="..."`
   - `resource.labels.namespace_name="..."`
3. Create a new issue in the Flaky Tests milestone with the `stability:cn-alert` label
4. Paste the link to the log explorer query and a short description of the issue (which alert rule fired, and any obvious log errors/warnings)

Example issue: https://github.com/DACH-NY/canton-network-node/issues/11663

## Investigating Alerts

When an alert from Grafana fires, a notification is sent to the `#team-canton-network-internal-alerts` channel. The notification aggregates all alerts currently firing at that time into one message.

To begin investigating the cause of an alert, follow these steps:

1. Click on the link in the Slack message, to open the page for the corresponding Alert Rule.
2. Each alert instance is identified by a set of labels, similar to metrics. The label will typically include information such as namespace, node_type, etc.
3. If the alert is currently firing, you can scroll down and view the 1 (or more) alert instances that are in the `Alerting` state.
   1. Check the timestamp that the alert instance was created at, and begin investigating.
   2. A good place to start is to open Google Logs Explorer and query around the timestamp, cluster name, and the namespace given by the alert label.
4. If the alert has resolved, but you still want to investigate a past instance, click on `Show state history`. This lists a history of alert instances grouped by label set. The label set is displayed above each state table. (The UI here kinda sucks).
   1. You now have the same information as above to query logs by timestamp, cluster name, namespace name, etc.

## Alert-Specific Tips

### Automation Failures

If the alert is an instance of the `Automation Failures` rule, one of the labels you get is the trigger name that caused the alert. In this case, you can add this to your Log Explorer query, replacing the trigger name:

```
jsonPayload.logger_name=~".*ReconcileSequencerLimitWithMemberTrafficTrigger.*"
```
