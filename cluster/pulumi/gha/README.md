# gha (Pulumi project)

Sets up infrastructure for running GitHub Actions self-hosted runners in the way expected by the Splice CI.

Deployed automatically on the `splice` cluster via an [operator](../operator) which tracks the main branch in the
[DACH-NY/canton-network-internal](https://github.com/DACH-NY/canton-network-internal) repository.
You should run a preview of the deployment by running `cncluster gha_preview` before merging,
to make sure that the operator will run the expected changes correctly.

Use `cncluster pulumi gha up` for manual deployment, but be cautious as it may conflict with operator's actions.
