#!/usr/bin/env bash

cd "$(dirname "${BASH_SOURCE[0]}")"
cd ..
daml start --on-start 'daml script --dar=.daml/dist/canton-coin-0.1.0.dar --ledger-host=localhost --ledger-port=6865 --script-name CC.Scripts.WalletInit:init --static-time'
