# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/configure-state-sync.sh $(dir)/target/LICENSE
$(dir)/$(docker-build): build_arg := --build-arg cometbft_version=${COMETBFT_RELEASE_VERSION}

$(dir)/target/LICENSE: LICENSE
	cp $< $@
