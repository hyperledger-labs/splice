# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): build_arg := --build-arg runner_version=${GHA_RUNNER_VERSION} --build-arg image_sha256=${GHA_RUNNER_DIGEST}
$(dir)/$(docker-build): $(dir)/target/LICENSE


$(dir)/target/LICENSE: ${SPLICE_ROOT}/LICENSE | $(dir)/target
	cp $< $@
