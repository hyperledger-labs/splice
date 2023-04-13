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

helm-build := target/helm.build

.PHONY: cluster/helm/build
cluster/helm/build: $(foreach chart,$(charts),cluster/helm/$(chart)/$(helm-build))

.PHONY: cluster/helm/clean
cluster/helm/clean: $(foreach chart,$(charts),cluster/helm/$(chart)/helm-clean)
	echo clean here

#########
# Helm pattern rules
#########

%/$(helm-build): %/Chart.yaml
	cd cluster/helm && helm dependency update $(notdir $(abspath $(@D)/..))
	mkdir -pv $(@D)
	touch $@

.PHONY: %/helm-clean
%/helm-clean:
	rm -rfv $(@D)/target $(@D)/charts
