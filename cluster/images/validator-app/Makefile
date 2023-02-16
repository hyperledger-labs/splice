dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/bootstrap.sc $(dir)/pre-bootstrap.sh
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell version-gen)

include cluster/images/cn-base-image-dep.mk
