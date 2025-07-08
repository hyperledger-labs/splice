# canton-network (Pulumi project)

Deploys a configurable number of SVs to found a new Splice synchronizer.

Deploys only Splice apps (SV app, validator app, scan app)!
For Canton nodes and CometBFT see [sv-canton](../sv-canton).

Also deploys a webserver with our public docs as well as (optionally) infrastructure for load tests.

Deployment typically managed via an [operator](../operator). For manual deployment s.a. `cncluster apply`.
