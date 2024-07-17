# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-canton := $(dir)/target/canton.tar
target-logback := $(dir)/target/logback.xml

include ${REPO_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/target/entrypoint.sh $(target-canton) $(target-logback)

$(target-canton):
	rm -f $@ ;\
	mkdir -p $(@D) ;\
	DIR=$$(mktemp -d) ;\
	cp -R $$(dirname $$(dirname $$(which canton)))/. $$DIR ;\
	chmod -R +w $$DIR ;\
	sed -i -E 's|#!.*/bin/sh|#!/usr/bin/env sh|' $$DIR/bin/canton ;\
    tar cf $@ -C $$DIR .

$(target-logback): ${REPO_ROOT}/scripts/canton-logback.xml
	cp $< $@
