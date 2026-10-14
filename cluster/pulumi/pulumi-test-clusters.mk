# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# dump-config writes every resource to its own file under test-output.
# Type checking is skipped as it dominates memory usage and is already done by `sbt lint`
.PHONY: $(dir)/test-output
$(dir)/test-output: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
	    . "${SPLICE_ROOT}/cluster/deployment/mock/.envrc.vars"; \
		DUMP_CONFIG_OUTPUT_DIR="$$PWD/$(@F)" TS_NODE_TRANSPILE_ONLY=true npm run --silent dump-config; \
	else \
		env -i PATH="$$PATH" HOME="$$HOME" SPLICE_ROOT="$$SPLICE_ROOT" GCP_CLUSTER_BASENAME="mock" CN_PULUMI_LOAD_ENV_CONFIG_FILE="true" DEPLOYMENT_DIR="$$DEPLOYMENT_DIR" PRIVATE_CONFIGS_PATH="$$PRIVATE_CONFIGS_PATH" PUBLIC_CONFIGS_PATH="$$PUBLIC_CONFIGS_PATH" DUMP_CONFIG_OUTPUT_DIR="$$PWD/$(@F)" TS_NODE_TRANSPILE_ONLY=true npm run --silent dump-config; \
	fi

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(dir)/test-output
	rm -rf $(EXPECTED_FILES_DIR)/$(notdir $(@D))
	cp -r $^ $(EXPECTED_FILES_DIR)/$(notdir $(@D))

.PHONY: $(dir)/test-config
$(dir)/test-config: $(dir)/test-output
	diff -ru $(EXPECTED_FILES_DIR)/$(notdir $(@D)) $^; \
	EXIT=$$?; \
	rm -rf $(EXPECTED_FILES_DIR)/$(notdir $(@D)); \
	cp -r $^ $(EXPECTED_FILES_DIR)/$(notdir $(@D)); \
	exit $$EXIT

.PHONY: $(dir)/lint
$(dir)/lint:
	set -o pipefail \
	&& cd $(@D) \
	&& npm run lint:check

.PHONY: $(dir)/test
$(dir)/test: $(dir)/test-config $(dir)/lint
