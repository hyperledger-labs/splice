---
name: Cut release and deploy
about: Cut release and upgrade DevNet nodes
title: Cut and deploy release 0.x.y
labels: ''
assignees: ''

---

## Cut release

- [ ] Wait for everything to be merged that we want in it
  - [ ] ...
- [ ] Do the steps from https://github.com/DACH-NY/canton-network-node/blob/main/RELEASE.md; make sure to:
  - [ ] fix the release notes (if needed) and change `Upcoming` to the target version.
  - [ ] Make sure the commit you want to release from has a `[release]` tag so it gets published as a non-snapshot version.
        If you tweak the release notes, this can be the same commit.
  - [ ] Create release branch from the commit with the `[release]` tag
  - [ ] Trigger a release on the release branch
  - [ ] Trigger CI job `publish-public-artifacts` on release branch
  - [ ] bump `VERSION` and `LATEST_RELEASE` on main
- [ ] Open source any Daml changes, see https://github.com/DACH-NY/canton-network-node/blob/main/OPEN_SOURCE.md

## Upgrade our own nodes on DevNet

- [ ] (optional) pause health checks
- [ ] warn our partners on [#global-synchronizer-ops](https://daholdings.slack.com/archives/C05E70BCSDA): We'll be upgrading our nodes on DevNet to test a new version. Some turbulence might be expected.
- [ ] Ensure all changes backported to the previous release branch are also included in main, e.g., by checking (adjusting branches as needed)
      `git diff (git merge-base origin/release-line-0.1.13 origin/main) origin/release-line-0.1.13`
      and ensuring that all changes are already included in the new release branch you're upgrading to. This should be the case but sometimes
      a change gets missed. Note: At the moment, for testnet and mainnet the previous branch is still `deployment/testnet`, `deployment/mainnet` so view the diff against that.
- [ ] Make a PR against the `.envrc.vars` file for the cluster you're upgrading in the release branch that bumps the versions.
- [ ] Forward port that PR to `main`.
- [ ] Trigger a CircleCI pipeline on the release branch (after merging the PR) with `run-job: preview-changes` and `cluster: YOUR_TARGET_CLUSTER`. Review the output
      together with someone else to see that there are no unexpected changes.
      Pay particular attention to deleted or newly created resources.
- [ ] Make a PR against main that changes the `CN_DEPLOYMENT_FLUX_REF` variable in the respective `.envrc.vars` to the new release branch, changes
      the branch references in `.circleci/configs/*.yml` and the branch references in `.circleci/triggers/*/${cluster}-*.json`.
- [ ] Trigger a CircleCI pipeline on this PR branch (after merging the PR) with `run-job: preview-changes` and `cluster: YOUR_TARGET_CLUSTER` and review the changes to the `deployment` stack.
- [ ] After merging to main, trigger a CircleCI pipeline on main with `run-job: update-deployment`. This makes the operator track the release branch.
- [ ] wait for [the operator](cluster/README.md#the-operator) to apply your changes
      A good check is `kubectl get stack -n operator -o json | jq '.items | .[] | {name: .metadata.name, status: .status}'` should show all stacks as successful
      and on the right commit.
- [ ] confirm that we didn't break anything (e.g., via the grafana dashboards and a manually triggered preflight check)
- [ ] resume health checks (if paused)

## Tell our partners

- [ ] communicate to partners that a new version is available

## Persist lessons learned

- [ ] ...
