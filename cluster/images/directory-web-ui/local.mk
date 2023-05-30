dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/config.js
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell get-docker-image-tag)

include cluster/images/cn-base-image-dep.mk
