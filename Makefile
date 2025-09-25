# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# -*- makefile -*-

export MAKE

SHELL=/usr/bin/env bash

current_dir = $(shell dirname $(lastword $(MAKEFILE_LIST)))

app-bundle := ${SPLICE_ROOT}/apps/app/target/release/splice-node.tar.gz

load-tester := ${SPLICE_ROOT}/load-tester/dist

party-allocator := ${SPLICE_ROOT}/party-allocator/build/bundle.js

canton-amulet-dar := ${SPLICE_ROOT}/daml/splice-amulet/.daml/dist/splice-amulet-current.dar
wallet-payments-dar := ${SPLICE_ROOT}/daml/splice-wallet-payments/.daml/dist/splice-wallet-payments-current.dar

.PHONY: build
build: $(app-bundle) $(load-tester) cluster/build ## Build the Splice app bundle and ensure cluster scripts are ready to run.

$(app-bundle): $(canton-amulet-dar) $(wallet-payments-dar)
	sbt --batch bundle

$(canton-amulet-dar) $(wallet-payments-dar) &:
	sbt --batch 'splice-amulet-daml'/damlBuild 'splice-wallet-payments-daml'/damlBuild

$(load-tester):
	cd "${SPLICE_ROOT}/load-tester" && npm ci && npm run build

$(party-allocator):
	sbt --batch 'party-allocator/npmBuild'

.PHONY: update-expected
update-expected: cluster/pulumi/update-expected

.PHONY: pulumi-clean
pulumi-clean: cluster/pulumi/clean

.PHONY: pulumi-build
pulumi-build: cluster/pulumi/build

.PHONY: pulumi-format
pulumi-format: cluster/pulumi/format

.PHONY: clean
clean: cluster/clean
	rm -rf apps/app/target/release
	rm -rf $(load-tester)

.PHONY: clean-all
clean-all: clean ## Completely clean all local build state, including model codegen.
	sbt --batch clean-splice
	find . -type d -name ".daml" -exec rm -rf {} +
	find . -type d -name "target" -exec rm -rf {} +

.PHONY: format
format:	cluster/format ## Automatically reformat and apply scalaFix to source code
	sbt --batch formatFix

.PHONY: help
help:	## Show list of available make targets
	@LC_ALL=C $(MAKE) -pRrq -f Makefile : 2>/dev/null | awk -v RS= -F: '/(^|\n)# Files(\n|$$)/,/(^|\n)# Finished Make data base/ {if ($$1 !~ "^[#.]") {print $$1}}' | sort | egrep -v -e '^[^[:alnum:]]' -e '^$@$$'

include cluster/local.mk
