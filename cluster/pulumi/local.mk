# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

.PHONY: $(dir)/build
$(dir)/build: $(dir)/.build

$(dir)/.build: $(dir)/package.json
	cd $(@D) && ${SPLICE_ROOT}/build-tools/npm-install.sh
	touch $@

.PHONY: $(dir)/clean
$(dir)/clean:
	cd $(@D) && rm -rfv node_modules .build

.PHONY: $(dir)/format
$(dir)/format: $(dir)/.build
	cd $(@D) && npm run format:fix

.PHONY: $(dir)/unit-test
$(dir)/unit-test: $(dir)/.build
	cd $(@D) && npm run test

pulumi_projects ::= operator deployment gcp infra canton-network sv-runbook validator-runbook multi-validator cluster sv-canton validator1 splitwell

deployment_dir := $(shell dirname $(dir))/deployment

.PHONY: $(dir)/test $(dir)/update-expected
$(dir)/test: $(dir)/unit-test $(deployment_dir)/check-resolved-config $(foreach project,$(pulumi_projects),$(dir)/$(project)/test)

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(deployment_dir)/update-resolved-config $(foreach project,$(pulumi_projects),$(dir)/$(project)/update-expected)

include $(pulumi_projects:%=$(dir)/%/local.mk)
