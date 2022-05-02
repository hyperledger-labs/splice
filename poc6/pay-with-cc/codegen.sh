#!/usr/bin/env bash

daml build
daml codegen js ../.daml/dist/canton-coin-0.1.0.dar -o ccwallet/daml.js
daml codegen js ../.daml/dist/canton-coin-0.1.0.dar -o directoryservice/daml.js
