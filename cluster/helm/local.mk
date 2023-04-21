charts := \
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
cluster/helm/clean:
	rm -rfv cluster/helm/target

#########
# Helm pattern rules
#########

# You cannot define implicit phony targets
# so instead we define the phony targets in here.
define DEFINE_PHONY_CHART_RULES =
prefix := cluster/helm/$(1)

.PHONY: $$(prefix)/helm-build
$$(prefix)/helm-build: $$(prefix)/Chart.yaml
	helm package $$(@D) --dependency-update --destination cluster/helm/target

.PHONY: $$(prefix)/helm-push
$$(prefix)/helm-push:
	jfrog rt upload $$(@D)/target/*.tgz artifactory

endef # end DEFINE_PHONY_CHART_RULESp

$(foreach chart,$(charts),$(eval $(call DEFINE_PHONY_CHART_RULES,$(chart))))

