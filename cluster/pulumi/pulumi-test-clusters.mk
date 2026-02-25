# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

.PHONY: $(dir)/test.json
$(dir)/test.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
	    . "${SPLICE_ROOT}/cluster/deployment/mock/.envrc.vars"; \
		npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	else \
		env -i PATH="$$PATH" HOME="$$HOME" SPLICE_ROOT="$$SPLICE_ROOT" GCP_CLUSTER_BASENAME="mock" CN_PULUMI_LOAD_ENV_CONFIG_FILE="true" DEPLOYMENT_DIR="$$DEPLOYMENT_DIR" PRIVATE_CONFIGS_PATH="$$PRIVATE_CONFIGS_PATH" PUBLIC_CONFIGS_PATH="$$PUBLIC_CONFIGS_PATH" npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	fi

cluster/expected/$(notdir $(dir)):
	mkdir -p $@

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(dir)/test.json | cluster/expected/$(notdir $(dir))
	@cp -v $^ $(EXPECTED_FILES_DIR)/$(notdir $(@D))/expected.json

.PHONY: $(dir)/test-config
$(dir)/test-config: $(dir)/test.json | cluster/expected/$(notdir $(dir))
	diff -u $(EXPECTED_FILES_DIR)/$(notdir $(@D))/expected.json $(@D)/test.json; \
	EXIT=$$?; \
	cp $(@D)/test.json $(EXPECTED_FILES_DIR)/$(notdir $(@D))/expected.json; \
	exit $$EXIT

.PHONY: $(dir)/lint
$(dir)/lint:
	set -o pipefail \
	&& cd $(@D) \
	&& npm run lint:check

.PHONY: $(dir)/test
$(dir)/test: $(dir)/test-config $(dir)/lint
