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
- [ ] Figure out from which branch we want to release. This can be `origin/main` or `origin/release-line-0.x`.
      For the rest of this checklist, this will be called the _ancestor branch_.
- [ ] Ensure all changes to the previous release branch `origin/release-line-0.x.z` are also included in both the _ancestor branch_ and `origin/main`.
      This should be the case but sometimes a change gets missed.
    - Use one of the following approaches to find changes applied to release line `0.x.z` after it was branched off its ancestor branch (which may be different from the ancestor branch of the new release).
        - Run `git diff (git merge-base origin/release-line-0.x.z ANCESTOR_BRANCH) origin/release-line-0.x.z` and compare it to the checked out source code of the release line you're upgrading to.
        - Run `git log (git merge-base origin/release-line-0.x.z ANCESTOR_BRANCH)..origin/release-line-0.x.z` and compare it to the log of the release line you're upgrading to.
        - Open https://github.com/DACH-NY/canton-network-node/compare/BRANCH_COMMIT...release-line-0.x.z to see the changes in the GitHub UI, where `BRANCH_COMMIT` is the commit that the release line was branched off from.
- [ ] Merge a PR into the _ancestor branch_ with the following changes:
  - [ ] Update the release notes (`docs/src/release_notes.rst`):
    - Replace `Upcoming` by the target version
    - Fix any spelling mistakes and make sure the RST rendering is not broken
    - Check whether any important changes are missing, for example by briefly comparing the release notes with `git log 0.x.z..` (replace `0.x.z` with the prev version)
  - [ ] Make sure the merge commit has a `[release]` tag so it gets published as a non-snapshot version. You may have to edit the commit message when pressing the merge button in the GitHub UI.
- [ ] Create a release branch called `release-line-0.x.y` from the merged commit with the `[release]` tag
  - Note: release branches are subject to branch protect rules. Once you push the branch, you need to open PRs to make further changes.
- [ ] Trigger a CircleCI pipeline on the release branch with `run-job: publish-public-artifacts`
- [ ] If _ancestor branch_ is not `origin/main`, forward port all changes made to the _ancestor branch_ as part of this release to `origin/main`
- [ ] Update the Open source repos, see https://github.com/DACH-NY/canton-network-node/blob/main/OPEN_SOURCE.md
  - [ ] Merge the auto-generated PR in https://github.com/digital-asset/decentralized-canton-sync
  - [ ] Merge the auto-generated PR in https://github.com/hyperledger-labs/splice
- [ ] After merging the PR on the DA OSS repo, go to Releases in that repo
      (https://github.com/digital-asset/decentralized-canton-sync/releases), find the draft
      release for the release you just created and publish it (click the edit pencil icon). This should be done after merging the PR because it will
      also automatically bundle the sources from the release-line branch.
- [ ] Merge a PR into the _ancestor branch_ with the following changes:
  - Update `VERSION` and `LATEST_RELEASE`. `VERSION` should be the next planned release (typically bumping the minor version), and `LATEST_RELEASE` should be the version of the newly created release line.

## Upgrade our own nodes on DevNet

- [ ] If significant time has passed since cutting the release, ensure that there are no changes that need to be backported to the release branch.
      In particular, check for changes to the `cluster/configs` and `cluster/configs-private` submodules.
- [ ] Merge a PR into the release branch (`origin/release-line-0.x.y`) with the following changes:
  - [ ] Update the cluster `config.yaml` file by setting the new reference under `synchronizerMigration.active.releaseReference` and update the `synchronizerMigration.active.version` to version `0.x.y`.
  - [ ] Update `cluster/deployment/devnet/.envrc.vars`, bumping the release version.
    - Currently, the affected env vars are `OVERRIDE_VERSION`, `CHARTS_VERSION`, and `MULTI_VALIDATOR_IMAGE_VERSION`.
  - [ ] Before merging, open the `preview_pulumi_changes` CircleCi workflow and approve the jobs to generate `deployment` and `devnet` previews.
    Review the changes together with someone else, paying particular attention to deleted or newly created resources.
- [ ] Warn our partners on [#supervalidator-operations](https://daholdings.slack.com/archives/C085C3ESYCT): "We'll be upgrading the DA-2 and DA-Eng nodes on DevNet to test a new version. Some turbulence might be expected."
- [ ] Trigger a CircleCI pipeline on the release branch with `run-job: update-deployment` and `cluster: devnet`.
    - This makes the operator track the release branch and kicks off the upgrade of our nodes on the cluster.
- [ ] Wait for [the operator](https://github.com/DACH-NY/canton-network-node/tree/main/cluster#the-operator) to apply your changes
    - A good check is `kubectl get stack -n operator -o json | jq '.items | .[] | {name: .metadata.name, status: .status}'` should show all stacks as successful and on the right commit.
      Remember to check that the `lastSuccessfulCommit` field points to the release line that you expect.
- [ ] Confirm that we didn't break anything (e.g., via the sv status grafana dashboard)
- [ ] Forward port the above change that bumped the devnet version to both the _ancestor branch_ and `origin/main`.
- [ ] Merge a PR into `origin/main` with the following changes:
  - [ ] Update the branch references in `.circleci/triggers/*/devnet-*.json` only for devnet.
        This will upgrade our periodic health checks to use the new release version.
        Old health checks may not work against the upgraded cluster, so expect some failures until this PR is merged.
- [ ] Communicate to partners that a new version is available

## Upgrade our own nodes on TestNet and MainNet

- [ ] One week after DevNet: TestNet
- [ ] One week after TestNet: MainNet

## Follow up

- [ ] Remind next person in the [rotation](https://docs.google.com/document/d/1f0nVeRnnxKQxwPi5nI2TiMq6qtHPwgiOjtUPUVJMKIk/edit?tab=t.0) that they are up next week.
- [ ] Persist any lessons learned and fix (documentation) bugs hit
  - [ ] ...
