.PHONY: $(dir)/test.json
$(dir)/test.json: $(dir $(dir)).build
	set -o pipefail \
	&& cd $(@D) \
	&& npm run --silent dump-config \
	   | jq --slurp --sort-keys $(JQ_FILTER) > $(@F)

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
