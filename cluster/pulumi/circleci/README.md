# circleci (Pulumi project)

Sets up infrastructure for running CircleCI self-hosted runners in the way expected by the Splice CI.

Among other things, CircleCI workflows on self-hosted runners are used for making changes to cluster deployments,
for example for installing and updating the [operator](../operator).

Applied manually via `cncluster pulumi circleci up`.
