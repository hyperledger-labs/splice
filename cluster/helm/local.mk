app_charts := \
	cn-cluster-ingress-full \
	cn-cluster-ingress-sv \
	cn-directory-web-ui \
	cn-docs \
	cn-domain \
	cn-participant \
	cn-postgres \
	cn-splitwell-app \
	cn-splitwell-web-ui \
	cn-sv-node \
	cn-svc \
	cn-validator \
	cn-istio-fwd

all_charts := $(app_charts) cn-util-lib

.PHONY: cluster/helm/build
cluster/helm/build: $(foreach chart,$(all_charts),cluster/helm/$(chart)/helm-build)

.PHONY: cluster/helm/clean
cluster/helm/clean: $(foreach chart,$(all_charts),cluster/helm/$(chart)/helm-clean)
	rm -rfv cluster/helm/target

%/values.yaml: %/values-template.yaml HELM_CHART_VERSION
	cat $< | version-tag-subst > $@

%/Chart.yaml: %/Chart-template.yaml HELM_CHART_VERSION
	cat $< | version-tag-subst > $@

#########
# Helm pattern rules
#########

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_CHART_RULES =
prefix := cluster/helm/$(1)

.PHONY: $$(prefix)/helm-build
$$(prefix)/helm-build: $$(prefix)/values.yaml $$(prefix)/Chart.yaml
	helm package $$(@D) --dependency-update --destination cluster/helm/target

.PHONY: $$(prefix)/helm-clean
$$(prefix)/helm-clean:
	rm -vf $$(@D)/values.yaml $$(@D)/Chart.yaml

endef # end DEFINE_PHONY_CHART_RULES

define ADD_UTIL_DEP =
prefix := cluster/helm/$(1)
$$(prefix)/helm-build: cluster/helm/cn-util-lib/Chart.yaml
endef

$(foreach chart,$(all_charts),$(eval $(call DEFINE_PHONY_CHART_RULES,$(chart))))

$(foreach chart,$(app_charts),$(eval $(call ADD_UTIL_DEP,$(chart))))
