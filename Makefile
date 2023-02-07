# -*- makefile -*-

export MAKE

SHELL=/usr/bin/env bash

current_dir = $(shell dirname $(lastword $(MAKEFILE_LIST)))

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
auth-service := ${REPO_ROOT}/canton/community/participant/target/scala-2.13/classes/com/digitalasset/canton/participant/ledger/api/CantonAdminTokenAuthService.class

.PHONY: build
build: $(app-bundle)	## Build the Canton Coin app bundle

$(app-bundle):
	sbt --batch bundle

$(auth-service):
	sbt --batch canton-community-participant/compile

.PHONY: clean
clean: images/clean
	rm -rf apps/app/target/release

.PHONY: clean-all
clean-all: clean	## Completely clean all local build state, including model codegen.
	sbt --batch clean-cn

.PHONY: format
format:	## Automatically reformat and apply scalaFix to source code
	sbt --batch formatFix

.PHONY: help
help:	## Show list of available make targets
	@LC_ALL=C $(MAKE) -pRrq -f Makefile : 2>/dev/null | awk -v RS= -F: '/(^|\n)# Files(\n|$$)/,/(^|\n)# Finished Make data base/ {if ($$1 !~ "^[#.]") {print $$1}}' | sort | egrep -v -e '^[^[:alnum:]]' -e '^$@$$'

include cluster/images/common.mk
include cluster/Makefile
