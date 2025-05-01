# Splice Token APIs

## What you see

This is a set of APIs for [CIP-0056 - Canton Network Token Standard](https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md).
We recommend to read their definitions jointly with the text of the CIP itself.

As for documentation, we currently have to refer you to the source code of the
Daml files defining the relevant Daml interfaces, and the source code of the
OpenAPI specifications for the off-ledger APIs.

Note that the `splice-api-token-burn-mint` API is not explained in CIP-0056. It was added
later due to discovering the need for it when considering how to build a bridge
leveraging an existing token implementation. See the [CHANGELOG](CHANGELOG.md)
for  a description of that change, and other major and minor changes that were done between
proposing CIP-0056 and releasing the APIs together with their `Amulet` implementation.


## Testing

### Testing Daml Apps

The Daml files in `splice-token-standard-test` provide a test harness for
both building apps on top of the allocation interfaces and for building
token registries implementing the interfaces. Take a copy of these files
and modify them to your liking for testing your app.

### Local Testing

There is not yet a stable release containing the token
standard. However, documentation is published at
https://docs.token-std-dev.global.canton.network.digitalasset.com/ updated
weekly and you can see the latest snapshot version at the top.

Amulet implements the token standard so can be used for testing token
standard APIs if you do not have access to another token.

You can use the [localnet
instructions](https://docs.token-std-dev.global.canton.network.digitalasset.com/app_dev/testing/localnet.html)
to spin up a local network to test your application against.

### Testing on token-std-devnet

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
