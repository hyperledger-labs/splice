# -*- makefile -*-

export MAKE

SHELL=/usr/bin/env bash

current_dir = $(shell dirname $(lastword $(MAKEFILE_LIST)))

app-bundle := ${REPO_ROOT}/apps/app/target/release/coin-0.1.0-SNAPSHOT.tar.gz
auth-service := ${REPO_ROOT}/canton/community/participant/target/scala-2.13/classes/com/digitalasset/canton/participant/ledger/api/CantonAdminTokenAuthService.class

.PHONY: build
build: $(app-bundle)	## Build the Canton Coin app bundle

$(app-bundle):
	sbt bundle

$(auth-service):
	sbt canton-community-participant/compile

.PHONY: clean
clean: images/clean
	rm -rf apps/app/target/release

.PHONY: clean-all
clean-all: clean	## Completely clean all local build state, including model codegen.
	sbt clean-cn

.PHONY: format
format:	## Automatically reformat and apply scalaFix to source code
	sbt formatFix

.PHONY: help
help:	## Show list of available make targets
	@cat Makefile | grep -e "^[a-zA-Z_\-]*: *.*## *" | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-30s\033[0m %s\n", $$1, $$2}'

include cluster/images/common.mk
include cluster/Makefile
