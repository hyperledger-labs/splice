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
- [ ] create a temp branch off of the release branch
- [ ] on this branch
   - [ ] reapply all changes made to the current `deployment/devnet` that look as if we might still need them (e.g., the changes after the last hard migration)
   - [ ] adjust the versions in `cluster/deployment/devnet/.envrc.vars` to match our new version
- [ ] Push your temp branch.
- [ ] Trigger a CircleCI pipeline on this branch with `run-job: preview-changes` and `cluster: YOUR_TARGET_CLUSTER`. Review the output to see that there are no unexpected changes.
      Pay particular attention to deleted or newly created resources.
- [ ] force push the temp branch we're on to `deployment/devnet` (**while pairing with someone!** note that you might need someone to change the branch config so you can do this; try asking Martin, Itai, Moritz, or Nicu)
- [ ] wait for [the operator](cluster/README.md#the-operator) to apply your changes
      A good check is `kubectl get stack -n operator -o json | jq '.items | .[] | {name: .metadata.name, status: .status}'` should show all stacks as successful
      and on the right commit.
- [ ] confirm that we didn't break anything (e.g., via the grafana dashboards and a manually triggered preflight check)
- [ ] resume health checks (if paused)

## Tell our partners

- [ ] communicate to partners that a new version is available

## Persist lessons learned

- [ ] ...
