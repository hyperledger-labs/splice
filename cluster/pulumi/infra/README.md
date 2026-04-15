# infra (Pulumi project)

Manages common setup expected by Splice deployments that is scoped to a single cluster, including

- IAM (auth0)
- ingress (Istio)
- DNS entries

The long-term goal is to split this into smaller projects;
consider taking steps towards this goal whenever you extend this.

Typically managed by an [operator](../operator).
In manually managed clusters gets updated as part of `cncluster apply`.
