# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/configs $(dir)/target/LICENSE
$(dir)/$(docker-build): build_arg := --build-arg cometbft_version=${COMETBFT_RELEASE_VERSION} --build-arg cometbft_sha=${COMETBFT_IMAGE_SHA256}

$(dir)/clean: $(dir)/clean-configs

$(dir)/clean-configs: $(dir)/configs
	rm -rfv $<

$(dir)/configs: ${SPLICE_ROOT}/apps/sv/src/test/resources/cometbft
	${SPLICE_ROOT}/cluster/images/splice-test-cometbft/copy-configs.sh $< $@

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE | $(dir)/target
	cp $< $@
