# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# When running locally, we want to run dump-config without any local environment variables,
# so we use env -i to unload everything except PATH, HOME and the envs required for our code to load the cluster config directly.
# In CI we load the env variables for the cluster directly, thus ensuring that the code loaded configuration and the env loaded config is always the same.

.PHONY: $(dir)/test-devnet.json
$(dir)/test-devnet.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
	    . "${REPO_ROOT}/cluster/deployment/devnet/.envrc.vars"; \
		npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	else \
		env -i PATH="$$PATH" HOME="$$HOME" REPO_ROOT="$$REPO_ROOT" GCP_CLUSTER_BASENAME="dev" CN_PULUMI_LOAD_ENV_CONFIG_FILE="true" npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	fi

.PHONY: $(dir)/test-testnet.json
$(dir)/test-testnet.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
	    . "${REPO_ROOT}/cluster/deployment/testnet/.envrc.vars"; \
    	npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
    else \
        env -i PATH="$$PATH" HOME="$$HOME" REPO_ROOT="$$REPO_ROOT" GCP_CLUSTER_BASENAME="testzrh" CN_PULUMI_LOAD_ENV_CONFIG_FILE="true" npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
    fi

.PHONY: $(dir)/test-mainnet.json
$(dir)/test-mainnet.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
	    . "${REPO_ROOT}/cluster/deployment/mainnet/.envrc.vars"; \
    	npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
    else \
        env -i PATH="$$PATH" HOME="$$HOME" REPO_ROOT="$$REPO_ROOT" GCP_CLUSTER_BASENAME="main" CN_PULUMI_LOAD_ENV_CONFIG_FILE="true" npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
    fi

.PHONY: $(dir)/test
$(dir)/test: $(dir)/test-devnet $(dir)/test-testnet $(dir)/test-mainnet

.PHONY: $(dir)/test-devnet
$(dir)/test-devnet: $(dir)/test-config-devnet $(dir)/lint

.PHONY: $(dir)/test-testnet
$(dir)/test-testnet: $(dir)/test-config-testnet $(dir)/lint

.PHONY: $(dir)/test-mainnet
$(dir)/test-mainnet: $(dir)/test-config-mainnet $(dir)/lint


.PHONY: $(dir)/lint
$(dir)/lint:
	set -o pipefail \
	&& cd $(@D) \
	&& npm run lint:check

.PHONY: $(dir)/test-config-devnet
$(dir)/test-config-devnet: $(dir)/test-devnet.json $(dir)/expected-devnet.json
	diff -u $(@D)/expected-devnet.json $(@D)/test-devnet.json; \
	EXIT=$$?; \
	cp $(@D)/test-devnet.json $(@D)/expected-devnet.json; \
	exit $$EXIT

.PHONY: $(dir)/test-config-testnet
$(dir)/test-config-testnet: $(dir)/test-testnet.json $(dir)/expected-testnet.json
	diff -u $(@D)/expected-testnet.json $(@D)/test-testnet.json; \
	EXIT=$$?; \
	cp $(@D)/test-testnet.json $(@D)/expected-testnet.json; \
	exit $$EXIT

.PHONY: $(dir)/test-config-mainnet
$(dir)/test-config-mainnet: $(dir)/test-mainnet.json $(dir)/expected-mainnet.json
	diff -u $(@D)/expected-mainnet.json $(@D)/test-mainnet.json; \
	EXIT=$$?; \
	cp $(@D)/test-mainnet.json $(@D)/expected-mainnet.json; \
	exit $$EXIT


.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(dir)/update-expected-devnet $(dir)/update-expected-testnet $(dir)/update-expected-mainnet

.PHONY: $(dir)/update-expected-devnet
$(dir)/update-expected-devnet: $(dir)/test-devnet.json
	@cp -v $^ $(@D)/expected-devnet.json

.PHONY: $(dir)/update-expected-testnet
$(dir)/update-expected-testnet: $(dir)/test-testnet.json
	@cp -v $^ $(@D)/expected-testnet.json

.PHONY: $(dir)/update-expected-mainnet
$(dir)/update-expected-mainnet: $(dir)/test-mainnet.json
	@cp -v $^ $(@D)/expected-mainnet.json
