IMPORTANT NOTE: copied and slightly adapted from the respective file from Canton's enterprise repo

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
- `<Node Index>` is between 00 and 99 (always two digits) and uniquely identifies the node within the test suite.
- `<Kind>` is one of the following:
  - `1`: Participant, Ledger API
  - `2`: Participant, Admin API
  - `3`: Validator, Admin API
  - `4`: Wallet, Admin API
  - `5`: SVC, Admin API 
  - `6`: Domain manager, Admin API
  - `7`: Mediator, Admin API
  - `8`: Domain, Public API
  - `9`: Domain, Admin API

Example:
- `5031` is the Ledger API of Participant 3 in Test Suite 5.
- `17239` is the Admin API of Domain 23 in Test Suite 17. 

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
