---
name: Cut release and deploy
about: Cut release and upgrade DevNet nodes
title: Cut and deploy release 0.x.y
labels: ""
assignees: ""
---

## Cut release

Note: Some commands assume you are using the [fish](https://fishshell.com/) shell.
If you are using other shells, you may need to adjust the commands accordingly.
For example, `foo (bar)` in `fish` is equivalent to `foo $(bar)` in `bash`.

The `VERSION` file specifies the release version to build, here referred to as `0.x.z`.
The previous release is referred to as `0.x.y` in these instructions.

Regular releases are started from `origin/main`, while bugfix / patch releases are started from a previous release line.
For the rest of this checklist, this will be called the _ancestor branch_.

Release versions can only be published from a _release line branch_. The _release line branch_ is `release-line-0.x.z` for release `0.x.z` in these instructions.
A _release line branch_ is branched from the _ancestor branch_.

- [ ] Choose the _ancestor branch_. This can be `origin/main` for all regular releases or `origin/release-line-0.x.y` for bugfix releases.
- [ ] Wait for everything to be merged in the _ancestor branch_ that we want in `0.x.z`.
  - [ ] ...
- [ ] Ensure all changes to the previous release branch `origin/release-line-0.x.y` are also included in both the _ancestor branch_ and `origin/main`.
      This should be the case but sometimes a change gets missed.
    - Use one of the following approaches to find changes applied to release line `0.x.y` after it was branched off its ancestor branch (which may be different from the ancestor branch of the new release).
        - Run `git diff (git merge-base origin/release-line-0.x.y ANCESTOR_BRANCH) origin/release-line-0.x.y` and compare it to the checked out source code of the release line you're upgrading to.
        - Run `git log (git merge-base origin/release-line-0.x.y ANCESTOR_BRANCH)..origin/release-line-0.x.y` and compare it to the log of the release line you're upgrading to.
        - Open https://github.com/DACH-NY/canton-network-node/compare/BRANCH_COMMIT...release-line-0.x.y to see the changes in the GitHub UI, where `BRANCH_COMMIT` is the commit that the release line was branched off from.
- [ ] Merge a PR into the _ancestor branch_ with the following changes:
  - [ ] Update the release notes (`docs/src/release_notes.rst`):
    - Replace `Upcoming` by the target version
    - Fix any spelling mistakes and make sure the RST rendering is not broken
    - Check whether any important changes are missing, for example by briefly comparing the release notes with `git log 0.x.y..` (replace `0.x.y` with the prev version)
- [ ] Create a release branch called `release-line-0.x.z` from the merged commit
    - Note: release branches are subject to branch protect rules. Once you push the branch, you need to open PRs to make further changes.
- [ ] Merge a PR into the release branch (`origin/release-line-0.x.z`) with the following changes:
  - [ ] Create an empty commit with `[release]` in the commit message so it gets published as a non-snapshot version. You may have to edit the commit message when pressing the merge button in the GitHub UI.
- [ ] Trigger a CircleCI pipeline from the DA-internal (on main) with `run-job: publish-release-artifacts` and `splice-git-ref: release-line-0.x.z`
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
- [ ] Communicate to partners that a new version is available

## Upgrade our own nodes on DevNet

- [ ] If significant time has passed since cutting the release, ensure that there are no changes that need to be backported to the release branch.
      In particular, check for changes to the `cluster/configs` and `cluster/configs-private` submodules.
- [ ] Merge a PR into the release branch (`origin/release-line-0.x.z`) with the following changes:
  - [ ] Update the cluster `config.yaml` file by setting the new reference under `synchronizerMigration.active.releaseReference` and update the `synchronizerMigration.active.version` to version `0.x.y`.
  - [ ] Update `cluster/deployment/devnet/.envrc.vars`, bumping the release version.
    - Currently, the affected env vars are `OVERRIDE_VERSION`, `CHARTS_VERSION`, and `MULTI_VALIDATOR_IMAGE_VERSION`.
  - [ ] Before merging, open the `preview_pulumi_changes` CircleCi workflow and approve the jobs to generate `deployment` and `devnet` previews.
    Review the changes together with someone else, paying particular attention to deleted or newly created resources.
- [ ] Warn our partners on [#supervalidator-operations](https://daholdings.slack.com/archives/C085C3ESYCT): "We'll be upgrading the DA-2 and DA-Eng nodes on DevNet to test a new version. Some turbulence might be expected."
- [ ] Forward-port the changes to `config.yaml` and `cluster/deployment/devnet/.envrc.vars` to `main`. The `deployment` stack, which watches `main`, should pick that up
and upgrade the other pulumi stacks.
- [ ] Wait for [the operator](https://github.com/DACH-NY/canton-network-node/tree/main/cluster#the-operator) to apply your changes
    - A good check is `kubectl get stack -n operator -o json | jq '.items | .[] | {name: .metadata.name, status: .status}'` should show all stacks as successful and on the right commit.
      Remember to check that the `lastSuccessfulCommit` field points to the release line that you expect.
- [ ] Confirm that we didn't break anything; for example:
  - [ ] The [SV Status Report Dashboard](https://grafana.dev.global.canton.network.digitalasset.com/d/caffa6f7-c421-4579-a839-b026d3b76826/sv-status-reports?orgId=1) looks green
  - [ ] There are no (unexpected) open alerts
  - [ ] The docs are reachable at both https://dev.network.canton.global/ and https://dev.global.canton.network.digitalasset.com/

## Upgrade our own nodes on TestNet and MainNet

- [ ] One week after DevNet: TestNet
- [ ] One week after TestNet: MainNet

## Follow up

- [ ] If you cut a release, remind the next person in the [rotation](https://docs.google.com/document/d/1f0nVeRnnxKQxwPi5nI2TiMq6qtHPwgiOjtUPUVJMKIk/edit?tab=t.0) that is up for cutting a release next week.
- [ ] Persist any lessons learned and fix (documentation) bugs hit
