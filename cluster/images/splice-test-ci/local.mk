# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)

rpc-script := $(dir)/target/gha-runner-rpc.py
rpc-source := ${REPO_ROOT}/.github/runners/runner-container-hooks/packages/k8s-workflow/gha-runner-rpc.py

$(dir)/$(docker-build): $(dir)/target/LICENSE $(rpc-script)

$(dir)/target/LICENSE: LICENSE
	cp $< $@

$(rpc-script): $(rpc-source)
	cp $< $@
