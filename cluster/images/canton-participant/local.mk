# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-logback := $(dir)/target/logback.xml

$(dir)/$(docker-build): build_arg := --build-arg canton_version=${CANTON_VERSION} --build-arg image_sha256=${CANTON_PARTICIPANT_IMAGE_SHA256}
$(dir)/$(docker-build): $(dir)/additional-config.conf $(dir)/R__repeatable-migration.sql
$(dir)/$(docker-build): $(target-logback)

$(target-logback): ${SPLICE_ROOT}/scripts/canton-logback.xml | $(dir)/target
	cp $< $@
