# Proposals for Splice Token Standards

## What you see

This is a proposal for a set of RFCs (Request for Comments) that we would like
to publish via the GSF (Global Synchronizer Foundation, see
https://sync.global/) to foster the development of a set of standards that allow
asset registry apps, wallet apps, and trading apps to compose uniformly, but evolve
independently.

We recommend to review this code as follows:

1. Watch this [presentation](https://docs.google.com/presentation/d/1PNyGMfILA-AKaTRsBP82kavFWG2W_8JNVgOaIKVsgq4) to get an overview
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

You can use the [localnet
instructions](https://docs.token-std-dev.global.canton.network.digitalasset.com/app_dev/testing/localnet.html)
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

## CLI

We provide a Command Line Interface (CLI) tool that can be used to interact with APIs that you would usually expect a wallet UI to interact with.
It is both useful as a tool for app developers for testing as well as for wallet developers as a reference for what they need to implement.
The CLI is available at `token-standard/cli`.

### Install dependencies

First of all, make sure to [setup your development environment for the project](../README.md#setting-up-your-development-environment).
Dependencies can be installed by running `sbt token-standard-cli/compile` in the root of the project.
This will download all the necessary dependencies and compile openAPI bindings used by the CLI.

### Run

All commands can be run with `npm run cli -- <command> <options...>` in the `token-standard` directory.
They all provide a `--help` option to figure out the required options.
The commands are:

#### List holdings

```
> npm run cli -- list-holdings

Usage: main list-holdings [options] <partyId>

List the holdings of a party

Arguments:
  partyId                   The party for which to list the holdings

Options:
  -l, --ledger-url <value>  The ledger JSON API base URL, e.g. http://localhost:6201
  -a, --auth-token <value>  The ledger JSON API auth token
  -h, --help                display help for command
```

Example:

```shell
npm run cli -- list-holdings a::partyid -l http://localhost:6201 -a an-auth-token
```

Output:

You can find an example output in [`token-standard/cli/__tests__/expected/holdings.json`](cli/__tests__/expected/holdings.json).

The output is a JSON array of holdings, each of which has two relevant fields:
- `contractId`: The contract id of the holding
- `payload`: An object containing the same fields as the [`HoldingView` Daml interface](splice-api-token-holding-v1/daml/Splice/Api/Token/HoldingV1.daml#L49).

#### List holding transactions

```
npm run cli -- list-holding-txs

Usage: main list-holding-txs [options] <partyId>

List transactions where a party is involved exercising Holding contracts

Arguments:
  partyId                      The party for which to list the transactions

Options:
  -o --after-offset <value>    Get transactions after this offset (exclusive).
  -d --debug-path <value>      Writes the original server response to this path for debugging
                               purposes.
  -s --strict                  Fail if any creates/archives without a known parent would be
                               returned. (default: false)
  --strict-ignore <values...>  In strict mode only: ignore raw creates / exercises of the given
                               entity names. Space-separated.
  -l, --ledger-url <value>     The ledger JSON API base URL, e.g. http://localhost:6201
  -a, --auth-token <value>     The ledger JSON API auth token
  -h, --help                   display help for command
```

The option `--strict` is useful for implementors of the token standard to verify that the CLI (and any similar implementations)
are capable of correctly parsing the transaction trees without yielding creates or archives that haven't been assigned
to a known choice. For example, that two creates are part of a Transfer as opposed to freestanding in the output.

Pagination can be done using the `--after-offset` option.
The `nextOffset` returned by the command can be used to call the command again until there are no more transactions returned.

Example:

```shell
npm run cli -- list-holding-txs a::partyid -l http://localhost:6201 -a an-auth-token
```

Output:

You can find an example output in [`token-standard/cli/__tests__/expected/txs.json`](cli/__tests__/expected/txs.json).

The output is a JSON object containing two fields:
- `nextOffset`: The offset to be used for the next call to the command for pagination.
- `transactions`: An array of transactions, where:
  - `events` is an array of all the events that happened in the transaction, parsed under a known token-standard operation.
    The field `label.type` is a string that defines what kind of operation the event represents.
    For example, `label.type="TransferOut"` indicates a transaction from the provided party to another party.
    You can find all possible outputs in the [type definition](cli/src/txparse/types.ts).

#### Execute transfer

``` 
npm run cli -- transfer --help

Usage: main transfer [options]

Send a transfer of holdings

Options:
  -s, --sender <value>                        The sender party of holdings
  -r --receiver <value>                       The receiver party of the holdings
  --amount <value>                            The amount to be transferred
  -e --instrument-admin <value>               The expected admin of the instrument.
  -d --instrument-id <value>                  The instrument id of the holding, e.g. "Amulet"
  --public-key <value>                        Path to the public key file
  --private-key <value>                       Path to the private key file
  -R --transfer-factory-registry-url <value>  The URL to a transfer registry.
  -u, --user-id <value>                       The user id, must match the user in the token
  -l, --ledger-url <value>                    The ledger JSON API base URL, e.g.
                                              http://localhost:6201
  -a, --auth-token <value>                    The ledger JSON API auth token
  -h, --help                                  display help for command
```

Example:

```shell
npm run cli -- transfer \
  -s a::senderparty \
  -r a::receiverparty \
  --amount 0.1 \
  -e admin::partyid \
  -d TheInstrumentId \
  --public-key sender-key.pub \
  --private-key sender-key.priv \
  -R http://localhost:5012 \
  -l http://localhost:6201 \
  -a an-auth-token
```

Output:

If the output is `{}`, the transfer was successful.
TODO (#18610): record_time and update_id will be added to the output.