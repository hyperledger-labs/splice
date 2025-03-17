# token-standard-wallet-cli

This directory includes a CLI tool that can be used to interact with APIs that you would usually expect a wallet UI to interact with.
It is both useful as a tool for app developers for testing as well as for wallet developers as a reference for what they need to implement.

## Usage

Note the `--`. Otherwise parameters are not passed to the CLI command and that yields confusing errors.

### List holdings

```
npm run cli -- list-holdings <partyId> -l <ledger json api> -a <auth token>
```
