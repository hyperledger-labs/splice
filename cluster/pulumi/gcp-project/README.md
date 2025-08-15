# gcp-project (Pulumi project)

Manages common setup expected by Splice deployments that is scoped to GCP projects.

Consider extending this whenever you need to change something on the GCP project level.
The long-term goal is that this project contains all setup required to initialize a fresh GCP project for use within the Splice project.

Currently deployed via a CircleCI workflow.
