# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

rpc-script := $(dir)/target/gha-runner-rpc.py
rpc-source := ${SPLICE_ROOT}/.github/runners/runner-container-hooks/packages/k8s-workflow/gha-runner-rpc.py

target-dir := $(dir)/target

$(dir)/$(docker-build): $(target-dir) $(dir)/target/LICENSE $(rpc-script)

$(target-dir):
	mkdir -p $(target-dir)

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE
	cp $< $@

$(rpc-script): $(rpc-source)
	cp $< $@
