---
name: Hard migration
about: Perform a hard migration on a production cluster
title: NETWORK Hard Synchronizer Migration DATE
labels: ''
assignees: ''

---

Agenda [here](https://docs.google.com/document/d/1AEh9ZMLPxmc9tKn0L7I5S48xHOR4GN__VpP2_IHnL0A/edit#heading=h.9pjnt72egfzq). *PLEASE UPDATE LINK*

Internal runbook [here](https://github.com/DACH-NY/canton-network-node/blob/main/cluster/README.md#via-the-pulumi-operator).

Checklist:

- [ ] prepare our staging nodes and tell partners
- [ ] a sufficient number of partners have reported that they are ready / prepared (or look as if they are); check once and escalate if check failed
- [ ] vote on scheduled downtime
- [ ] disable periodic CI jobs (including sv and validator runbook resets)
- [ ] (only if DevNet) take down multi-validator stack (does not handle hard domain migrations in its current form): `cncluster pulumi multi-validator down` from release branch
- [ ] call with all SVs: checks before, backups, actual migration, checks after
- [ ] unset `GLOBAL_DOMAIN_MIGRATE_FROM_MIGRATION_ID` on the release branch so that future redeploys don't attempt to migrate
- [ ] patch periodic CI jobs
- [ ] trigger periodic CI jobs manually once to make sure the patches worked
- [ ] re-enable periodic CI jobs
- [ ] make sure that the versions and migration IDs from the release branch are forward ported to the main branch
- [ ] take down old synchronizer nodes (once we're allowed to based on agreement with other SVs)
- [ ] make sure that the [next planned production network operation](https://docs.google.com/document/d/14gZQNdXLPUCfqxN4vLK_yGlsptfcHMJZR8e1oOKgqLc/edit) has assignees and will get done; escalate if this is not the case
- [ ] improve docs (collect ideas here) and other things
