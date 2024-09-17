# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-bundle := $(dir)/target/splice-node.tar.gz
app-bundle := ${REPO_ROOT}/apps/app/target/release/splice-node.tar.gz

include ${REPO_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/target/entrypoint.sh $(dir)/target/LICENSE $(target-bundle)

$(dir)/target/LICENSE: LICENSE
	cp $< $@

$(target-bundle): $(app-bundle)
	mkdir -p $(@D)
	cp $< $@
