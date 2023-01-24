dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/envoy.yaml $(dir)/docker-entrypoint.sh
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)
