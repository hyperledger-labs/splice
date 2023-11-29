dir := $(call current_dir)
html := $(dir)/target/html/index.html


$(dir)/$(docker-build): $(html)
$(dir)/$(docker-build): build_arg := --build-arg version=$(shell get-snapshot-version)

.PHONY: $(dir)/html
$(dir)/html: $(html)

docs-dir := $(dir)
$(html): $(shell find $(docs-dir)/src -type f) $(canton-coin-dar) $(wallet-payments-dar)
	SKIP_DAML_BUILD=1 $(docs-dir)/gen-daml-docs.sh
	VERSION=$(shell get-snapshot-version) CHART_VERSION=$(shell get-snapshot-version) sphinx-build -M html $(docs-dir)/src $(docs-dir)/target -D version=$(shell get-snapshot-version) -W
