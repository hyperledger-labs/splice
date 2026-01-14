# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/target/LICENSE
$(dir)/$(docker-build): build_arg := --build-arg KUBECTL_VERSION=v${KUBECTL_VERSION}
$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE
	cp $< $@
