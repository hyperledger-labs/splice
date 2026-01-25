# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

target-party-allocator := $(dir)/target/bundle.js
party-allocator := ${SPLICE_ROOT}/party-allocator/build/bundle.js

$(dir)/$(docker-build): $(target-party-allocator)  $(dir)/target/LICENSE  $(dir)/entrypoint.sh

$(target-party-allocator): $(party-allocator)
	mkdir -p $(@D)
	cp -r $< $@

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE
	cp $< $@
