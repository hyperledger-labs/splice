IMPORTANT NOTE: copied and slightly adapted from the respective file from Canton's enterprise repo

Name Allocation
===============

Our deployment topologies require many servers representing nodes and apps to be deployed simultaneously.
We use the following prefixes for their names:
- dso: for the servers operated by the dso
- alice: for servers operated by alice acting as a self-hosted validator operator
- bob: for servers operated by bob acting as a self-hosted validator operator

Test Setup
==========

Most of our tests share the same long-running Canton instance,
see `./start-canton.sh` and `simple-topology-canton.conf`.
Test isolation is implemented by each test using unique Daml party names and leveraging Daml privacy features.

Most of our tests use the same definition of the Canton Network setup,
see `simple-topology.conf`.
To avoid port collisions, tests run sequentially, see `ConcurrentEnvironmentLimiter`.

Port Allocation
===============

Chosen ports should be between 4001 and 32767,
as the other ports tend to be either reserved or will be randomly chosen for client connections:
https://en.wikipedia.org/wiki/Ephemeral_port

## Manually allocated ports

Consequently, we use the following port allocation scheme when manually allocating static ports:
A port number has five digits and is of the form `<Network Index><Node Index><API Index>`, where:

- `<Network Index>` is between 5 and 31 and uniquely identifies a canton network instance.
- `<Node Index>` is between 0 and 9 and uniquely identifies the node within the network.
- `<API Index>` is between 0 and 99 and uniquely identifies an API exposed by the node.

**Exception:** Ports used by CometBFT instances are allocated using a dedicated scheme based on the CometBFT default ports.
All CometBFT ports we use (both locally and on clusters) are of the form `266<Node Index><API Index>`,
where `<Node Index>` defaults to 0 and `<API Index>` is one of `6` (P2P), `7` (RPC) and `0` (Prometheus metrics).

### Allocated Networks (Network Index)

To avoid collisions with our grpc-web proxy (which proxies ports `N` to `N+1000`, see below),
all network indices must be odd numbers.

- `5`: Default for integration tests
  - See `simple-topology.conf`, `simple-topology-canton.conf`.
- `9`: Local runbook integration test and participant identities dump test, `sv1Local`, `aliceValidatorLocal`
  - See `local-sv-node`, `local-validator-node`
- `11`: Ports forwarded from remote participant as part of `cncluster [participant|sequencer|mediator]_console`
- `15`: Simulated time
  - See `simple-topology.conf` with ports bumped by 10k, `simple-topology-canton-simtime.conf`
- `25`: Toxi-proxy ports
  - See `simple-topology.conf` with ports bumped by 20k (currently for the ledger API, but may be extended for other connections in the future)
- `26`: Reserved for CometBFT
- `27`: Standalone Canton instances launched in integration tests
- `28`: Canton instances used for soft domain migration tests

### Allocated Nodes (Node Index)

- `1`: SV1
- `2`: SV2
- `3`: SV3
- `4`: SV4
- `5`: Alice
- `6`: Bob
- `7`: Splitwell

### Allocated APIs (API Index)

- `00`: Canton, Prometheus metrics endpoint
- `01`: Participant, Ledger API
- `02`: Participant, Admin API
- `03`: Validator, Admin API
- `04`: Wallet, Admin API
- `05`: DSO, Admin API
- `06`: Domain manager, Admin API
- `07`: Mediator, Admin API
- `08`: Domain, Public API
- `09`: Domain, Admin API
- `11`: ANS user, Admin API
- `12`: Scan, Admin API
- `13`: Splitwell, Admin API
- `14`: SV, Public API + Admin API
- `15`: SV, Admin API (reserved)

### Examples

- `5301` is the Ledger API of Participant 3 in Canton Network 5.
- `17309` is the Admin API of Domain 3 in Canton Network 17.

## Verifying port allocation

Run `./scripts/print-config-summary.sh` to print the actual ports used by our main configuration files.

Cluster Deployment
==================

Note that the ports used in our cluster deployments are not identical to the ones used in testing.
For more details on the setup in the cluster, please refer to [the cluster README](../../../../../cluster/README.md).
