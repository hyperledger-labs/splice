dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(cn-image-build)
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

$(dir)/$(docker-push): $(cn-image-push)
