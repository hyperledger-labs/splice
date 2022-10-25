IMPORTANT NOTE: copied and slightly adapted from the respective file from Canton's enterprise repo

Name Allocation
===============

Our deployment topologies require many servers representing nodes and apps to be deployed simultaneously.
We use the following prefixes for their names:
- svc: for the servers operated by the svc
- directory: for the servers operated by the directory provider
- alice: for servers operated by alice acting as a self-hosted validator operator
- bob: for servers operated by bob acting as a self-hosted validator operator

Port Allocation
===============

As different tests run in parallel, every test suite needs unique ports.

The chosen ports should be between 4001 and 32767, 
as the other ports tend to be either reserved or will be randomly chosen for client connections: 
https://en.wikipedia.org/wiki/Ephemeral_port

## Manually allocated ports

Consequently, we use the following port allocation scheme when manually allocating static ports:
A port number has five digits and is of the form `<Test Index><Node Index><Kind>`, where:

- `<Test Index>` is between 5 and 31 and uniquely identifies the test suite.
- `<Node Index>` is between 0 and 9 and uniquely identifies the node within the test suite.
- `<Kind>` is one of the following:
  - `01`: Participant, Ledger API
  - `02`: Participant, Admin API
  - `03`: Validator, Admin API
  - `04`: Wallet, Admin API
  - `05`: SVC, Admin API
  - `06`: Domain manager, Admin API
  - `07`: Mediator, Admin API
  - `08`: Domain, Public API
  - `09`: Domain, Admin API
  - `10`: Directory provider, Admin API
  - `11`: Directory user, Admin API
  - `12`: Scan, Admin API
  - `13`: Splitwise, Admin API

Example:
- `5301` is the Ledger API of Participant 3 in Test Suite 5.
- `17309` is the Admin API of Domain 3 in Test Suite 17.

At time of writing, the following test indices have been used by integration tests running on CI:
- 4: -
- 5: `simple-topology.conf` (not yet run on CI, but likely will soon)
- 6: -
- 7: -
- 8: -
- 9: -
- 10: -
- 11: -
- 12: -
- 13: -
- 14: -
- 15+: dynamically allocated ports (typically from integration tests deriving from BaseIntegrationTest)


## Dynamically allocated ports

For tests that do not need a known static port, the `CoinConfigTransforms.globallyUniquePorts` configuration transform
can be used to allocate a unique port within a test run (this is done by default).

## Envoy grpc-web proxy

We use an envoy proxy to allow web applications to communicate with gRPC services using gRPC-web.
A gRPC service running on port `N` is proxied to a gRPC-web server running on port `N + 1000`.
