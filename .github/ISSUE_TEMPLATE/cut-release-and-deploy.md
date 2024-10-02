---
name: Cut release and deploy
about: Cut release and upgrade DevNet nodes
title: Cut and deploy release 0.x.y
labels: ""
assignees: ""
---

## Cut release

Note: Some commands assume you are using the [fish](https://fishshell.com/) shell. If you are using other shells, you may need to adjust the commands accordingly. For example, `foo (bar)` in `fish` is equivalent to `foo $(bar)` in `bash`.

- [ ] Wait for everything to be merged that we want in it
  - [ ] ...
- [ ] Merge a PR into `origin/main` with the following changes:
  - [ ] Update the release notes (`docs/src/release_notes.rst`):
    - Replace `Upcoming` by the target version
    - Fix any spelling mistakes and make sure the RST rendering is not broken
    - Check whether any important changes are missing, for example by briefly comparing the release notes with `git log 0.x.z..` (replace `0.x.z` with the preg version)
  - [ ] Update `cluster/deployment/*/.envrc.vars`, bumping the release version.
    - Currently, the affected env vars are `OVERRIDE_VERSION`, `CHARTS_VERSION`, and `MULTI_VALIDATOR_IMAGE_VERSION`.
  - [ ] Make sure the merge commit has a `[release]` tag so it gets published as a non-snapshot version. You may have to edit the commit message when pressing the merge button in the GitHub UI.
- [ ] Create a release branch called `release-line-0.x.y` from the merged commit with the `[release]` tag
  - Note: release branches are subject to branch protect rules. Once you push the branch, you need to open PRs to make further changes.
- [ ] Trigger a CircleCI pipeline on the release branch with `run-job: publish-public-artifacts`
- [ ] Merge a PR into `origin/main` with the following changes:
  - Update `VERSION` and `LATEST_RELEASE` on main. `VERSION` should be the next planned release (typically bumping the minor version), and `LATEST_RELEASE` should be the version of the newly created release line.
- [ ] Update the Open source repos, see https://github.com/DACH-NY/canton-network-node/blob/main/OPEN_SOURCE.md
  - [ ] Merge the auto-generated PR in https://github.com/digital-asset/decentralized-canton-sync
  - [ ] Copy the Daml code to [Splice](https://github.com/hyperledger-labs/splice), and create a PR for it

## Upgrade our own nodes on DevNet

- [ ] Warn our partners on [#supervalidator-ops](https://daholdings.slack.com/archives/C05E70BCSDA): "We'll be upgrading the DA-2 and DA-Eng nodes on DevNet to test a new version. Some turbulence might be expected."
- [ ] Ensure all changes to the previous release branch are also included in main. This should be the case but sometimes a change gets missed.
  - Use one of the following approaches to find changes applied to release line `0.x.z` after it was branched off from main.
    - Run `git diff (git merge-base origin/release-line-0.x.z origin/main) origin/release-line-0.x.z` and compare it to the checked out source code of the release line you're upgrading to.
    - Run `git log (git merge-base origin/release-line-0.x.z origin/main)..origin/release-line-0.x.z` and compare it to the log of the release line you're upgrading to.
- [ ] Review the output of the `preview_pulumi_changes` CircleCi workflow together with someone else to see that there are no unexpected changes.
  - Pay particular attention to deleted or newly created resources.
  - Note that this should be the `preview_pulumi_changes` run on the branch `release-line-0.x.z`, as those are the changes that will be applied after merging!
- [ ] Merge a PR into `main` with the following changes:
  - [ ] Update the cluster `config.yaml` file by setting the new reference under `synchronizerMigration.active.releaseReference`.
  - [ ] Update the branch references in `.circleci/triggers/*/${cluster}-*.json`.
  - [ ] Before merging, review the changes to the `deployment` stack from the `preview_pulumi_changes` CircleCi workflow.
- [ ] Trigger a CircleCI pipeline on main with `run-job: update-deployment` and `cluster: devnet`.
  - This makes the operator track the release branch and kicks off the upgrade of our nodes on the cluster.
- [ ] Wait for [the operator](https://github.com/DACH-NY/canton-network-node/tree/main/cluster#the-operator) to apply your changes
  - A good check is `kubectl get stack -n operator -o json | jq '.items | .[] | {name: .metadata.name, status: .status}'` should show all stacks as successful and on the right commit.
- [ ] Confirm that we didn't break anything (e.g., via the sv status grafana dashboard)

## Tell our partners

- [ ] Communicate to partners that a new version is available

## Persist lessons learned

- [ ] ...
