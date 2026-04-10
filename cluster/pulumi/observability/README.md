# Observability (Pulumi project)

Manages common setup expected by Splice deployments that is scoped to a single cluster, including

- monitoring and alerting (Grafana, Prometheus)
- GCP log-based alerting

Typically managed by an [operator](../operator).
In manually managed clusters gets updated as part of `cncluster apply`.
