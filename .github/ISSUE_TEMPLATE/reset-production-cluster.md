---
name: Reset production cluster
about: (Planned) reset of DevNet or TestNet
title: Reset production cluster
labels: ""
assignees: ""
---

Scheduled for: *date + time*

- [ ] (a few days before the scheduled time) Remind people on [#supervalidator-operations](https://daholdings.slack.com/archives/C085C3ESYCT) and [#validator-operations](https://daholdings.slack.com/archives/C08AP9QR7K4) that the reset is planned.
- [ ] Merge a PR to the correct release branch that unprotects the databases so they can be deleted. Wait for this change to be applied (you can check [grafana](https://grafana.test.global.canton.network.digitalasset.com/d/QP_wDqDnz/pulumi-operator-stacks-dashboard?orgId=1) for example). Example PR: #16323
- [ ] Prepare (don't merge yet!) a PR against the release branch for bootstrapping the new cluster (example: #16324). This includes but might not be limited to:
  - [ ] Set migration ID to 0
  - [ ] Increment `COMETBFT_CHAIN_ID_SUFFIX` by 1
  - [ ] Remove any `legacy` or `archive` migrations that might still be around.
  - [ ] Make sure that the current config of the running cluster matches what you will bootstrap (see `INITIAL_PACKAGE_CONFIG_JSON`)
- [ ] (before starting the actual reset) Send another update to SVs and validators as well as internal channels that could be relevant.
- [ ] (while pairing with someone) Uninstall the pulumi operator with `cncluster pulumi operator down`. Note that this does not take down the actual components, only the operator stack resources, so that the operator does not
      accidentally kick in at wrong times.
- [ ] (while pairing with someone) Reset all the sv-canton stacks for **archived** migrations one day before the actual reset. You can also delete PVC snapshots and CloudSQL backups for the active migration. Expect this to be slow, which is why it's done one day in advance.
- [ ] (while pairing with someone) Reset all stacks **except** the `infra` stack manually. `cncluster reset` could work, `CI=1 cncluster pulumi XYZ down --yes --skip-preview` will certainly work (check `kubectl get stacks -A` for stacks you should down and don't forget to also down the `deployment` stack). Expect some slowness/timeouts/rate-limiting from GCP.
- [ ] Merge the PR you prepared above
- [ ] Forward-port the PR you prepared above to `main`.
- [ ] Once merged to main, redeploy the operator through CircleCI: on `main`, trigger `run-jon: deploy-operator`, `cluster: ...`.
- [ ] Wait for the network to deploy and confirm that the `AmuletRules` `packageConfig` contains the expected DAR versions.
- [ ] Prepare and merge a second PR to the release branch that configures wallet sweeps to the DA-Wallet party (`SV1_SWEEP`, you need their wallet to be onboarded, you can ask in [#da-wallet](https://daholdings.slack.com/archives/C073K97TL3U)) and (unless you already did this earlier) sets the `cloudSql.protect` back to `false` (example PR: https://github.com/DACH-NY/canton-network-node/pull/16329).
- [ ] Tell SVs: "You are welcome to join now with migration ID 0 and chain ID X. Please reset your existing nodes completely, clearing out all databases and PVCs, and then onboard afresh." (example text, tweak as needed)
- [ ] Tell validators: "Please wait until bootstrapping has complete and join in 2h from now, using migration ID 0. Please reset your existing nodes completely, clearing out all databases, and then onboard afresh." (example text, tweak as needed)
- [ ] Forward port the final state on the release branch to main
- [ ] Fix anything in this template that you didn't like
