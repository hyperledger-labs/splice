# sv-runbook (Pulumi project)

Deploys a single SV, closely following (and thereby testing) the [public deployment documentation](https://docs.dev.sync.global/sv_operator/sv_helm.html).

The project itself deploys only Splice apps (SV app, validator app, scan app).
For Canton nodes and CometBFT see [sv-canton](../sv-canton).

Deployment typically managed via an [operator](../operator). For manual deployment s.a. `cncluster apply_sv`.
