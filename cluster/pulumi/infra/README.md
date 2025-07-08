# infra (Pulumi project)

Manages common setup expected by Splice deployments that is scoped to a single cluster, including

- IAM (auth0)
- monitoring and alerting (Grafana, Prometheus)
- ingress (Istio)
- GCP log-based alerting
- DNS entries

Typically managed by an [operator](../operator).
In manually managed clusters gets updated as part of `cncluster apply`.
