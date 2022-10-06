#!/usr/bin/env bash

if [ -z "${CI}" ]; then
    npm install
else
    npm ci
fi
