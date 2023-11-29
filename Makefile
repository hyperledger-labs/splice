# -*- makefile -*-

export MAKE

SHELL=/usr/bin/env bash

current_dir = $(shell dirname $(lastword $(MAKEFILE_LIST)))

app-bundle := ${REPO_ROOT}/apps/app/target/release/cn-node-0.1.0-SNAPSHOT.tar.gz

canton-coin-dar := ${REPO_ROOT}/daml/canton-coin/.daml/dist/canton-coin-0.1.0.dar
wallet-payments-dar := ${REPO_ROOT}/daml/wallet-payments/.daml/dist/wallet-payments-0.1.0.dar

.PHONY: build
build: $(app-bundle) cluster/build ## Build the Canton Coin app bundle and ensure cluster scripts are ready to run.

$(app-bundle): $(canton-coin-dar) $(wallet-payments-dar)
	sbt --batch bundle

$(canton-coin-dar) $(wallet-payments-dar) &:
	sbt --batch canton-coin-daml/damlBuild wallet-payments-daml/damlBuild directory-daml/damlBuild

.PHONY: clean
clean: cluster/clean
	rm -rf apps/app/target/release

.PHONY: clean-all
clean-all: clean ## Completely clean all local build state, including model codegen.
	sbt --batch clean-cn

.PHONY: format
format:	cluster/format ## Automatically reformat and apply scalaFix to source code
	sbt --batch formatFix

.PHONY: help
help:	## Show list of available make targets
	@LC_ALL=C $(MAKE) -pRrq -f Makefile : 2>/dev/null | awk -v RS= -F: '/(^|\n)# Files(\n|$$)/,/(^|\n)# Finished Make data base/ {if ($$1 !~ "^[#.]") {print $$1}}' | sort | egrep -v -e '^[^[:alnum:]]' -e '^$@$$'

include cluster/local.mk
