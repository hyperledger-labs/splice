---
name: Reset production cluster
about: (Planned) reset of DevNet or TestNet
title: Reset production cluster
labels: ""
assignees: ""
---

Scheduled for: *date + time*

- [ ] (a few days before the scheduled time) Remind people on [#supervalidator-ops](https://daholdings.slack.com/archives/C05E70BCSDA) and [#validator-ops](https://daholdings.slack.com/archives/C06QB1ZEGCE) that the reset is planned.
- [ ] Merge a PR to the correct release branch that unprotects the databases so they can be deleted. Wait for this change to be applied (you can check [grafana](https://grafana.test.global.canton.network.digitalasset.com/d/QP_wDqDnz/pulumi-operator-stacks-dashboard?orgId=1) for example). Example PR: #16323
- [ ] Prepare (don't merge yet!) a PR against the release branch for bootstrapping the new cluster (example: #16324). This includes but might not be limited to:
  - [ ] Set migration ID to 0
  - [ ] Increment `COMETBFT_CHAIN_ID_SUFFIX` by 1
  - [ ] Remove any `legacy` or `archive` migrations that might still be around.
  - [ ] Make sure that the current config of the running cluster matches what you will bootstrap (see `INITIAL_PACKAGE_CONFIG_JSON`)
- [ ] (before starting the actual reset) Send another update to SVs and validators as well as internal channels that could be relevant.
- [ ] (while pairing with someone) Reset all stacks **except** the `infra` stack manually. `cncluster reset` could work, `CI=1 cncluster pulumi XYZ down --yes --skip-preview` will certainly work (check `kubectl get stacks -A` for stacks you should down and don't forget to also down the `deployment` stack).
- [ ] Merge the PR you prepared above and re-deploy the operator (`run-job: update-deployment`, `cluster: ...`)
- [ ] Prepare and merge a second PR to the release branch that configures wallet sweeps to validator1 and (unless you already did this earlier) sets `DISABLE_CLOUD_SQL_PROTECT` back to `false` (example PR: https://github.com/DACH-NY/canton-network-node/pull/16329). An easy way to get the validator1 party is to log in to https://wallet.validator1.NET.global.canton.network.digitalasset.com/ using `admin@validator1.com` and copy the party ID from the top of the page there.
- [ ] Fix the `migration_id` in all relevant periodic CI jobs, on both main and the release branch (many periodic jobs run from the release branch). (This will become obsolete with #16337)
- [ ] Tell SVs: "You are welcome to join now with migration ID 0 and chain ID X. Please reset your existing nodes completely, clearing out all databases and PVCs, and then onboard afresh." (example text, tweak as needed)
- [ ] Tell validators: "Please wait until bootstrapping has complete and join in 27h from now, using migration ID 0. Please reset your existing nodes completely, clearing out all databases, and then onboard afresh." (example text, tweak as needed)
- [ ] Forward port the final state on the release branch to main
- [ ] Fix anything in this template that you didn't like
