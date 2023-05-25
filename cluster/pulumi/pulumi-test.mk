.PHONY: $(dir)/test.json
$(dir)/test.json: $(dir $(dir))install
	set -o pipefail \
	&& cd $(@D) \
	&& npm run --silent dump-config \
	   | jq --slurp --sort-keys $(JQ_FILTER) > $(@F)

.PHONY: $(dir)/diff-config
$(dir)/diff-config: $(dir)/test.json $(dir)/expected.json
	cd $(@D) && diff -u expected.json test.json

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(dir)/test.json
	@cp -v $^ $(@D)/expected.json
