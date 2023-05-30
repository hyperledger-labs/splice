dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell get-docker-image-tag)

include cluster/images/canton-base-image-dep.mk
