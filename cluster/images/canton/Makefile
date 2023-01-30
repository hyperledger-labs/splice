dir := $(call current_dir)

target-canton := $(dir)/target/canton
target-classpath := $(dir)/target/canton-classpath
auth-service := ${REPO_ROOT}/canton/community/participant/target/scala-2.13/classes/com/digitalasset/canton/participant/ledger/api/CantonAdminTokenAuthService.class

include ${REPO_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/empty.conf $(dir)/target/entrypoint.sh $(target-canton) $(target-classpath)

$(target-canton):
	rm -rf $@
	mkdir -p $@
	cp -R $$(dirname $$(dirname $$(which canton)))/. $@
	chmod -R +w $@
	sed -i -E 's|#!.*/bin/sh|#!/usr/bin/env sh|' $@/bin/canton

$(target-classpath): $(auth-service)
	rm -rf $@
	mkdir -p $@/com/digitalasset/canton/participant/ledger/api
	cp $< \
	  $@/com/digitalasset/canton/participant/ledger/api

