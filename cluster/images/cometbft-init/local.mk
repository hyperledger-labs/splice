dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/configure-state-sync.sh
