# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/target/LICENSE $(target-dir)

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE $(target-dir)
	cp $< $@

$(target-dir):
	mkdir -p $(@D)
