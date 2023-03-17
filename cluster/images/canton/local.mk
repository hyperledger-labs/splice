dir := $(call current_dir)

target-canton := $(dir)/target/canton
target-classpath := $(dir)/target/canton-classpath

include ${REPO_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/empty.conf $(dir)/target/entrypoint.sh $(target-canton)

$(target-canton):
	rm -rf $@
	mkdir -p $@
	cp -R $$(dirname $$(dirname $$(which canton)))/. $@
	chmod -R +w $@
	sed -i -E 's|#!.*/bin/sh|#!/usr/bin/env sh|' $@/bin/canton
