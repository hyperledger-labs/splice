# CN Cost analysis scripts

This directory consists of several scripts which we've found useful for analyzing
cloud costs of the CN team. In the future, we may consider running these periodically
and/or adding metrics based on them to detect early when spend is growing beyond expectations.

## cci-costs.sh

Summarizes the CircleCI costs/month, as observed in the last 3 months, including total monthly
and the highest cost workflows.

Note that this script requires a CIRCLECI_TOKEN environment variable. A token can be obtained
from CircleCI web UI, under User Settings->Personal API Tokens->Create New Token

## detect-no-scaledown.sh

(Should be run from a cluster deployment directory)

Computes the amount of unused available cores in the cluster over the past week.
The most wasteful hour over the last week (max over the week of min over an hour
of the wasted CPUs) is reported.
If this number is high, investigate the autoscaler and whether it was blocked from
scaling down nodes that were not being utilized.

## logging-costs.sh

(Should be run from a cluster deployment directory)

Summarizes the average logging rate over the last week, total for the cluster, as well
as top containers and namespaces by log rate.

## overprovisioned-cpus.sh

(Should be run from a cluster deployment directory)

Summarizes the CPU usage against the CPU request per container name over the last week.
Useful for identifying components for which the requests can be safely reduced.
