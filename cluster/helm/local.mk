# Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

app_charts := \
	splice-cluster-ingress-runbook \
	splice-cometbft \
	cn-docs \
	splice-domain \
	splice-global-domain \
	splice-istio-gateway \
	splice-load-tester \
	splice-participant \
	splice-party-allocator \
	splice-postgres \
	splice-scan \
	splice-splitwell-app \
	splice-splitwell-web-ui \
	splice-sv-node \
	splice-validator \
	splice-info

HELM_VERSION_TAG := cluster/helm/.version-tag
IMAGE_DIGESTS := cluster/helm/.image-digests
APP_CHARTS_FILE := cluster/helm/.app-charts

# Makefile for each file in cluster/helm/target run `helm push $file`
.PHONY: cluster/helm/push
cluster/helm/push:
	@set -e; \
	for file in cluster/helm/target/*.tgz; do \
	    publish_helm_chart.sh $$file;\
	done

.PHONY: cluster/helm/write-version
cluster/helm/write-version:
	overwrite-if-changed '$(shell get-snapshot-version)' $(HELM_VERSION_TAG)

.PHONY: cluster/helm/write-digests
cluster/helm/write-digests:
	get-docker-image-digests.sh > $(IMAGE_DIGESTS)

.PHONY: cluster/helm/write-app-charts
cluster/helm/write-app-charts:
	overwrite-if-changed '$(shell echo $(app_charts) | tr ' ' '\n')' $(APP_CHARTS_FILE)
.PHONY: cluster/helm/copy_release_to_ghcr
cluster/helm/copy_release_to_ghcr: cluster/helm/write-app-charts cluster/helm/write-version
	./build-tools/copy_release_helm_charts_to_ghcr.sh -v $(shell cat cluster/helm/.version-tag) -f cluster/helm/.app-charts

.PHONY: cluster/helm/build
cluster/helm/build: $(foreach chart,$(app_charts),cluster/helm/$(chart)/helm-build)

.PHONY: cluster/helm/clean
cluster/helm/clean: $(foreach chart,$(app_charts),cluster/helm/$(chart)/helm-clean)
	rm -rfv cluster/helm/target

.PHONY: cluster/helm/test
cluster/helm/test: cluster/helm/build $(foreach chart,$(app_charts),cluster/helm/$(chart)/helm-test)

%/values.yaml: %/values-template.yaml
  # We do not automatically run write-digests, as we do not want that for local dev, only for published artifacts
	cp $< $@
	if [ -f "$(IMAGE_DIGESTS)" ]; then \
		cat "$(IMAGE_DIGESTS)" >> $@ ; \
	fi

%/Chart.yaml: %/Chart-template.yaml cluster/helm/write-version
	@version-tag-subst "$$(< $(HELM_VERSION_TAG))" < $< > $@

#########
# Helm pattern rules
#########

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_CHART_RULES =
prefix := cluster/helm/$(1)

.PHONY: $$(prefix)/helm-build
$$(prefix)/helm-build: $$(prefix)/values.yaml $$(prefix)/Chart.yaml $$(prefix)/LICENSE
	helm package $$(@D) --dependency-update --destination cluster/helm/target

.PHONY: $$(prefix)/helm-clean
$$(prefix)/helm-clean:
	rm -vf $$(@D)/values.yaml $$(@D)/Chart.yaml $$(@D)/LICENSE

.PHONY: $$(prefix)/helm-test
$$(prefix)/helm-test:
	helm unittest $$(@D)

$$(prefix)/LICENSE: LICENSE
	cp LICENSE $$(@D)/LICENSE

endef # end DEFINE_PHONY_CHART_RULES

define ADD_UTIL_DEP =
prefix := cluster/helm/$(1)
$$(prefix)/helm-build: cluster/helm/splice-util-lib/Chart.yaml
endef

$(foreach chart,$(app_charts),$(eval $(call DEFINE_PHONY_CHART_RULES,$(chart))))

$(foreach chart,$(app_charts),$(eval $(call ADD_UTIL_DEP,$(chart))))
