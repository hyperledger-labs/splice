#!/usr/bin/env bash

cd "$(dirname "${BASH_SOURCE[0]}")"
daml trigger --dar=.daml/dist/pay-with-cc-0.1.0.dar --ledger-host=localhost --ledger-port=6865 --ledger-user provider --trigger-name ProviderTrigger:trigger --dev-mode-unsafe
