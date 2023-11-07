dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/configure-state-sync.sh
$(dir)/$(docker-build): build_arg := --build-arg cometbft_version=${COMETBFT_RELEASE_VERSION}
