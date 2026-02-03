# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-bundle := $(dir)/target/splice-node.tar.gz
versioned-bundle := $(dir)/target/$(shell get-snapshot-version)_splice-node.tar.gz
app-bundle := ${SPLICE_ROOT}/apps/app/target/release/splice-node.tar.gz
versioned-openapi := $(dir)/target/$(shell get-snapshot-version)_openapi.tar.gz
target-logback := $(dir)/target/logback.xml

include ${SPLICE_ROOT}/cluster/images/common/entrypoint-image.mk

$(dir)/$(docker-build): $(dir)/target/entrypoint.sh $(dir)/target/LICENSE $(target-bundle) $(target-logback)

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE | $(dir)/target
	cp $< $@

$(target-bundle): $(app-bundle) | $(dir)/target
	cp $< $@

$(target-logback): ${SPLICE_ROOT}/scripts/canton-logback.xml | $(dir)/target
	cp $< $@
