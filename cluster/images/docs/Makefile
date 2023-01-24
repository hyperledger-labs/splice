dir := $(call current_dir)
html := $(dir)/target/html/index.html


$(dir)/$(docker-build): $(html)
$(dir)/$(docker-build): build_arg := --build-arg version=$(shell version-gen)

.PHONY: $(dir)/html
$(dir)/html: $(html)

docs-dir := $(dir)
$(html): $(shell find $(docs-dir)/src -type f)
	$(docs-dir)/gen-daml-docs.sh
	VERSION=$(shell version-gen) sphinx-build -M html $(docs-dir)/src $(docs-dir)/target -D version=$(shell version-gen) -W
