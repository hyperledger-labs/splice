app_charts := \
	cn-cluster-ingress-full \
	cn-cluster-ingress-runbook \
	cn-cluster-loopback-gateway \
	cn-cometbft \
	cn-docs \
	cn-domain \
	cn-global-domain \
	cn-istio-fwd \
	cn-istio-gateway \
	cn-participant \
	cn-postgres \
	cn-postgres-metrics \
	cn-scan \
	cn-splitwell-app \
	cn-splitwell-web-ui \
	cn-sv-node \
	cn-validator \
	cn-directory

all_charts := $(app_charts) cn-util-lib

HELM_VERSION_TAG := cluster/helm/.version-tag

.PHONY: cluster/helm/write-version
cluster/helm/write-version:
	overwrite-if-changed '$(shell get-snapshot-version)' $(HELM_VERSION_TAG)

.PHONY: cluster/helm/build
cluster/helm/build: $(foreach chart,$(all_charts),cluster/helm/$(chart)/helm-build)

.PHONY: cluster/helm/clean
cluster/helm/clean: $(foreach chart,$(all_charts),cluster/helm/$(chart)/helm-clean)
	rm -rfv cluster/helm/target

%/values.yaml: %/values-template.yaml cluster/helm/write-version
	@version-tag-subst "$$(< $(HELM_VERSION_TAG))" < $< > $@

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

$$(prefix)/LICENSE: LICENSE.txt
	cp LICENSE.txt $$(@D)/LICENSE

endef # end DEFINE_PHONY_CHART_RULES

define ADD_UTIL_DEP =
prefix := cluster/helm/$(1)
$$(prefix)/helm-build: cluster/helm/cn-util-lib/Chart.yaml
endef

$(foreach chart,$(all_charts),$(eval $(call DEFINE_PHONY_CHART_RULES,$(chart))))

$(foreach chart,$(app_charts),$(eval $(call ADD_UTIL_DEP,$(chart))))
