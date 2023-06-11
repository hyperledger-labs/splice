dir := $(call current_dir)

$(dir)/$(docker-build): build_arg := --build-arg cometbft_version=${COMETBFT_DOCKER_IMAGE_VERSION}
