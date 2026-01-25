# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

dir := $(call current_dir)
src_dir := ${SPLICE_ROOT}/.github/runners/runner-container-hooks
package_files := $(src_dir)/package.json $(src_dir)/packages/k8s/package.json $(src_dir)/packages/hooklib/package.json
source_files :=	$(shell find $(src_dir)/packages/k8s/src -name '*.ts') $(shell find $(src_dir)/packages/hooklib/src -name '*.ts')
target-dir := $(dir)/target

$(dir)/$(docker-build): $(target-dir) $(dir)/target/LICENSE $(dir)/target/.npm_installed $(dir)/target/index.js

$(target-dir):
	mkdir -p $(target-dir)

$(dir)/target/LICENSE: ${SPLICE_ROOT}/cluster/images/LICENSE
	cp $< $@

$(dir)/target/.npm_installed: $(package_files)
	touch $@
	npm install --prefix $(src_dir)
	npm run bootstrap --prefix $(src_dir)

$(dir)/target/index.js: $(source_files) $(dir)/target/.npm_installed
	npm run build-all --prefix $(src_dir)
	cp $(src_dir)/packages/k8s/dist/index.js $@
