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
  - [ ] fix the release notes (if needed)
  - [ ] Create release branch
  - [ ] Create a non-snapshot release
  - [ ] Trigger CI job `publish-public-artifacts` on release branch
  - [ ] bump `VERSION` and `LATEST_RELEASE` on main

## Upgrade our own nodes on DevNet

- [ ] (optional) pause health checks
- [ ] warn our partners on [#global-synchronizer-ops](https://daholdings.slack.com/archives/C05E70BCSDA): We'll be upgrading our nodes on DevNet to test a new version. Some turbulence might be expected.
- [ ] create a temp branch off of the release branch
- [ ] on this branch
   - [ ] reapply all changes made to the current `deployment/devnet` that look as if we might still need them (e.g., the changes after the last hard migration)
   - [ ] adjust the versions in `cluster/deployment/devnet/.envrc.vars` to match our new version
- [ ] from this branch (**while pairing with someone!**): do `cncluster pulumi ... preview --diff` and (if preview makes sense) `cncluster pulumi ... up --yes --skip-preview`
  - [ ] `canton-network` stack
  - [ ] `sv-runbook` stack (if deployed)
  - [ ] `validator-runbook` stack (if deployed)
  - [ ] `multi-validator` stack (if deployed)
  - [ ] `infra` stack
- [ ] confirm that we didn't break anything (e.g., via the grafana dashboards and a manually triggered preflight check)
- [ ] force push the temp branch we're on to `deployment/devnet` (**while pairing with someone!** note that you might need someone to change the branch config so you can do this; try asking Martin, Itai, Moritz, or Nicu)
- [ ] resume health checks (if paused)

## Tell our partners

- [ ] communicate to partners that a new version is available

## Persist lessons learned

- [ ] ...
