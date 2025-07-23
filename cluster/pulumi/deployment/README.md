# deployment (Pulumi project)

Manages an [operator](../operator)-based Splice deployment by creating Pulumi stack CRs based on Splice Pulumi projects such as:
[canton-network](../canton-network), [sv-canton](../sv-canton), [validator-runbook](../validator-runbook), and others.

The deployment of the `deployment` stack itself is managed by the operator,
its Stack CR being created together with the operator deployment.
