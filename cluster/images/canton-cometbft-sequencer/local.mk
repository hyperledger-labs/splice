# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-driver := $(dir)/target/driver.jar

$(dir)/$(docker-build): $(target-driver)
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell get-snapshot-version)

$(target-driver): ${COMETBFT_DRIVER}/driver.jar
	cp $< $@

include cluster/images/canton-sequencer-base-image-dep.mk
