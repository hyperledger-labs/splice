# When running locally, we want to run dump-config without any local environment variables,
# so we use env -i to unload everything except PATH and HOME,
# and then we direnv exec to restore the repo's environment variables before running dump-config.
# This doesn't translate well to CI, but that's okay because CI doesn't change variables anyway.
.PHONY: $(dir)/test.json
$(dir)/test.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D); \
	if [ -n "$$CI" ]; then \
		npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	else \
		env -i PATH=$$PATH HOME=$$HOME IGNORE_PRIVATE_ENVRC=1 direnv exec . npm run --silent dump-config | jq --slurp --sort-keys $(JQ_FILTER) > $(@F); \
	fi

.PHONY: $(dir)/test
$(dir)/test: $(dir)/test-config $(dir)/lint

.PHONY: $(dir)/lint
$(dir)/lint:
	set -o pipefail \
	&& cd $(@D) \
	&& npm run lint:check

.PHONY: $(dir)/test-config
$(dir)/test-config: $(dir)/test.json $(dir)/expected.json
	diff -u $(@D)/expected.json $(@D)/test.json; \
	EXIT=$$?; \
	cp $(@D)/test.json $(@D)/expected.json; \
	exit $$EXIT

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(dir)/test.json
	@cp -v $^ $(@D)/expected.json
