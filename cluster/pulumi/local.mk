dir := $(call current_dir)

$(dir)/install: $(dir)/package.json
	cd $(@D) && if [ -v CI ]; then npm ci; else npm install; fi
	touch $@

.PHONY: $(dir)/clean
$(dir)/clean:
	cd $(@D) && rm -rfv node_modules install

.PHONY: $(dir)/format
$(dir)/format: $(dir)/install
	cd $(@D) && npm run format:fix

pulumi_projects ::= infra canton-network sv-runbook

.PHONY: $(dir)/test $(dir)/update-expected
$(dir)/test: $(foreach project,$(pulumi_projects),$(dir)/$(project)/diff-config)
$(dir)/update-expected: $(foreach project,$(pulumi_projects),$(dir)/$(project)/update-expected)

include $(pulumi_projects:%=$(dir)/%/local.mk)
