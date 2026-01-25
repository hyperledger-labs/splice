# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

deployment_dir ::= $(call current_dir)

clusters ::= scratchneta scratchnetb scratchnetc scratchnetd scratchnete
cluster_dirs ::= $(foreach cluster,$(clusters),$(deployment_dir)/$(cluster))
resolved_config_targets ::= $(foreach cluster_dir,$(cluster_dirs),$(cluster_dir)/config.resolved.yaml)

# We use .PHONY because it is hard to pinpoint exact deps for config resolution as it might depend
# on many config files and the config loader implementation. At some point, when we get rid of
# more specific config loading rules, it might make sense to try list the dependencies here.
define update_resolved_config
.PHONY: $(1)/config.resolved.yaml
$(1)/config.resolved.yaml: $(shell dirname $(deployment_dir))/pulumi/build
	source $(1)/.envrc.vars && \
	cd "${SPLICE_ROOT}/cluster/pulumi" && \
	npm run resolve-config
endef
$(foreach cluster_dir,$(cluster_dirs),$(eval $(call update_resolved_config,$(cluster_dir))))

.PHONY: $(deployment_dir)/update-resolved-config
$(deployment_dir)/update-resolved-config: $(resolved_config_targets)

.PHONY: $(deployment_dir)/check-resolved-config
$(deployment_dir)/check-resolved-config: $(resolved_config_targets)
	git diff --exit-code --quiet $(resolved_config_targets)
