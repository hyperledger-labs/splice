# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): $(dir)/app.conf $(dir)/pre-bootstrap.sh
$(dir)/$(docker-build): build_arg := --build-arg base_version=$(shell get-snapshot-version)

include cluster/images/splice-base-image-dep.mk
