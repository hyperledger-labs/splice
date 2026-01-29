# Splice Token APIs

## What you see

This is a set of APIs for [CIP-0056 - Canton Network Token Standard](https://github.com/global-synchronizer-foundation/cips/blob/main/cip-0056/cip-0056.md).
We recommend to read their definitions jointly with the text of the CIP itself.

As for documentation, we currently have to refer you to the source code of the
Daml files defining the relevant Daml interfaces, and the source code of the
OpenAPI specifications for the off-ledger APIs.

Note that the fine-tuning of the API definitions happened after proposing
CIP-0056.  See the [CHANGELOG](CHANGELOG.md) for the changes that were done
between proposing CIP-0056 and releasing the APIs together with their `Amulet`
implementation.


## Testing

### Testing Daml Apps

The Daml files in `splice-token-standard-test` provide a test harness for
both building apps on top of the allocation interfaces and for building
token registries implementing the interfaces. Take a copy of these files
and modify them to your liking for testing your app.

### Local Testing

Amulet implements the token standard so can be used for testing token
standard APIs if you do not have access to another token.

You can use the [localnet
instructions](https://docs.dev.sync.global/app_dev/testing/localnet.html)
to spin up a local network to test your application against.

### Testing on Dev/Test/Mainnet.

Alternatively, you can also test against DevNet, TestNet or
MainNet. MainNet will only work after July 8th after the new Daml
models are effective.

## CLI

We provide a Command Line Interface (CLI) tool that can be used to interact with APIs that you would usually expect a wallet UI to interact with.
It is both useful as a tool for app developers for testing as well as for wallet developers as a reference for what they need to implement.
The CLI is available at `token-standard/cli`.

### Install dependencies

First of all, make sure to [setup your development environment for the project](../DEVELOPMENT.md).
Dependencies can be installed by running `sbt token-standard-cli/compile` in the root of the project.
This will download all the necessary dependencies and compile openAPI bindings used by the CLI.

### Run

All commands can be run with `npm run cli -- <command> <options...>` in the `cli` directory.
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


#### List transfer instructions

```
> npm run cli -- list-transfer-instructions

Usage: main list-transfer-instructions [options] <partyId>

List all transfer instructions where the provided party is a stakeholder of

Arguments:
  partyId                   The party for which to list the transfer
                            instructions

Options:
  -l, --ledger-url <value>  The ledger JSON API base URL, e.g.
                            http://localhost:6201
  -a, --auth-token <value>  The ledger JSON API auth token
  -h, --help                display help for command
```

Example:

```shell
npm run cli -- list-transfer-instructions a::partyid -l http://localhost:6201 -a an-auth-token
```

Output:

You can find an example output in [`token-standard/cli/__tests__/expected/transfer-instructions.json`](cli/__tests__/expected/transfer-instructions.json).

The output is a JSON array of transfer instructions, each of which has two relevant fields:
- `contractId`: The contract id of the transfer instruction
- `payload`: An object containing the same fields as the [`TransferInstruction` Daml interface](splice-api-token-transfer-instruction-v1/daml/Splice/Api/Token/TransferInstructionV1.daml#L115).

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
  -u a-user-id \
  -R http://localhost:5012 \
  -l http://localhost:6201 \
  -a an-auth-token
```

Output:

The output will include ` "status": "success" `, when the transfer was successful.
Additionally, it will also include:
- `updateId`: the update id identifying the transfer in the ledger.
- `recordTime`: the time in the ledger at which the transfer happened.
- `synchronizerId`: the synchronizer id in which the transfer was executed.

Any error will be logged with  "Failed to execute transfer:", followed by error details.
These can happen due to any intermediate request failing, or timing out.


#### Accept transfer instruction

```
npm run cli -- accept-transfer-instruction --help

Usage: main accept-transfer-instruction [options] <transferInstructionCid>

Execute the choice TransferInstruction_Accept on the provided transfer instruction

Arguments:
  transferInstructionCid                      The contract ID of the transfer instruction to accept

Options:
  -p, --party <value>                         The party as which to accept the transfer instruction. Must be usable by the auth token's user.
  -u, --user-id <value>                       The user id, must match the user in the token
  --public-key <value>                        Path to the public key file
  --private-key <value>                       Path to the private key file
  -R --transfer-factory-registry-url <value>  The URL to a transfer registry.
  -l, --ledger-url <value>                    The ledger JSON API base URL, e.g. http://localhost:6201
  -a, --auth-token <value>                    The ledger JSON API auth token
  -h, --help                                  display help for command
```

Example:

```shell
npm run cli -- accept-transfer-instruction \
  aTransferInstructionCid \
  -p acting::party \
  --public-key sender-key.pub \
  --private-key sender-key.priv \
  -R http://localhost:5012 \
  -l http://localhost:6201 \
  -u a-user-id \
  -a an-auth-token
```

You can list all transfer instructions, including their contract id,
from the output of the `list-transfer-instructions` command.

Output:

The output will include ` "status": "success" `, when the instruction acceptance  was successful.
Additionally, it will also include:
- `updateId`: the update id identifying the instruction acceptance in the ledger.
- `recordTime`: the time in the ledger at which the instruction acceptance happened.
- `synchronizerId`: the synchronizer id in which the instruction acceptance was executed.

Any error will be logged with  "Failed to accept transfer instruction:", followed by error details.
These can happen due to any intermediate request failing, or timing out.
