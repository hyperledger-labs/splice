dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/bootstrap.sc $(canton-image-build)
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

$(dir)/$(docker-push): $(canton-image-push)
