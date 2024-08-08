# Operating on Production Clusters

This document describes guidelines for operating on production clusters (MainNet, TestNet, DevNet).

## Patching an [Operator](https://github.com/DACH-NY/canton-network-node/tree/main/cluster#operator-deployments) controlled deployment

1. Open a PR with the changes
2. Run the preview job for the clusters tracking the branch, by running on the branch that you created, a CCI job with the following arguments: `run-job=preview-operator-changes`, `cluster=<target_cluster>`
3. Post the link to the preview job in the PR you have opened, and (optionally) add the preview job output as a comment on the PR
4. Get approval for the PR
5. Post on the slack channel for the clusters that you are merging a patch
6. Merge the PR
7. Monitor the cluster health (as outlined further down in this document) and slack channels for notifications until the operator has finished applying your changes

## Emergency fixes on production clusters

We should always propagate any change through the operator, but if for some reason
that is not easily doable we should follow procedures outlined next.

### Pair with someone that has worked on prod clusters before

There are lots of subtleties of operating on production clusters and
in particular, under the pressure of an incident it is easy to make
mistakes. Therefore, pair on all steps with someone that has worked on
one of the production clusters before.

For commands that take very long (e.g. `cncluster pulumi
canton-network up`) it is ok to pair to the point where you start the
command and then start pairing again once it finishes (successful or
unsuccessful) instead of waiting together for it to finish.

### Prefer Pulumi over kubectl edit

It can often be tempting to quickly fix an issue with a `kubectl
edit`. However, this results in pulumi being unaware of that change so
the next time someone tries to apply a pulumi change it might revert
your fix.

Instead, default to trying to fix the issue such that you can apply it
using a `cncluster pulumi $stack up` and commit the change.

If you are unable to do that or time pressure is too high, a `kubectl
edit` can be a temporary option. However, ensure that you manually get
things in sync again afterwards (e.g., uninstall the helm chart,
refresh and reapply the stack).

### Use `cncluster pulumi` over the more high-level `cncluster` commands

In the current state, a lot of the `cncluster commands` are geared
towards a workflow where we fairly regularly reset clusters and for
local deployments to scratchnet.

On production cluters invoke `cncluster pulumi` directly instead of
high-level wrappers like `cncluster apply`.

### Use `cncluster preview --diff` before applying a stack

Before running `cncluster pulumi $stack up`, always make sure to run
`cncluster preview --diff` and audit the changes together with the
person you're pairing with. The main things to look out for are:
1. What resources get deleted.
2. For the resources that get updated, check the diff in values.
3. For new resources, you mainly want to look out for what resource
   gets created. The full list of values is often too long to
   reasonably audit it for every single resource.

After you've run a preview and audited the results, you can run
```
cncluster pulumi $stack up --skip-preview --yes
```


### Set CI=1 locally

`cncluster pulumi $stack up` and similar commands require setting the
`CI=1` env var. Instead of exporting that in your shell, or even
putting in in your `.envrc.private`, set it locally for individual
commands, e.g.,

```
CI=1 cncluster pulumi canton-network up --skip-preview --yes
```

This avoid potential mistakes where you exported it in the shell and
kept that shell and accidentally run a command in that shell later.

### Workaround for pulumi assertion errors

In some cases, you might see the pulumi assertion error described in [#12008](https://github.com/DACH-NY/canton-network-node/issues/12008).

In those cases, feel free to temporarily change your `ARTIFACTORY_USER` AND `ARTIFACTORY_PASSWORD` to the last user which works around this.
Easiest to do this in `.envrc.private` to make sure it applies everywhere.

### Keep an Audit Trail

Make sure that you capture all the steps you go through so that in
case something goes wrong, it is possible to reconstruct what might
have happened. For CLI commands, shell history is often good
enough. However, if you edit any files, e.g., change the pulumi config
and then rerun `cncluster pulumi $stack up` make sure that you create
a commit for each step and your `git status` is clean before you
actually apply the pulumi stack. Similar, if you do manual edits be it
through `kubectl edit` or the GCP UI, document the steps you've taken and
add them to GH issue or post-mortem doc afterwards.

## Monitor Cluster health

While working on the cluster and especially after you fixed the cluster, check [Network Health](./network-health/NETWORK_HEALTH.md).
In particular, make sure that our nodes can submit SV status reports and if some other SVs are unable to do so, follow the
steps in that doc to find the cause.

Make sure to make a PR with your latest state against
`deployment/$cluster` once you are finished.

## Lock to Release Versions

On production clusters should always use helm charts and docker images
built by CI from the `publish-public-artifacts` job.

To ensure that, make sure the `cluster/deployment/$cluster/.envrc.vars` file contains the following two lines:

```
exports CHARTS_VERSION=$VERSION
exports OVERRIDE_VERSION=$VERSION
```

Make sure to clean and rebuild the relevant setup before doing anything:

```
make -C $REPO_ROOT clean
make -C $REPO_ROOT cluster/build
```

## Handling Failed Pulumi Operations

If pulumi fails, you might need to run `cncluster pulumi $stack
cancel` followed by `cncluster pulumi $stack refresh`.
