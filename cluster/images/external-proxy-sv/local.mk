dir := $(call current_dir)

$(dir)/$(docker-build): $(shell find $(dir) -name '*.conf' -type f)
