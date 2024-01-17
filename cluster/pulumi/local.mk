dir := $(call current_dir)

.PHONY: $(dir)/build
$(dir)/build: $(dir)/.build

$(dir)/.build: $(dir)/package.json
	cd $(@D) && if [ -v CI ]; then npm ci; else npm install; fi
	touch $@

.PHONY: $(dir)/clean
$(dir)/clean:
	cd $(@D) && rm -rfv node_modules .build

.PHONY: $(dir)/format
$(dir)/format: $(dir)/.build
	cd $(@D) && npm run format:fix

pulumi_projects ::= infra canton-network sv-runbook validator-runbook

.PHONY: $(dir)/test $(dir)/update-expected
$(dir)/test: $(foreach project,$(pulumi_projects),$(dir)/$(project)/test)

.PHONY: $(dir)/update-expected
$(dir)/update-expected: $(foreach project,$(pulumi_projects),$(dir)/$(project)/update-expected)

include $(pulumi_projects:%=$(dir)/%/local.mk)
