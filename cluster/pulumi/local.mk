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

pulumi_projects ::= operator deployment gcp infra canton-network sv-runbook validator-runbook multi-validator cluster sv-canton validator1 splitwell

.PHONY: $(dir)/test $(dir)/update-expected
$(dir)/test: $(foreach project,$(pulumi_projects),$(dir)/$(project)/test)

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(foreach project,$(pulumi_projects),$(dir)/$(project)/update-expected)

include $(pulumi_projects:%=$(dir)/%/local.mk)
