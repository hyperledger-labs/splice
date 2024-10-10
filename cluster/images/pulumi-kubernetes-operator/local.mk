# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

$(dir)/$(docker-build): platform_opt := --platform=linux/amd64 --build-arg pulumi_version=${PULUMI_VERSION}
