dir := $(call current_dir)

$(dir)/$(docker-build): build_arg := --build-arg pulumi_version=${PULUMI_VERSION}
