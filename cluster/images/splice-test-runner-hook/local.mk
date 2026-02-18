# Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)
src_dir := ${SPLICE_ROOT}/.github/runners/runner-container-hooks
package_files := $(src_dir)/package.json $(src_dir)/packages/k8s/package.json $(src_dir)/packages/hooklib/package.json
source_files :=	$(shell find $(src_dir)/packages/k8s/src -name '*.ts') $(shell find $(src_dir)/packages/hooklib/src -name '*.ts')

$(dir)/$(docker-build): $(dir)/target/LICENSE $(dir)/target/.npm_installed $(dir)/target/index.js

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE | $(dir)/target
	cp $< $@

$(dir)/target/.npm_installed: $(package_files) | $(dir)/target
	touch $@
	npm ci --prefix $(src_dir)
    # we run ci here and not the bootstrap script because it modifies the package.json file
	npm ci --prefix $(src_dir)/packages/hooklib && npm ci --prefix $(src_dir)/packages/k8s && npm ci --prefix $(src_dir)/packages/docker

$(dir)/target/index.js: $(source_files) $(dir)/target/.npm_installed | $(dir)/target
	npm run build-all --prefix $(src_dir)
	cp $(src_dir)/packages/k8s/dist/index.js $@
