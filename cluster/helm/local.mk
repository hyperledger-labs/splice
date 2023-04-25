charts := \
	cn-util-lib \
	\
	cn-cluster-ingress \
	cn-directory-web-ui \
	cn-docs \
	cn-domain \
	cn-participant \
	cn-postgres \
	cn-splitwell-app \
	cn-splitwell-web-ui \
	cn-sv-node \
	cn-svc \
	cn-validator

.PHONY: cluster/helm/build
cluster/helm/build: $(foreach chart,$(charts),cluster/helm/$(chart)/helm-build)

.PHONY: cluster/helm/clean
cluster/helm/clean: $(foreach chart,$(charts),cluster/helm/$(chart)/helm-clean)
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

$(foreach chart,$(charts),$(eval $(call DEFINE_PHONY_CHART_RULES,$(chart))))

