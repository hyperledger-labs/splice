This folder contains configuration files required to start copies of `aliceParticipant` and `aliceValidator`
(henceforth `aliceValidatorLocal`),
with the intention of connecting them to a network maintained by [`sv1Local`](../local-sv-node).
The configs are modeled after the configs used in the cluster (see `cluster/images/**/*.conf`),
with tweaked ports so we don't conflict with `sv1Local` and its participant, and no auth on the participant.
