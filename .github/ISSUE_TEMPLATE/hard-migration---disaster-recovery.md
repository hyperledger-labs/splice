---
name: 'Hard migration / Disaster recovery'
about: 'Perform a hard migration or disaster recovery on a production cluster'
title: 'NETWORK [Hard Synchronizer Migration|Disaster Recovery] DATE'
labels: ''
assignees: ''

---

Agenda [here](https://docs.google.com/document/d/1AEh9ZMLPxmc9tKn0L7I5S48xHOR4GN__VpP2_IHnL0A/edit#heading=h.9pjnt72egfzq). *PLEASE UPDATE LINK*

Tracking sheet [here](https://docs.google.com/spreadsheets/d/1AKAVhGqxFkhe7kBnbLf9L-nfnpr1H0QjZ5PHMaVkvc8/edit?gid=128511196). *PLEASE UPDATE LINK*

Internal runbook [here](https://github.com/DACH-NY/canton-network-node/blob/main/cluster/README.md#via-the-pulumi-operator).

## Checklist

### Prepare

- [ ] If you are upgrading to a new Canton major version, manually test compatibility of dev/test/mainnet snapshots as described in `cluster/README.md`
- [ ] open or create corresponding agenda and tracking sheet in [this folder](https://drive.google.com/drive/folders/1-HZPAiZ7wVei4nlp-AOyQ5TrZ-S5FVSB)
- [ ] prepare our staging nodes and tell partners
  - [ ] (later) forward-port to branches that may serve as potential future release sources (e.g. for 0.2.8, 0.2 and `main`; `main` is always included)
- [ ] a sufficient number of partners have reported that they are ready / prepared (or look as if they are); check once and escalate if check failed
- [ ] (only if hard migration) vote on scheduled downtime
- [ ] disable periodic CI jobs (including sv and validator runbook resets) on `main`
- [ ] (only if DevNet) take down multi-validator stack (does not handle hard domain migrations in its current form): `cncluster pulumi multi-validator down` from release branch
- [ ] (only if disaster recovery) test the `cncluster take_disaster_recovery_dumps` step
- [ ] take backups with `cncluster backup_nodes` (all nodes in parallel!) as you would during the meeting - to confirm that the commands work for you and to have them ready
- [ ] (shortly before the call) request PAM in case you'll need it later

### Call with all SVs (hard migrations version; remove me if DR)

- [ ] wait for current synchronizer to pause and dumps to be taken (`Wrote domain migration dump` in SV app / validator app logs)
- [ ] ensure that apps are sufficiently caught up
- [ ] take backups with `cncluster backup_nodes`
- [ ] merge PRs for deployment branch & `main` to migrate to higher migration ID
- [ ] check: domain is healthy

### Call with all SVs (DR version; remove me if hard migration)

- [ ] everyone scales down their CometBFT nodes with `kubectl scale deployment --replicas=0 -n <namespace> global-domain-<old-migration-id>-cometbft`
- [ ] take backups with `cncluster backup_nodes`
- [ ] agree on a timestamp based on logs (e.g., ask everyone for the `toInclusive` value of their latest `Commitment correct for sender and period CommitmentPeriod(fromExclusive = X, toInclusive = THIS)` log entry on the participant and use the min of that)
- [ ] get the dumps with `cncluster take_disaster_recovery_dumps`
- [ ] copy the dumps into our PVCs with `cncluster copy_disaster_recovery_dumps`
- [ ] merge PRs for deployment branch & `main` to migrate to higher migration ID
- [ ] check: domain is healthy

### Cleanup

- [ ] unset `synchronizerMigration.active.migratingFrom` on the release branch so that future redeploys don't attempt to migrate
  - [ ] (later) forward-port to branches that may serve as potential future release sources
- [ ] trigger periodic CI jobs manually once to make sure the updates worked
- [ ] re-enable periodic CI jobs on `main`
- [ ] recheck above forward-port items (e.g. versions, migration IDs)
- [ ] take down old synchronizer nodes (once we're allowed to based on agreement with other SVs)

### Follow-up

- [ ] make sure that the [next planned production network operation](https://docs.google.com/document/d/14gZQNdXLPUCfqxN4vLK_yGlsptfcHMJZR8e1oOKgqLc/edit) has assignees and will get done; escalate if this is not the case
- [ ] improve docs (collect ideas here) and other things
