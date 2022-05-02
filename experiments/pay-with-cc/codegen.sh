#!/usr/bin/env bash

CANTON_COIN=../../canton-coin

DAML_PROJECT=$CANTON_COIN daml build
daml codegen js $CANTON_COIN/.daml/dist/canton-coin-0.1.0.dar -o ccwallet/daml.js
daml codegen js $CANTON_COIN/.daml/dist/canton-coin-0.1.0.dar -o directoryservice/daml.js
