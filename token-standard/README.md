# Proposals for Splice Token Standards

## What you see

This is a proposal for a set of RFCs (Request for Comments) that we would like
to publish via the GSF (Global Synchronizer Foundation, see
https://sync.global/) to foster the development of a set of standards that allow
asset registry apps, wallet apps, and trading apps to compose uniformly, but evolve
independently.

We recommend to review this code as follows:

1. Watch this [presentation](https://docs.google.com/presentation/d/1ffBf2uv3jfeupvrvLUwIFt2bjcIPzmpVNvt8eibPlSE/edit#slide=id.g2722663396b_0_1589) to get an overview
of the why and what.
2. Review the code and its tests in the directories of the form `splice-api-token-*`
jointly with the examples in `examples/`. Examples of particular interest are:

    - [`examples/splice-token-amulet-test/daml/Splice/Scripts/TestAmuletTokenTransfer.daml`](examples/splice-token-amulet-test/daml/Splice/Scripts/TestAmuletTokenTransfer.daml)
    - [`examples/splice-token-amulet-test/daml/Splice/Scripts/TestAmuletTokenDvP.daml`](examples/splice-token-amulet-test/daml/Splice/Scripts/TestAmuletTokenDvP.daml)

## Testing


### local testing

There is not yet a stable release containing the token
standard. However, documentation is published at
https://docs.token-std-dev.global.canton.network.digitalasset.com/ updated
weekly and you can see the latest snapshot version at the top.

Amulet implements the token standard so can be used for testing token
standard APIs if you do not have access to another token.

You can use the [localnet instructions](https://docs.token-std-dev.global.canton.network.digitalasset.com/app_dev/testing/localnet.html)
to spin up a local network to test your application against.

### token-std-devnet

The token standard is not yet available on dev, test or mainnet. However, there is a dedicated cluster
operated by Digital Asset under https://token-std-dev.global.canton.network.digitalasset.com/.

You can see the snapshot version deployed there at the top of the page.

To connect a validator, follow the normal validator instructions and use the following parameters:

| name | value |
|-|-|
| MIGRATION_ID | 0 |
| SPONSOR_SV_URL | https://sv.sv-2.token-std-dev.global.canton.network.digitalasset.com/ |
| TRUSTED_SCAN_URL| https://scan.sv-2.token-std-dev.global.canton.network.digitalasset.com/ |

`token-std-devnet` runs in devnet configuration so you can request validator secrets yourself.

The approved IP ranges match devnet. If you need access from an
additional IP reach out to Digital Asset directly.

This cluster will be reset every Monday with announcements sent in
slack. You must reset your node fully, i.e., drop all databases and
update to the new version mentioned in the
[docs](https://docs.token-std-dev.global.canton.network.digitalasset.com/). No
data is preserved across these resets.

Once DevNet upgrades to a version including the token standard this cluster will be retired in favor of using DevNet or TestNet for testing.
