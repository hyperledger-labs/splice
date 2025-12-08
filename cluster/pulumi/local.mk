# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

include $(shell dirname $(dir))/deployment/local.mk

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

# TODO(#3237) Reattach resolved config targets to test and update-expected after resolving issues
#             with loading scratchnet configs in the internal repository.
.PHONY: $(dir)/test $(dir)/update-expected
$(dir)/test: $(dir)/unit-test $(foreach project,$(pulumi_projects),$(dir)/$(project)/test) # $(deployment_dir)/check-resolved-config 

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(foreach project,$(pulumi_projects),$(dir)/$(project)/update-expected) # $(deployment_dir)/update-resolved-config

include $(pulumi_projects:%=$(dir)/%/local.mk)
